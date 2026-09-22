import os
from dataclasses import dataclass


class ConfigError(ValueError):
    """Raised when required configuration is missing."""


@dataclass(frozen=True)
class Config:
    hydra_admin_url: str
    kratos_admin_url: str
    kratos_public_url: str
    kratos_browser_url: str
    access_control_file: str
    admin_email: str
    admin_password: str
    bridge_host: str
    bridge_port: int
    retry_attempts: int
    retry_delay_seconds: float


def load_config() -> Config:
    admin_email = os.environ.get("ADMIN_EMAIL", "").strip()
    admin_password = os.environ.get("ADMIN_PASSWORD", "").strip()

    if not admin_email:
        raise ConfigError("ADMIN_EMAIL is required")
    if not admin_password:
        raise ConfigError("ADMIN_PASSWORD is required")

    return Config(
        hydra_admin_url=os.environ.get("HYDRA_ADMIN_URL", "http://hydra:4445").rstrip("/"),
        kratos_admin_url=os.environ.get("KRATOS_ADMIN_URL", "http://kratos:4434").rstrip("/"),
        # Server-to-server calls (e.g. session lookups) — reachable over the Docker network.
        kratos_public_url=os.environ.get("KRATOS_PUBLIC_URL", "http://kratos:4433").rstrip("/"),
        # Browser-facing redirects — must be reachable from the user's browser.
        kratos_browser_url=os.environ.get("KRATOS_BROWSER_URL", "http://127.0.0.1:4433").rstrip("/"),
        access_control_file=os.environ.get("ACCESS_CONTROL_FILE", "/app/access-control.yml"),
        admin_email=admin_email,
        admin_password=admin_password,
        bridge_host=os.environ.get("OAUTH_SERVICE_HOST", "0.0.0.0"),
        bridge_port=int(os.environ.get("OAUTH_SERVICE_PORT", "4446")),
        retry_attempts=int(os.environ.get("BOOTSTRAP_RETRY_ATTEMPTS", "10")),
        retry_delay_seconds=float(os.environ.get("BOOTSTRAP_RETRY_DELAY_SECONDS", "2")),
    )
