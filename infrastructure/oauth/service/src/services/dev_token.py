import hashlib
import secrets
from base64 import urlsafe_b64encode
from typing import Optional

from clients.hydra import HydraClient, parse_query_param
from clients.kratos import KratosAuthenticationError, KratosClient
from clients.spring_auth import SpringAuthClient
from config import Config
from models import TokenResponse
from services.login_consent import handle_consent, resolve_canonical_user


def _generate_pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(verifier.encode()).digest()
    challenge = urlsafe_b64encode(digest).rstrip(b"=").decode()
    return verifier, challenge


def issue_dev_token(
    config: Config,
    hydra_client: HydraClient,
    kratos_client: KratosClient,
    spring_auth_client: SpringAuthClient,
    email: Optional[str],
    password: Optional[str],
    kratos_session_token: Optional[str] = None,
) -> TokenResponse:
    """Headlessly drives the same authorization-code flow a browser would,
    for local developer tooling only. Accepts either a password login or
    an externally completed native Kratos session, then validates assurance.
    Kratos verifies credentials and Hydra issues/signs the token. This
    function orchestrates the challenge exchange and reuses the browser
    bridge's consent handling."""
    if kratos_session_token:
        if email is not None or password is not None:
            raise KratosAuthenticationError("Use only one authentication mode")
    else:
        if not email or not password:
            raise KratosAuthenticationError("Missing authentication credentials")
        kratos_login = kratos_client.authenticate_with_password(email, password)
        kratos_session_token = kratos_login.get("session_token")
    if not isinstance(kratos_session_token, str) or not kratos_session_token:
        raise KratosAuthenticationError("Kratos did not return a native session token")
    kratos_session = kratos_client.whoami(None, session_token=kratos_session_token)
    if kratos_session is None:
        raise KratosAuthenticationError("Invalid or expired Kratos session")
    kratos_identity_id = kratos_session["identity"]["id"]
    spring_user = resolve_canonical_user(kratos_client, spring_auth_client, kratos_identity_id)
    subject = str(spring_user["id"])

    code_verifier, code_challenge = _generate_pkce_pair()
    state = secrets.token_urlsafe(16)
    http_session = hydra_client.new_authorization_session()

    login_redirect = hydra_client.start_authorization(
        http_session,
        client_id=config.dev_client_id,
        redirect_uri=config.dev_redirect_uri,
        scope=_registered_scope(hydra_client, config.dev_client_id),
        code_challenge=code_challenge,
        state=state,
    )
    login_challenge = parse_query_param(login_redirect, "login_challenge")

    accepted_login = hydra_client.accept_login_request(
        login_challenge, subject, context={"role": spring_user["role"]}
    )

    consent_redirect = hydra_client.follow_redirect(http_session, accepted_login["redirect_to"])
    consent_challenge = parse_query_param(consent_redirect, "consent_challenge")

    accepted_consent_redirect = handle_consent(config, hydra_client, consent_challenge)

    final_redirect = hydra_client.follow_redirect(http_session, accepted_consent_redirect)
    code = parse_query_param(final_redirect, "code")

    token = hydra_client.exchange_code_for_token(
        client_id=config.dev_client_id,
        code=code,
        redirect_uri=config.dev_redirect_uri,
        code_verifier=code_verifier,
    )

    return TokenResponse(
        access_token=token["access_token"],
        token_type=token.get("token_type", "bearer"),
        expires_in=token.get("expires_in"),
        scope=token.get("scope"),
        refresh_token=token.get("refresh_token"),
        kratos_session_token=kratos_session_token,
    )


def _registered_scope(hydra_client: HydraClient, client_id: str) -> str:
    # Request every scope the client is registered with in Hydra (reconciled
    # from access-control.yml by services.bootstrap) — mirroring what the SPA
    # itself would ask for, since Hydra rejects any scope outside this set.
    client = hydra_client.get_client(client_id)
    if client is None:
        raise RuntimeError(f"Hydra client {client_id!r} is not registered")
    return client.get("scope", "")
