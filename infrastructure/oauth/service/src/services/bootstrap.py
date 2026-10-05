import logging
import re
from dataclasses import dataclass, field
from datetime import date
from typing import Union

import requests
import yaml

from clients.hydra import HydraClient
from clients.kratos import KratosClient
from config import Config

logger = logging.getLogger(__name__)

SCOPE_NAME_PATTERN = re.compile(r"^[a-z][a-z-]*:[a-z][a-z-]*$")
ADMIN_WILDCARD = "*"

# Reserved OAuth2/OIDC protocol scopes — not application resource scopes, so
# they intentionally don't follow SCOPE_NAME_PATTERN (`<resource>:<action>`)
# and are never part of the roles[].scopes catalogue or the ADMIN wildcard
# expansion. "offline_access" is what Fosite/Hydra look for to decide
# whether to issue a refresh token for the authorization_code grant; it's
# opted into per-client via `clients[].additional_scopes`, not the shared
# `scopes:` catalogue.
RESERVED_SCOPES = frozenset({"offline_access"})

# Fields the bootstrap owns and reconciles on the Hydra client. Any other
# field Hydra returns (timestamps, internal metadata, etc.) is ignored so
# re-runs don't spuriously report a diff.
_MANAGED_CLIENT_FIELDS = (
    "client_name",
    "grant_types",
    "response_types",
    "token_endpoint_auth_method",
    "redirect_uris",
    "scope",
)


class AccessControlSpecError(ValueError):
    """Raised when access-control.yml is missing, malformed, or invalid."""


@dataclass(frozen=True)
class Scope:
    name: str
    description: str


@dataclass(frozen=True)
class Role:
    name: str
    description: str
    scopes: Union[str, list[str]]  # "*" sentinel, or an explicit scope-name list


@dataclass(frozen=True)
class ClientSpec:
    client_id: str
    client_name: str
    grant_types: list[str]
    response_types: list[str]
    token_endpoint_auth_method: str
    redirect_uris: list[str]
    scopes: Union[list[str], None] = None  # None = every declared scope (legacy default)
    # Reserved OAuth2/OIDC protocol scopes (see RESERVED_SCOPES) granted to
    # this client in addition to `scopes` — always appended, regardless of
    # whether `scopes` is an explicit list or the "every declared scope"
    # default.
    additional_scopes: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class AccessControlSpec:
    version: int
    scopes: list[Scope]
    roles: list[Role]
    clients: list[ClientSpec]


def expand_admin_scopes(spec: AccessControlSpec) -> list[str]:
    """Returns every declared scope name, sorted. Not used by the client
    reconciliation path today — kept as a pure utility for a future
    Spring-side authorization task that may need to expand the ADMIN
    wildcard sentinel."""
    return sorted(scope.name for scope in spec.scopes)


def load_and_validate_spec(path: str) -> AccessControlSpec:
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f)

    if not isinstance(raw, dict):
        raise AccessControlSpecError(f"{path}: expected a YAML mapping at the top level")

    version = raw.get("version")
    if version != 1:
        raise AccessControlSpecError(f"{path}: unsupported version {version!r}, expected 1")

    scopes = _parse_scopes(raw.get("scopes"), path)
    scope_names = {scope.name for scope in scopes}

    roles = _parse_roles(raw.get("roles"), path, scope_names)
    clients = _parse_clients(raw.get("clients"), path, scope_names)

    return AccessControlSpec(version=version, scopes=scopes, roles=roles, clients=clients)


def _parse_scopes(raw_scopes, path: str) -> list[Scope]:
    if not isinstance(raw_scopes, list) or not raw_scopes:
        raise AccessControlSpecError(f"{path}: 'scopes' must be a non-empty list")

    scopes = []
    seen_names = set()
    for entry in raw_scopes:
        name = entry.get("name")
        description = entry.get("description", "")

        if not isinstance(name, str) or not SCOPE_NAME_PATTERN.match(name):
            raise AccessControlSpecError(
                f"{path}: invalid scope name {name!r}, expected '<resource>:<action>'"
            )
        if name in seen_names:
            raise AccessControlSpecError(f"{path}: duplicate scope name {name!r}")

        seen_names.add(name)
        scopes.append(Scope(name=name, description=description))

    return scopes


def _parse_roles(raw_roles, path: str, scope_names: set[str]) -> list[Role]:
    if not isinstance(raw_roles, list) or not raw_roles:
        raise AccessControlSpecError(f"{path}: 'roles' must be a non-empty list")

    roles = []
    for entry in raw_roles:
        name = entry.get("name")
        description = entry.get("description", "")
        role_scopes = entry.get("scopes")

        if role_scopes == ADMIN_WILDCARD:
            roles.append(Role(name=name, description=description, scopes=ADMIN_WILDCARD))
            continue

        if not isinstance(role_scopes, list):
            raise AccessControlSpecError(
                f"{path}: role {name!r} 'scopes' must be a list or the '*' sentinel"
            )

        unknown = [s for s in role_scopes if s not in scope_names]
        if unknown:
            raise AccessControlSpecError(
                f"{path}: role {name!r} references undeclared scope(s): {unknown}"
            )

        roles.append(Role(name=name, description=description, scopes=role_scopes))

    return roles


def _parse_clients(raw_clients, path: str, scope_names: set[str]) -> list[ClientSpec]:
    if not isinstance(raw_clients, list) or not raw_clients:
        raise AccessControlSpecError(f"{path}: 'clients' must be a non-empty list")

    clients = []
    for entry in raw_clients:
        raw_scopes = entry.get("scopes")
        scopes = None
        if raw_scopes is not None:
            unknown = [s for s in raw_scopes if s not in scope_names]
            if unknown:
                raise AccessControlSpecError(
                    f"{path}: client {entry.get('client_id')!r} references undeclared scope(s): {unknown}"
                )
            scopes = list(raw_scopes)

        raw_additional_scopes = entry.get("additional_scopes", [])
        unknown_reserved = [s for s in raw_additional_scopes if s not in RESERVED_SCOPES]
        if unknown_reserved:
            raise AccessControlSpecError(
                f"{path}: client {entry.get('client_id')!r} references unknown reserved scope(s) "
                f"{unknown_reserved} (expected one of {sorted(RESERVED_SCOPES)})"
            )

        clients.append(
            ClientSpec(
                client_id=entry["client_id"],
                client_name=entry.get("client_name", entry["client_id"]),
                grant_types=list(entry.get("grant_types", [])),
                response_types=list(entry.get("response_types", [])),
                token_endpoint_auth_method=entry.get("token_endpoint_auth_method", "none"),
                redirect_uris=list(entry.get("redirect_uris", [])),
                scopes=scopes,
                additional_scopes=list(raw_additional_scopes),
            )
        )

    return clients


# --- Hydra OAuth2 client reconciliation ---


def _build_desired_client_payload(client: ClientSpec, spec: AccessControlSpec) -> dict:
    scopes = set(client.scopes if client.scopes is not None else [scope.name for scope in spec.scopes])
    scopes |= set(client.additional_scopes)
    return {
        "client_id": client.client_id,
        "client_name": client.client_name,
        "grant_types": list(client.grant_types),
        "response_types": list(client.response_types),
        "token_endpoint_auth_method": client.token_endpoint_auth_method,
        "redirect_uris": list(client.redirect_uris),
        "scope": " ".join(sorted(scopes)),
    }


def _is_client_in_sync(existing: dict, desired: dict) -> bool:
    for field in _MANAGED_CLIENT_FIELDS:
        existing_value = existing.get(field)
        desired_value = desired.get(field)

        if field == "scope":
            if set((existing_value or "").split()) != set((desired_value or "").split()):
                return False
            continue

        if isinstance(desired_value, list):
            if set(existing_value or []) != set(desired_value):
                return False
            continue

        if existing_value != desired_value:
            return False

    return True


def reconcile_hydra_clients(hydra_client: HydraClient, spec: AccessControlSpec, config: Config) -> None:
    for client in spec.clients:
        desired = _build_desired_client_payload(client, spec)
        existing = hydra_client.get_client(client.client_id)

        if existing is None:
            create_payload = dict(desired)
            # The secret is a real credential, never checked into
            # access-control.yml — it's injected here from Config (env) and
            # only ever sent on creation, never on update (Hydra hashes it;
            # resending on every reconcile run would rotate it unexpectedly).
            if client.client_id == config.oauth_service_client_id:
                create_payload["client_secret"] = config.oauth_service_client_secret
            hydra_client.create_client(create_payload)
            logger.info("created Hydra client %s", client.client_id)
            continue

        if not _is_client_in_sync(existing, desired):
            hydra_client.update_client(client.client_id, desired)
            logger.info("updated Hydra client %s", client.client_id)
            continue

        logger.info("Hydra client %s already in sync, no-op", client.client_id)


# --- Kratos dev admin identity reconciliation ---

# Deterministic dev fixture for the one field the canonical Spring User
# requires that this bootstrap has no other source for. Not meaningful data,
# just a non-null placeholder satisfying the DB constraint.
DEV_ADMIN_DATE_OF_BIRTH = date(2000, 1, 1)

# The role Spring assigns when provisioning the dev admin. This is Spring's
# own authoritative decision for this one bootstrapped user, not something
# read back from Kratos — Kratos never stores role at all any more.
DEV_ADMIN_ROLE = "ADMIN"


class BootstrapIdentityError(RuntimeError):
    """Privileged bootstrap cannot establish safe identity provenance."""


def reconcile_kratos_admin_identity(
    kratos_client: KratosClient, spring_auth_client, config: Config, schema_id: str = "default"
) -> None:
    """Never adopt an email match or heal privileged links/roles. Only a
    bootstrap-created private provenance marker permits a read-only restart."""
    provenance = {"version": 1, "username": config.admin_username, "email": config.admin_email}
    try:
        existing = kratos_client.find_identity_by_email(config.admin_email)
    except (requests.RequestException, ValueError):
        raise BootstrapIdentityError("Administrator bootstrap identity lookup failed") from None

    linked_id = None
    if existing is not None:
        metadata = existing.get("metadata_admin") or {}
        traits = existing.get("traits") or {}
        linked_id = metadata.get("spring_user_id") if isinstance(metadata, dict) else None
        if (
            not isinstance(metadata, dict)
            or metadata.get("bootstrap") != provenance
            or not isinstance(traits, dict)
            or traits.get("email") != config.admin_email
            or existing.get("schema_id") != schema_id
            or type(linked_id) is not int
            or linked_id <= 0
        ):
            raise BootstrapIdentityError(
                "Administrator bootstrap identity is untrusted or conflicting; manual reconciliation required"
            )
        try:
            spring_user = spring_auth_client.resolve_user(linked_id)
        except (requests.RequestException, ValueError):
            raise BootstrapIdentityError(
                "Administrator bootstrap link cannot be resolved; manual reconciliation required"
            ) from None
    else:
        try:
            spring_user = spring_auth_client.provision_bootstrap_admin(
                config.admin_username, config.admin_email, DEV_ADMIN_DATE_OF_BIRTH
            )
        except (requests.RequestException, ValueError):
            raise BootstrapIdentityError(
                "Administrator bootstrap requires safe create-only canonical provisioning; manual reconciliation required"
            ) from None

    expected = {"username": config.admin_username, "email": config.admin_email, "role": DEV_ADMIN_ROLE}
    if (
        not isinstance(spring_user, dict)
        or type(spring_user.get("id")) is not int
        or spring_user["id"] <= 0
        or (linked_id is not None and spring_user["id"] != linked_id)
        or any(spring_user.get(key) != value for key, value in expected.items())
    ):
        raise BootstrapIdentityError("Administrator bootstrap canonical user conflicts; manual reconciliation required")

    if existing is not None:
        logger.info("trusted administrator bootstrap identity already linked, no-op")
        return

    try:
        kratos_client.create_identity(
            schema_id, config.admin_email, config.admin_password,
            metadata_admin={"spring_user_id": spring_user["id"], "bootstrap": provenance},
        )
    except (requests.RequestException, ValueError):
        raise BootstrapIdentityError(
            "Administrator bootstrap identity creation failed; manual reconciliation required"
        ) from None
    logger.info("created trusted administrator bootstrap identity")


def run(config: Config, hydra_client: HydraClient, kratos_client: KratosClient, spring_auth_client) -> None:
    hydra_client.wait_until_ready(config.retry_attempts, config.retry_delay_seconds)
    kratos_client.wait_until_ready(config.retry_attempts, config.retry_delay_seconds)
    spring_auth_client.wait_until_ready(config.retry_attempts, config.retry_delay_seconds)

    spec = load_and_validate_spec(config.access_control_file)
    reconcile_hydra_clients(hydra_client, spec, config)
    reconcile_kratos_admin_identity(kratos_client, spring_auth_client, config)
