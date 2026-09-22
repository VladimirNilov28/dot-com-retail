import time
from typing import Optional

import requests


class HydraNotReadyError(RuntimeError):
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

    raise HydraNotReadyError(
        f"Hydra at {admin_url} did not become ready after {attempts} attempts: {last_error}"
    )


# --- OAuth2 client reconciliation ---


def get_client(admin_url: str, client_id: str) -> Optional[dict]:
    response = requests.get(f"{admin_url}/admin/clients/{client_id}", timeout=10)
    if response.status_code == 404:
        return None
    response.raise_for_status()
    return response.json()


def create_client(admin_url: str, payload: dict) -> dict:
    response = requests.post(f"{admin_url}/admin/clients", json=payload, timeout=10)
    response.raise_for_status()
    return response.json()


def update_client(admin_url: str, client_id: str, payload: dict) -> dict:
    response = requests.put(f"{admin_url}/admin/clients/{client_id}", json=payload, timeout=10)
    response.raise_for_status()
    return response.json()


# --- Login/consent challenge (bridge) ---


def get_login_request(admin_url: str, login_challenge: str) -> dict:
    response = requests.get(
        f"{admin_url}/admin/oauth2/auth/requests/login",
        params={"login_challenge": login_challenge},
        timeout=10,
    )
    response.raise_for_status()
    return response.json()


def accept_login_request(
    admin_url: str, login_challenge: str, subject: str, context: Optional[dict] = None
) -> dict:
    payload = {"subject": subject, "remember": True, "remember_for": 3600}
    if context is not None:
        payload["context"] = context

    response = requests.put(
        f"{admin_url}/admin/oauth2/auth/requests/login/accept",
        params={"login_challenge": login_challenge},
        json=payload,
        timeout=10,
    )
    response.raise_for_status()
    return response.json()


def get_consent_request(admin_url: str, consent_challenge: str) -> dict:
    response = requests.get(
        f"{admin_url}/admin/oauth2/auth/requests/consent",
        params={"consent_challenge": consent_challenge},
        timeout=10,
    )
    response.raise_for_status()
    return response.json()


def accept_consent_request(
    admin_url: str,
    consent_challenge: str,
    grant_scope: list,
    grant_access_token_audience: list,
    access_token_claims: Optional[dict] = None,
) -> dict:
    payload = {
        "grant_scope": grant_scope,
        "grant_access_token_audience": grant_access_token_audience,
        "remember": True,
        "remember_for": 3600,
    }
    if access_token_claims is not None:
        payload["session"] = {"access_token": access_token_claims}

    response = requests.put(
        f"{admin_url}/admin/oauth2/auth/requests/consent/accept",
        params={"consent_challenge": consent_challenge},
        json=payload,
        timeout=10,
    )
    response.raise_for_status()
    return response.json()
