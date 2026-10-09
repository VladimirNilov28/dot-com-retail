import os
from dataclasses import dataclass
from urllib.parse import urlparse


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
    spring_internal_base_url: str
    admin_username: str
    admin_email: str
    admin_password: str
    bridge_host: str
    bridge_port: int
    internal_token_host: str
    internal_token_port: int
    dev_client_id: str
    dev_redirect_uri: str
    oauth_service_client_id: str
    oauth_service_client_secret: str
    retry_attempts: int
    retry_delay_seconds: float
    bff_client_id: str = "bytecore-storefront"
    bff_client_secret: str = ""
    bff_redirect_uri: str = "http://127.0.0.1:3000/auth/callback"
    bridge_browser_url: str = "http://127.0.0.1:4446"
    auth_bridge_secret: str = ""
    auth_database_url: str = ""
    captcha_secret: str = ""


def load_config() -> Config:
    admin_username = os.environ.get("ADMIN_USERNAME", "").strip()
    admin_password = os.environ.get("ADMIN_PASSWORD", "").strip()

    oauth_service_client_secret = os.environ.get("OAUTH_SERVICE_CLIENT_SECRET", "").strip()

    hydra_admin_url = os.environ.get("HYDRA_ADMIN_URL", "").strip()
    hydra_public_url = os.environ.get("HYDRA_PUBLIC_URL", "").strip()
    kratos_admin_url = os.environ.get("KRATOS_ADMIN_URL", "").strip()
    kratos_public_url = os.environ.get("KRATOS_PUBLIC_URL", "").strip()
    spring_internal_base_url = os.environ.get("SPRING_INTERNAL_BASE_URL", "").strip()

    if not admin_username:
        raise ConfigError("ADMIN_USERNAME is required")
    if not admin_password:
        raise ConfigError("ADMIN_PASSWORD is required")
    if not oauth_service_client_secret:
        raise ConfigError("OAUTH_SERVICE_CLIENT_SECRET is required")
    if not hydra_admin_url:
        raise ConfigError("HYDRA_ADMIN_URL is required")
    if not hydra_public_url:
        raise ConfigError("HYDRA_PUBLIC_URL is required")
    if not kratos_admin_url:
        raise ConfigError("KRATOS_ADMIN_URL is required")
    if not kratos_public_url:
        raise ConfigError("KRATOS_PUBLIC_URL is required")
    if not spring_internal_base_url:
        raise ConfigError("SPRING_INTERNAL_BASE_URL is required")
    for name in ("BFF_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "CAPTCHA_SECRET"):
        value = os.environ.get(name, "").strip()
        if len(value) < 32 or value.startswith("replace-with"):
            raise ConfigError(f"{name} must be a configured private secret of at least 32 characters")
    if not os.environ.get("AUTH_DATABASE_URL", "").strip():
        raise ConfigError("AUTH_DATABASE_URL is required")
    bff_redirect_uri = os.environ.get("BFF_REDIRECT_URI", "http://127.0.0.1:3000/auth/callback")
    callback = urlparse(bff_redirect_uri)
    if (callback.username or callback.password or callback.query or callback.fragment
            or callback.path != "/auth/callback" or not callback.hostname
            or (callback.scheme != "https" and not (
                callback.scheme == "http" and callback.hostname in ("127.0.0.1", "localhost", "::1")
            ))):
        raise ConfigError("BFF_REDIRECT_URI must be an exact HTTPS callback or loopback development callback")

    return Config(
        hydra_admin_url=hydra_admin_url.rstrip("/"),
        # Token-endpoint/authorization-endpoint calls the dev-token flow drives itself.
        hydra_public_url=hydra_public_url.rstrip("/"),
        kratos_admin_url=kratos_admin_url.rstrip("/"),
        # Server-to-server calls (e.g. session lookups) — reachable over the Docker network.
        kratos_public_url=kratos_public_url.rstrip("/"),
        # Browser-facing redirects — must be reachable from the user's browser.
        kratos_browser_url=os.environ.get("KRATOS_BROWSER_URL", "http://127.0.0.1:4433").rstrip("/"),
        access_control_file=os.environ.get("ACCESS_CONTROL_FILE", "/app/access-control.yml"),
        spring_internal_base_url=spring_internal_base_url.rstrip("/"),
        admin_username=admin_username,
        admin_email=f"{admin_username}@bytecore.ee",
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
        oauth_service_client_id=os.environ.get("OAUTH_SERVICE_CLIENT_ID", "oauth-service-internal"),
        oauth_service_client_secret=oauth_service_client_secret,
        retry_attempts=int(os.environ.get("BOOTSTRAP_RETRY_ATTEMPTS", "10")),
        retry_delay_seconds=float(os.environ.get("BOOTSTRAP_RETRY_DELAY_SECONDS", "2")),
        bff_client_id=os.environ.get("BFF_CLIENT_ID", "bytecore-storefront"),
        bff_client_secret=os.environ.get("BFF_CLIENT_SECRET", "").strip(),
        bff_redirect_uri=bff_redirect_uri,
        bridge_browser_url=os.environ.get("OAUTH_BRIDGE_BROWSER_URL", "http://127.0.0.1:4446").rstrip("/"),
        auth_bridge_secret=os.environ.get("AUTH_BRIDGE_SECRET", "").strip(),
        auth_database_url=os.environ.get("AUTH_DATABASE_URL", "").strip(),
        captcha_secret=os.environ.get("CAPTCHA_SECRET", "").strip(),
    )
