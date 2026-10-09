from typing import Optional
from urllib.parse import urlencode, parse_qs, urlparse

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


class UnverifiedIdentityError(BridgeError):
    pass


def resolve_canonical_user(kratos_client: KratosClient, spring_auth_client: SpringAuthClient, kratos_identity_id: str) -> dict:
    """Spring is authoritative for id/role; Kratos only stores a reference to
    it in metadata_admin.spring_user_id, never exposed via public APIs."""
    identity = kratos_client.get_identity(kratos_identity_id)
    spring_user_id = (identity.get("metadata_admin") or {}).get("spring_user_id")

    if identity.get("schema_id") == "customer-v1":
        email = (identity.get("traits") or {}).get("email")
        verified = any(
            address.get("via") == "email" and address.get("verified") is True
            and isinstance(email, str) and email.casefold() == str(address.get("value", "")).casefold()
            for address in identity.get("verifiable_addresses") or []
        )
        if not verified:
            raise UnverifiedIdentityError("Verify your email before signing in.")
        canonical = spring_auth_client.finalize_registration(kratos_identity_id)
        if spring_user_id is not None and str(spring_user_id) != str(canonical["id"]):
            raise BridgeError("Canonical identity metadata does not match")
        if spring_user_id is None:
            metadata = dict(identity.get("metadata_admin") or {})
            metadata["spring_user_id"] = canonical["id"]
            kratos_client.set_identity_metadata_admin(kratos_identity_id, metadata)
        return canonical

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
    if (login_request.get("client") or {}).get("client_id") == config.bff_client_id:
        return _storefront_url(config, "/auth/challenge", login_challenge=login_challenge)

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


def handle_consent(
    config: Config, hydra_client: HydraClient, consent_challenge: str,
    expected_client_id: Optional[str] = None,
    expected_state: Optional[str] = None,
) -> str:
    """Grant only requested scopes allowed by both the client and Spring role,
    including on remembered consent. Machine scopes are never human grants."""
    consent_request = hydra_client.get_consent_request(consent_challenge)
    if expected_client_id is None and (consent_request.get("client") or {}).get("client_id") == config.bff_client_id:
        return _storefront_url(config, "/auth/consent", consent_challenge=consent_challenge)
    if expected_state is not None and parse_qs(
        urlparse(consent_request.get("request_url", "")).query
    ).get("state") != [expected_state]:
        raise BridgeError("Consent does not match the authorization transaction")
    spec = load_and_validate_spec(config.access_control_file)

    role = (consent_request.get("context") or {}).get("role")
    role_spec = next((entry for entry in spec.roles if entry.name == role), None)
    if not isinstance(role, str) or role_spec is None:
        raise BridgeError("Consent requires a known application role")

    client = consent_request.get("client") or {}
    if expected_client_id is not None and client.get("client_id") != expected_client_id:
        raise BridgeError("Consent belongs to a different OAuth client")
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


def bff_session(kratos_client: KratosClient, spring_auth_client: SpringAuthClient, cookie: str) -> dict:
    session = kratos_client.whoami(cookie)
    if session is None:
        return {"active": False}
    canonical = resolve_canonical_user(kratos_client, spring_auth_client, session["identity"]["id"])
    return {
        "active": True, "identity_id": session["identity"]["id"],
        "session_id": session["id"], "expires_at": session["expires_at"],
        "aal": session["authenticator_assurance_level"],
        "user_id": str(canonical["id"]), "role": canonical["role"],
    }


def bff_login(config: Config, hydra_client: HydraClient, kratos_client: KratosClient,
              spring_auth_client: SpringAuthClient, challenge: str, cookie: str, state: str) -> dict:
    request = hydra_client.get_login_request(challenge)
    if (request.get("client") or {}).get("client_id") != config.bff_client_id:
        raise BridgeError("Login belongs to a different OAuth client")
    if parse_qs(urlparse(request.get("request_url", "")).query).get("state") != [state]:
        raise BridgeError("Login does not match the authorization transaction")
    try:
        session = bff_session(kratos_client, spring_auth_client, cookie)
    except KratosSecondFactorRequiredError:
        return {"login_required": True, "aal": "aal2"}
    except UnverifiedIdentityError:
        return {"verification_required": True}
    if not session["active"]:
        return {"login_required": True, "aal": "aal1"}
    if request.get("skip") and str(request.get("subject")) != session["user_id"]:
        raise BridgeError("Remembered OAuth subject does not match")
    accepted = hydra_client.accept_login_request(
        challenge, session["user_id"], context={"role": session["role"]},
    )
    return {"redirect_to": accepted["redirect_to"], "session": session}


def _self_url(config: Config, path: str, **query: str) -> str:
    base = f"{config.bridge_browser_url}{path}"
    if query:
        return f"{base}?{urlencode(query)}"
    return base


def _storefront_url(config: Config, path: str, **query: str) -> str:
    callback = urlparse(config.bff_redirect_uri)
    return f"{callback.scheme}://{callback.netloc}{path}?{urlencode(query)}"
