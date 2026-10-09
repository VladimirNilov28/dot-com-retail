import time
from datetime import date
from typing import Callable, Optional

import requests


class SpringNotReadyError(RuntimeError):
    pass


class SpringAuthClient:
    """Thin HTTP client for the Spring backend's internal auth REST API.
    Token acquisition (Hydra client-credentials) is injected as a callable so
    this class stays a pure transport concern."""

    def __init__(self, base_url: str, token_provider: Callable[[], str]):
        self._base_url = base_url.rstrip("/")
        self._token_provider = token_provider

    def wait_until_ready(self, attempts: int, delay_seconds: float) -> None:
        """Unlike Hydra/Kratos's wait_until_ready, this doesn't check a
        dedicated health endpoint — any HTTP response at all (even 401/403,
        since this is called before we necessarily have a token) proves
        Spring is reachable. Only connection-level failures (refused,
        timed out) count as "not ready"."""
        last_error: Optional[Exception] = None
        for _ in range(attempts):
            try:
                requests.get(f"{self._base_url}/internal/users/0", timeout=5)
                return
            except requests.RequestException as exc:
                last_error = exc
            time.sleep(delay_seconds)

        raise SpringNotReadyError(
            f"Spring at {self._base_url} did not become reachable after {attempts} attempts: {last_error}"
        )

    def provision_user(self, username: str, email: str, date_of_birth: date, role: str) -> dict:
        token = self._token_provider()
        response = requests.post(
            f"{self._base_url}/internal/users",
            json={
                "username": username,
                "email": email,
                "dateOfBirth": date_of_birth.isoformat(),
                "role": role,
            },
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    def resolve_user(self, spring_user_id: int) -> dict:
        token = self._token_provider()
        response = requests.get(
            f"{self._base_url}/internal/users/{spring_user_id}",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    def provision_bootstrap_admin(self, username: str, email: str, date_of_birth: date) -> dict:
        """Create only: the server must reject existing usernames/emails.
        A distinct route fails closed against older, upsert-only backends."""
        token = self._token_provider()
        response = requests.post(
            f"{self._base_url}/internal/users/bootstrap-admin",
            json={"username": username, "email": email, "dateOfBirth": date_of_birth.isoformat()},
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    def reserve_registration(self, flow_id: str, traits: dict, expires_at: str) -> dict:
        return self._registration_request("registration-reservations", {
            "flowId": flow_id, "username": traits["username"], "email": traits["email"],
            "dateOfBirth": traits["dateOfBirth"], "expiresAt": expires_at,
        })

    def bind_registration(self, identity_id: str) -> dict:
        return self._registration_request("registration-bind", {"identityId": identity_id})

    def finalize_registration(self, identity_id: str) -> dict:
        return self._registration_request("registration-finalize", {"identityId": identity_id})

    def maintain_registration(self) -> dict:
        return self._registration_request("registration-maintenance", {})

    def _registration_request(self, path: str, body: dict) -> dict:
        response = requests.post(
            f"{self._base_url}/internal/users/{path}", json=body,
            headers={"Authorization": f"Bearer {self._token_provider()}"}, timeout=10,
        )
        response.raise_for_status()
        return response.json()
