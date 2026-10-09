"""Generate an isolated authentication fixture; never read the owner's .env."""
import json
import os
from pathlib import Path
import secrets
import sys

import yaml


def generate(destination):
    root = Path(__file__).resolve().parents[5]
    infrastructure = root / "infrastructure"
    destination = Path(destination).resolve()
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Refusing to replace existing fixture credentials; choose a fresh directory.")
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(destination, 0o700)
    compose = yaml.safe_load((infrastructure / "compose.yml").read_text())
    for name in ["payment-service"]:
        del compose["services"][name]
    compose.pop("networks", None)
    compose["volumes"].pop("kafka_volume", None)
    for service in compose["services"].values():
        service.pop("container_name", None)
        service.pop("ports", None)
        service["restart"] = "no"
        if "build" in service:
            service["build"]["context"] = str((infrastructure / service["build"]["context"]).resolve())
        for index, mount in enumerate(service.get("volumes", [])):
            if mount.startswith("./"):
                source, target = mount.split(":", 1)
                service["volumes"][index] = f"{(infrastructure / source).resolve()}:{target}"
    services = compose["services"]
    services["postgres"]["ports"] = ["127.0.0.1:25432:5432"]
    services["hydra"]["ports"] = ["127.0.0.1:24445:4445"]
    services["hydra-public"]["ports"] = ["127.0.0.1:24444:4444"]
    services["hydra"]["environment"].update({
        "URLS_SELF_ISSUER": "http://127.0.0.1:24444",
        "URLS_LOGIN": "http://127.0.0.1:24446/login",
        "URLS_CONSENT": "http://127.0.0.1:24446/consent",
        "URLS_LOGOUT": "http://127.0.0.1:3300/account",
        "TTL_ACCESS_TOKEN": "45s",
    })
    services["kratos"]["ports"] = ["127.0.0.1:24433:4433", "127.0.0.1:24434:4434"]
    services["kratos"]["environment"].update({
        "SERVE_PUBLIC_BASE_URL": "http://127.0.0.1:24433/",
        "SERVE_ADMIN_BASE_URL": "http://127.0.0.1:24434/",
        "SELFSERVICE_DEFAULT_BROWSER_RETURN_URL": "http://127.0.0.1:3300/",
        "SELFSERVICE_ALLOWED_RETURN_URLS": '["http://127.0.0.1:3300/","http://127.0.0.1:24446/"]',
        "SELFSERVICE_FLOWS_LOGIN_UI_URL": "http://127.0.0.1:3300/login",
        "SELFSERVICE_FLOWS_REGISTRATION_UI_URL": "http://127.0.0.1:3300/register",
        "SELFSERVICE_FLOWS_VERIFICATION_UI_URL": "http://127.0.0.1:3300/verify",
        "COURIER_SMTP_FROM_ADDRESS": "no-reply@bytecore.example",
    })
    services["mailpit"]["ports"] = ["127.0.0.1:28025:8025"]
    services["oauth-service"]["ports"] = ["127.0.0.1:24446:4446", "127.0.0.1:24447:4447"]
    backend = services["backend"]
    backend.pop("build", None)
    backend["image"] = "eclipse-temurin:21-jre-alpine"
    backend["command"] = ["java", "-jar", "/app/backend.jar"]
    backend["volumes"] = [f"{root}/backend/build/libs/bytecore-backend.jar:/app/backend.jar:ro"]
    backend["ports"] = ["127.0.0.1:28080:8080"]
    backend["environment"]["SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI"] = "http://127.0.0.1:24444"
    services["kafka"].pop("volumes", None)
    services["hive-router"]["ports"] = ["127.0.0.1:24002:4000"]
    password = lambda: secrets.token_urlsafe(40)
    environment = {
        "DB_USERNAME": "auth_fixture", "DB_PASSWORD": password(),
        "HYDRA_DB_USER": "hydra_fixture", "HYDRA_DB_PASSWORD": password(),
        "KRATOS_DB_USER": "kratos_fixture", "KRATOS_DB_PASSWORD": password(),
        "PAYMENT_DB_USER": "payment_fixture", "PAYMENT_DB_PASSWORD": password(),
        "AUTH_MIGRATION_USER": "auth_migration", "AUTH_MIGRATION_PASSWORD": password(),
        "AUTH_DB_USER": "auth_bff", "AUTH_DB_PASSWORD": password(),
        "CAPTCHA_DB_USER": "auth_captcha", "CAPTCHA_DB_PASSWORD": password(),
        "HYDRA_SYSTEM_SECRET": password(), "KRATOS_SECRETS_COOKIE": password(),
        "KRATOS_SECRETS_CIPHER": secrets.token_hex(16),
        "ADMIN_USERNAME": "authfixtureadmin", "ADMIN_PASSWORD": password(),
        "OAUTH_SERVICE_CLIENT_ID": "oauth-service-internal", "OAUTH_SERVICE_CLIENT_SECRET": password(),
        "BFF_CLIENT_ID": "bytecore-storefront", "BFF_CLIENT_SECRET": password(),
        "BFF_REDIRECT_URI": "http://127.0.0.1:3300/auth/callback",
        "AUTH_BRIDGE_SECRET": password(), "CAPTCHA_SECRET": password(),
        "HYDRA_ADMIN_URL": "http://hydra:4445", "HYDRA_PUBLIC_URL": "http://hydra:4444",
        "KRATOS_ADMIN_URL": "http://kratos:4434", "KRATOS_PUBLIC_URL": "http://kratos:4433",
        "KRATOS_BROWSER_URL": "http://127.0.0.1:24433",
        "OAUTH_BRIDGE_BROWSER_URL": "http://127.0.0.1:24446",
        "KAFKA_CLUSTER_ID": secrets.token_urlsafe(16),
    }
    for provider in ["HYDRA", "KRATOS"]:
        environment[f"{provider}_DSN"] = (
            f"postgres://{environment[f'{provider}_DB_USER']}:{environment[f'{provider}_DB_PASSWORD']}"
            f"@postgres:5432/{provider.lower()}?sslmode=disable"
        )
    environment["CAPTCHA_DATABASE_URL"] = (
        f"postgres://auth_captcha:{environment['CAPTCHA_DB_PASSWORD']}@postgres:5432/storefront_auth?sslmode=disable"
    )
    frontend = {
        "STOREFRONT_ORIGIN": "http://127.0.0.1:3300", "HIVE_GRAPHQL_URL": "http://127.0.0.1:24002/graphql",
        "AUTH_DATABASE_URL": f"postgres://auth_bff:{environment['AUTH_DB_PASSWORD']}@127.0.0.1:25432/storefront_auth?sslmode=disable",
        "AUTH_OAUTH_ISSUER": "http://127.0.0.1:24444", "AUTH_KRATOS_URL": "http://127.0.0.1:24433",
        "AUTH_BRIDGE_URL": "http://127.0.0.1:24446", "AUTH_CLIENT_ID": environment["BFF_CLIENT_ID"],
        "AUTH_CLIENT_SECRET": environment["BFF_CLIENT_SECRET"], "AUTH_BRIDGE_SECRET": environment["AUTH_BRIDGE_SECRET"],
        "AUTH_SEAL_KEYS": json.dumps({"active": "fixture", "keys": {"fixture": secrets.token_urlsafe(32)}}),
    }
    for filename, content in [
        ("compose.yml", yaml.safe_dump(compose, sort_keys=False)),
        ("compose.env", "".join(f"{key}={value}\n" for key, value in environment.items())),
        ("frontend.env.json", json.dumps(frontend)),
    ]:
        path = destination / filename
        path.write_text(content)
        os.chmod(path, 0o600)
    print("Isolated authentication configuration generated (private values not printed).")


if __name__ == "__main__":
    generate(sys.argv[1])
