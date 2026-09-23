import time
import urllib.parse
from typing import Optional

import requests


class KratosNotReadyError(RuntimeError):
    pass


class KratosAuthenticationError(RuntimeError):
    """Raised when Kratos itself rejects the submitted email/password. This is
    Kratos reporting invalid credentials, not this service checking them."""


class KratosClient:
    def __init__(self, admin_url: str, public_url: str):
        self._admin_url = admin_url
        self._public_url = public_url

    def wait_until_ready(self, attempts: int, delay_seconds: float) -> None:
        last_error: Optional[Exception] = None
        for _ in range(attempts):
            try:
                response = requests.get(f"{self._admin_url}/health/ready", timeout=5)
                if response.ok:
                    return
                last_error = RuntimeError(f"unhealthy status {response.status_code}")
            except requests.RequestException as exc:
                last_error = exc
            time.sleep(delay_seconds)

        raise KratosNotReadyError(
            f"Kratos at {self._admin_url} did not become ready after {attempts} attempts: {last_error}"
        )

    # --- Dev admin identity bootstrap ---

    def find_identity_by_email(self, email: str) -> Optional[dict]:
        response = requests.get(
            f"{self._admin_url}/admin/identities",
            params={"credentials_identifier": email},
            timeout=10,
        )
        response.raise_for_status()
        identities = response.json()
        return identities[0] if identities else None

    def create_identity(
        self,
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

        response = requests.post(f"{self._admin_url}/admin/identities", json=payload, timeout=10)
        response.raise_for_status()
        return response.json()

    def get_identity(self, identity_id: str) -> dict:
        """Fetches the full admin-side identity, including metadata_admin — never
        exposed via the public API (e.g. /sessions/whoami)."""
        response = requests.get(f"{self._admin_url}/admin/identities/{identity_id}", timeout=10)
        response.raise_for_status()
        return response.json()

    def set_identity_metadata_admin(self, identity_id: str, metadata_admin: dict) -> dict:
        patch = [{"op": "add", "path": "/metadata_admin", "value": metadata_admin}]
        response = requests.patch(
            f"{self._admin_url}/admin/identities/{identity_id}", json=patch, timeout=10
        )
        response.raise_for_status()
        return response.json()

    # --- Login bridge (browser-driven) ---

    def whoami(self, cookie_header: Optional[str]) -> Optional[dict]:
        """Returns the active Kratos session for the given browser Cookie header,
        or None if there is no valid session."""
        if not cookie_header:
            return None

        response = requests.get(
            f"{self._public_url}/sessions/whoami",
            headers={"Cookie": cookie_header},
            timeout=10,
        )
        if response.status_code == 200:
            return response.json()
        return None

    def browser_login_url(self, browser_url: str, return_to: str) -> str:
        query = urllib.parse.urlencode({"return_to": return_to})
        return f"{browser_url}/self-service/login/browser?{query}"

    # --- Headless password login (dev token issuance) ---

    def authenticate_with_password(self, email: str, password: str) -> dict:
        """Drives Kratos's own native (non-browser) self-service login flow.
        Kratos performs the actual password check; this only relays the flow.
        Returns the Kratos session dict (with session["identity"]["id"] as the
        Hydra login `subject`). Raises KratosAuthenticationError on invalid
        credentials, as reported by Kratos."""
        init_response = requests.get(
            f"{self._public_url}/self-service/login/api",
            headers={"Accept": "application/json"},
            timeout=10,
        )
        init_response.raise_for_status()
        action_url = init_response.json()["ui"]["action"]
        # Kratos builds `action` from its own configured public base_url,
        # which is browser-facing (127.0.0.1) and unreachable from inside
        # this container — reissue it against the docker-network-reachable
        # public_url this client was actually built with, path+query only.
        action_path_and_query = action_url[len(self._origin(action_url)):]

        submit_response = requests.post(
            f"{self._public_url}{action_path_and_query}",
            json={"method": "password", "identifier": email, "password": password},
            headers={"Accept": "application/json"},
            timeout=10,
        )
        if submit_response.status_code >= 400:
            raise KratosAuthenticationError("Kratos rejected the submitted email/password")

        return submit_response.json()["session"]

    @staticmethod
    def _origin(url: str) -> str:
        parsed = urllib.parse.urlparse(url)
        return f"{parsed.scheme}://{parsed.netloc}"
