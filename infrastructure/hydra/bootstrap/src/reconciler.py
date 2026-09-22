import logging

from hydra_client import create_client, get_client, update_client
from models import AccessControlSpec, ClientSpec

logger = logging.getLogger(__name__)

# Fields the bootstrap owns and reconciles. Any other field Hydra returns on
# an existing client (timestamps, internal metadata, etc.) is ignored so
# re-runs don't spuriously report a diff.
_MANAGED_FIELDS = (
    "client_name",
    "grant_types",
    "response_types",
    "token_endpoint_auth_method",
    "redirect_uris",
    "scope",
)


def build_desired_client_payload(client: ClientSpec, spec: AccessControlSpec) -> dict:
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


def is_client_in_sync(existing: dict, desired: dict) -> bool:
    for field in _MANAGED_FIELDS:
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


def reconcile_client(admin_url: str, client: ClientSpec, spec: AccessControlSpec) -> None:
    desired = build_desired_client_payload(client, spec)
    existing = get_client(admin_url, client.client_id)

    if existing is None:
        create_client(admin_url, desired)
        logger.info("created client %s", client.client_id)
        return

    if not is_client_in_sync(existing, desired):
        update_client(admin_url, client.client_id, desired)
        logger.info("updated client %s", client.client_id)
        return

    logger.info("client %s already in sync, no-op", client.client_id)


def reconcile(admin_url: str, spec: AccessControlSpec) -> None:
    for client in spec.clients:
        reconcile_client(admin_url, client, spec)
