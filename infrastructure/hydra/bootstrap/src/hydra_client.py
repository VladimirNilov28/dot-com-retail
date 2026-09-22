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
