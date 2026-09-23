import hashlib
import secrets
from base64 import urlsafe_b64encode

from clients.hydra import HydraClient, parse_query_param
from clients.kratos import KratosClient
from config import Config
from models import TokenResponse
from services.login_consent import role_for_subject


def _generate_pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(verifier.encode()).digest()
    challenge = urlsafe_b64encode(digest).rstrip(b"=").decode()
    return verifier, challenge


def issue_dev_token(
    config: Config,
    hydra_client: HydraClient,
    kratos_client: KratosClient,
    email: str,
    password: str,
) -> TokenResponse:
    """Headlessly drives the same authorization-code flow a browser would,
    for local developer tooling only. Kratos verifies the password and Hydra
    issues/signs the token — this function only orchestrates the existing
    login/consent challenge exchange, exactly as services.login_consent does
    for the browser-driven flow."""
    kratos_session = kratos_client.authenticate_with_password(email, password)
    subject = kratos_session["identity"]["id"]

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

    role = role_for_subject(kratos_client, subject)
    login_context = {"role": role} if role else None
    accepted_login = hydra_client.accept_login_request(
        login_challenge, subject, context=login_context
    )

    consent_redirect = hydra_client.follow_redirect(http_session, accepted_login["redirect_to"])
    consent_challenge = parse_query_param(consent_redirect, "consent_challenge")

    consent_request = hydra_client.get_consent_request(consent_challenge)
    consent_role = (consent_request.get("context") or {}).get("role")
    access_token_claims = {"role": consent_role} if consent_role else None
    accepted_consent = hydra_client.accept_consent_request(
        consent_challenge,
        grant_scope=consent_request.get("requested_scope", []),
        grant_access_token_audience=consent_request.get("requested_access_token_audience", []),
        access_token_claims=access_token_claims,
    )

    final_redirect = hydra_client.follow_redirect(http_session, accepted_consent["redirect_to"])
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
    )


def _registered_scope(hydra_client: HydraClient, client_id: str) -> str:
    # Request every scope the client is registered with in Hydra (reconciled
    # from access-control.yml by services.bootstrap) — mirroring what the SPA
    # itself would ask for, since Hydra rejects any scope outside this set.
    client = hydra_client.get_client(client_id)
    if client is None:
        raise RuntimeError(f"Hydra client {client_id!r} is not registered")
    return client.get("scope", "")
