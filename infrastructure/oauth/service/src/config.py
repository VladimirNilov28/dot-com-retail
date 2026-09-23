import os
from dataclasses import dataclass


class ConfigError(ValueError):
    """Raised when required configuration is missing."""


@dataclass(frozen=True)
class Config:
    hydra_admin_url: str
    hydra_public_url: str
    kratos_admin_url: str
    kratos_public_url: str
    kratos_browser_url: str
    access_control_file: str
    admin_email: str
    admin_password: str
    bridge_host: str
    bridge_port: int
    internal_token_host: str
    internal_token_port: int
    dev_client_id: str
    dev_redirect_uri: str
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
        # Token-endpoint/authorization-endpoint calls the dev-token flow drives itself.
        hydra_public_url=os.environ.get("HYDRA_PUBLIC_URL", "http://hydra:4444").rstrip("/"),
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
        # Loopback-only dev endpoint (/internal/token). Must stay bound to 0.0.0.0
        # *inside* the container — Docker's port-publish forwards to the container's
        # non-loopback interface, so loopback-only enforcement happens at the
        # compose port-publish (127.0.0.1:port:port), not this bind address.
        internal_token_host=os.environ.get("OAUTH_INTERNAL_HOST", "0.0.0.0"),
        internal_token_port=int(os.environ.get("OAUTH_INTERNAL_PORT", "4447")),
        dev_client_id=os.environ.get("OAUTH_DEV_CLIENT_ID", "bytecore-web"),
        dev_redirect_uri=os.environ.get("OAUTH_DEV_REDIRECT_URI", "http://localhost:4200/auth/callback"),
        retry_attempts=int(os.environ.get("BOOTSTRAP_RETRY_ATTEMPTS", "10")),
        retry_delay_seconds=float(os.environ.get("BOOTSTRAP_RETRY_DELAY_SECONDS", "2")),
    )
