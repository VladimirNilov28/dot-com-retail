import time
import urllib.parse
from typing import Optional

import requests


class KratosNotReadyError(RuntimeError):
    pass


def wait_until_ready(admin_url: str, attempts: int, delay_seconds: float) -> None:
    last_error: Optional[Exception] = None
    for _ in range(attempts):
        try:
            response = requests.get(f"{admin_url}/health/ready", timeout=5)
            if response.ok:
                return
            last_error = RuntimeError(f"unhealthy status {response.status_code}")
        except requests.RequestException as exc:
            last_error = exc
        time.sleep(delay_seconds)

    raise KratosNotReadyError(
        f"Kratos at {admin_url} did not become ready after {attempts} attempts: {last_error}"
    )


# --- Dev admin identity bootstrap ---


def find_identity_by_email(admin_url: str, email: str) -> Optional[dict]:
    response = requests.get(
        f"{admin_url}/admin/identities",
        params={"credentials_identifier": email},
        timeout=10,
    )
    response.raise_for_status()
    identities = response.json()
    return identities[0] if identities else None


def create_identity(
    admin_url: str,
    schema_id: str,
    email: str,
    password: str,
    metadata_admin: Optional[dict] = None,
) -> dict:
    payload = {
        "schema_id": schema_id,
        "traits": {"email": email},
        "credentials": {
            "password": {
                "config": {
                    "password": password,
                },
            },
        },
    }
    if metadata_admin is not None:
        payload["metadata_admin"] = metadata_admin

    response = requests.post(f"{admin_url}/admin/identities", json=payload, timeout=10)
    response.raise_for_status()
    return response.json()


def get_identity(admin_url: str, identity_id: str) -> dict:
    """Fetches the full admin-side identity, including metadata_admin — never
    exposed via the public API (e.g. /sessions/whoami)."""
    response = requests.get(f"{admin_url}/admin/identities/{identity_id}", timeout=10)
    response.raise_for_status()
    return response.json()


def set_identity_metadata_admin(admin_url: str, identity_id: str, metadata_admin: dict) -> dict:
    patch = [{"op": "add", "path": "/metadata_admin", "value": metadata_admin}]
    response = requests.patch(f"{admin_url}/admin/identities/{identity_id}", json=patch, timeout=10)
    response.raise_for_status()
    return response.json()


# --- Login bridge ---


def whoami(public_url: str, cookie_header: Optional[str]) -> Optional[dict]:
    """Returns the active Kratos session for the given browser Cookie header,
    or None if there is no valid session."""
    if not cookie_header:
        return None

    response = requests.get(
        f"{public_url}/sessions/whoami",
        headers={"Cookie": cookie_header},
        timeout=10,
    )
    if response.status_code == 200:
        return response.json()
    return None


def browser_login_url(browser_url: str, return_to: str) -> str:
    query = urllib.parse.urlencode({"return_to": return_to})
    return f"{browser_url}/self-service/login/browser?{query}"
