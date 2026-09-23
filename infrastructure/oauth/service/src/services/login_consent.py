from typing import Optional
from urllib.parse import urlencode

from clients.hydra import HydraClient
from clients.kratos import KratosClient
from config import Config


class BridgeError(RuntimeError):
    pass


def role_for_subject(kratos_client: KratosClient, subject: str) -> Optional[str]:
    """The Kratos session (and the public /sessions/whoami response) never
    exposes metadata_admin, so the role is read via an Admin API call on
    the identity."""
    identity = kratos_client.get_identity(subject)
    return (identity.get("metadata_admin") or {}).get("role")


def handle_login(
    config: Config,
    hydra_client: HydraClient,
    kratos_client: KratosClient,
    login_challenge: str,
    cookie_header: Optional[str],
) -> str:
    """Returns the URL the browser should be redirected to next."""
    login_request = hydra_client.get_login_request(login_challenge)

    if login_request.get("skip"):
        # Hydra already knows the subject for this session (e.g. remembered
        # from a previous login) — accept again with the same subject.
        subject = login_request["subject"]
    else:
        session = kratos_client.whoami(cookie_header)
        if session is None:
            # Not authenticated yet — send the browser through Kratos's own
            # login flow, returning back to this same endpoint once it
            # succeeds. Kratos remains solely responsible for verifying the
            # password.
            return_to = _self_url(config, "/login", login_challenge=login_challenge)
            return kratos_client.browser_login_url(config.kratos_browser_url, return_to)

        subject = session["identity"]["id"]

    role = role_for_subject(kratos_client, subject)
    context = {"role": role} if role else None

    accepted = hydra_client.accept_login_request(login_challenge, subject, context=context)
    return accepted["redirect_to"]


def handle_consent(config: Config, hydra_client: HydraClient, consent_challenge: str) -> str:
    """Returns the URL the browser should be redirected to next. Auto-accepts
    every scope Hydra reports as requested — Hydra itself already restricts
    requested_scope to whatever the client was registered with in
    access-control.yml, so no additional scope filtering is needed here."""
    consent_request = hydra_client.get_consent_request(consent_challenge)

    role = (consent_request.get("context") or {}).get("role")
    access_token_claims = {"role": role} if role else None

    accepted = hydra_client.accept_consent_request(
        consent_challenge,
        grant_scope=consent_request.get("requested_scope", []),
        grant_access_token_audience=consent_request.get("requested_access_token_audience", []),
        access_token_claims=access_token_claims,
    )
    return accepted["redirect_to"]


def _self_url(config: Config, path: str, **query: str) -> str:
    base = f"http://127.0.0.1:{config.bridge_port}{path}"
    if query:
        return f"{base}?{urlencode(query)}"
    return base
