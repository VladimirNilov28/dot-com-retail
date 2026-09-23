import logging
import re
from dataclasses import dataclass
from typing import Union

import yaml

from clients.hydra import HydraClient
from clients.kratos import KratosClient
from config import Config

logger = logging.getLogger(__name__)

SCOPE_NAME_PATTERN = re.compile(r"^[a-z][a-z-]*:[a-z][a-z-]*$")
ADMIN_WILDCARD = "*"

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
    clients = _parse_clients(raw.get("clients"), path)

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


def _parse_clients(raw_clients, path: str) -> list[ClientSpec]:
    if not isinstance(raw_clients, list) or not raw_clients:
        raise AccessControlSpecError(f"{path}: 'clients' must be a non-empty list")

    clients = []
    for entry in raw_clients:
        clients.append(
            ClientSpec(
                client_id=entry["client_id"],
                client_name=entry.get("client_name", entry["client_id"]),
                grant_types=list(entry.get("grant_types", [])),
                response_types=list(entry.get("response_types", [])),
                token_endpoint_auth_method=entry.get("token_endpoint_auth_method", "none"),
                redirect_uris=list(entry.get("redirect_uris", [])),
            )
        )

    return clients


# --- Hydra OAuth2 client reconciliation ---


def _build_desired_client_payload(client: ClientSpec, spec: AccessControlSpec) -> dict:
    all_scopes = sorted(scope.name for scope in spec.scopes)
    return {
        "client_id": client.client_id,
        "client_name": client.client_name,
        "grant_types": list(client.grant_types),
        "response_types": list(client.response_types),
        "token_endpoint_auth_method": client.token_endpoint_auth_method,
        "redirect_uris": list(client.redirect_uris),
        "scope": " ".join(all_scopes),
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


def reconcile_hydra_clients(hydra_client: HydraClient, spec: AccessControlSpec) -> None:
    for client in spec.clients:
        desired = _build_desired_client_payload(client, spec)
        existing = hydra_client.get_client(client.client_id)

        if existing is None:
            hydra_client.create_client(desired)
            logger.info("created Hydra client %s", client.client_id)
            continue

        if not _is_client_in_sync(existing, desired):
            hydra_client.update_client(client.client_id, desired)
            logger.info("updated Hydra client %s", client.client_id)
            continue

        logger.info("Hydra client %s already in sync, no-op", client.client_id)


# --- Kratos dev admin identity reconciliation ---

# The dev admin's application role, stored in metadata_admin — never exposed
# via public Kratos APIs (e.g. /sessions/whoami), only through Admin API
# calls. This is not a general-purpose role store; it exists solely to seed
# the one bootstrapped dev admin identity.
DEV_ADMIN_ROLE = "ADMIN"


def reconcile_kratos_admin_identity(
    kratos_client: KratosClient, config: Config, schema_id: str = "default"
) -> None:
    existing = kratos_client.find_identity_by_email(config.admin_email)

    if existing is None:
        identity = kratos_client.create_identity(
            schema_id,
            config.admin_email,
            config.admin_password,
            metadata_admin={"role": DEV_ADMIN_ROLE},
        )
        logger.info(
            "created Kratos identity %s (id=%s) with role=%s",
            config.admin_email,
            identity.get("id"),
            DEV_ADMIN_ROLE,
        )
        return

    current_role = (existing.get("metadata_admin") or {}).get("role")
    if current_role == DEV_ADMIN_ROLE:
        logger.info(
            "Kratos identity %s already exists with role=%s, no-op", config.admin_email, DEV_ADMIN_ROLE
        )
        return

    kratos_client.set_identity_metadata_admin(existing["id"], {"role": DEV_ADMIN_ROLE})
    logger.info(
        "updated Kratos identity %s metadata_admin.role -> %s", config.admin_email, DEV_ADMIN_ROLE
    )


def run(config: Config, hydra_client: HydraClient, kratos_client: KratosClient) -> None:
    hydra_client.wait_until_ready(config.retry_attempts, config.retry_delay_seconds)
    kratos_client.wait_until_ready(config.retry_attempts, config.retry_delay_seconds)

    spec = load_and_validate_spec(config.access_control_file)
    reconcile_hydra_clients(hydra_client, spec)
    reconcile_kratos_admin_identity(kratos_client, config)
