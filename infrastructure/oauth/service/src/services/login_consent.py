from typing import Optional
from urllib.parse import urlencode

from clients.hydra import HydraClient
from clients.kratos import KratosClient, KratosSecondFactorRequiredError
from clients.spring_auth import SpringAuthClient
from config import Config
from services.bootstrap import ADMIN_WILDCARD, load_and_validate_spec


class BridgeError(RuntimeError):
    pass


class UnprovisionedIdentityError(BridgeError):
    """Raised when a Kratos identity has no linked canonical Spring user
    (metadata_admin.spring_user_id). Login must fail explicitly here rather
    than fall back to issuing a JWT with the Kratos UUID as subject."""


def resolve_canonical_user(kratos_client: KratosClient, spring_auth_client: SpringAuthClient, kratos_identity_id: str) -> dict:
    """Spring is authoritative for id/role; Kratos only stores a reference to
    it in metadata_admin.spring_user_id, never exposed via public APIs."""
    identity = kratos_client.get_identity(kratos_identity_id)
    spring_user_id = (identity.get("metadata_admin") or {}).get("spring_user_id")

    if spring_user_id is None:
        raise UnprovisionedIdentityError(
            f"Kratos identity {kratos_identity_id} has no linked Spring user"
        )

    return spring_auth_client.resolve_user(spring_user_id)


def handle_login(
    config: Config,
    hydra_client: HydraClient,
    kratos_client: KratosClient,
    spring_auth_client: SpringAuthClient,
    login_challenge: str,
    cookie_header: Optional[str],
) -> str:
    """Returns the URL the browser should be redirected to next."""
    login_request = hydra_client.get_login_request(login_challenge)

    return_to = _self_url(config, "/login", login_challenge=login_challenge)
    try:
        session = kratos_client.whoami(cookie_header)
    except KratosSecondFactorRequiredError:
        return kratos_client.browser_login_url(config.kratos_browser_url, return_to, aal="aal2")
    if session is None:
        return kratos_client.browser_login_url(config.kratos_browser_url, return_to)

    kratos_identity_id = session["identity"]["id"]
    spring_user = resolve_canonical_user(kratos_client, spring_auth_client, kratos_identity_id)
    subject = str(spring_user["id"])
    if login_request.get("skip") and login_request.get("subject") != subject:
        raise BridgeError("Remembered OAuth subject does not match the authenticated identity")

    accepted = hydra_client.accept_login_request(
        login_challenge, subject, context={"role": spring_user["role"]}
    )
    return accepted["redirect_to"]


def handle_consent(config: Config, hydra_client: HydraClient, consent_challenge: str) -> str:
    """Grant only requested scopes allowed by both the client and Spring role,
    including on remembered consent. Machine scopes are never human grants."""
    consent_request = hydra_client.get_consent_request(consent_challenge)
    spec = load_and_validate_spec(config.access_control_file)

    role = (consent_request.get("context") or {}).get("role")
    role_spec = next((entry for entry in spec.roles if entry.name == role), None)
    if not isinstance(role, str) or role_spec is None:
        raise BridgeError("Consent requires a known application role")

    client = consent_request.get("client") or {}
    client_spec = next((entry for entry in spec.clients if entry.client_id == client.get("client_id")), None)
    if client_spec is None or "authorization_code" not in client_spec.grant_types:
        raise BridgeError("Consent requires a registered human OAuth client")
    if not isinstance(client.get("scope"), str):
        raise BridgeError("Consent client scope allow-list is missing")

    declared = {scope.name for scope in spec.scopes}
    role_scopes = declared if role_spec.scopes == ADMIN_WILDCARD else set(role_spec.scopes)
    client_scopes = declared if client_spec.scopes is None else set(client_spec.scopes)
    allowed = (role_scopes & client_scopes) | set(client_spec.additional_scopes)
    allowed &= set(client["scope"].split())
    allowed.discard("internal:provision-user")
    granted = sorted(set(consent_request.get("requested_scope") or []) & allowed)

    accepted = hydra_client.accept_consent_request(
        consent_challenge,
        grant_scope=granted,
        grant_access_token_audience=consent_request.get("requested_access_token_audience", []),
        access_token_claims={"role": role},
    )
    return accepted["redirect_to"]


def _self_url(config: Config, path: str, **query: str) -> str:
    base = f"http://127.0.0.1:{config.bridge_port}{path}"
    if query:
        return f"{base}?{urlencode(query)}"
    return base
