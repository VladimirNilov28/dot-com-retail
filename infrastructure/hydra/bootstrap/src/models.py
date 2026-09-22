import re
from dataclasses import dataclass
from typing import Union

import yaml

SCOPE_NAME_PATTERN = re.compile(r"^[a-z][a-z-]*:[a-z][a-z-]*$")

ADMIN_WILDCARD = "*"


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


def load_and_validate(path: str) -> AccessControlSpec:
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
