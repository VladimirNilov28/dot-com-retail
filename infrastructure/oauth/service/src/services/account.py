"""Customer account-management operations behind the private BFF bridge.

Every function takes an already-resolved, active USER bff_session. Responses
are projections: credential hashes, backup codes, provider subjects and TOTP
URLs never leave this module."""
import logging
import re
import unicodedata
from datetime import datetime, timezone

import requests

from clients.kratos import KratosClient
from clients.mailer import MailDeliveryError, SmtpMailer
from clients.spring_auth import SpringAuthClient
from services.login_consent import BridgeError

logger = logging.getLogger(__name__)

_CODE = re.compile(r"^[0-9]{6}$")
_EMAIL = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")
_UNUSED_TIME = "0001-01-01T00:00:00Z"

SECURITY_EVENTS = {
    "password_set": "A password was added to your ByteCore account.",
    "password_changed": "Your ByteCore account password was changed.",
    "totp_enabled": "An authenticator app was enabled for two-step verification on your ByteCore account.",
    "totp_disabled": "The authenticator app was removed from your ByteCore account.",
    "recovery_codes_regenerated": "New recovery codes were generated for your ByteCore account. "
                                  "Previous recovery codes no longer work.",
    "recovery_codes_disabled": "Recovery codes were turned off for your ByteCore account.",
    "google_connected": "A Google account was connected to your ByteCore account for sign-in.",
    "google_disconnected": "A Google account was disconnected from your ByteCore account.",
    "sessions_revoked": "Other signed-in sessions of your ByteCore account were signed out.",
    "mfa_recovery_used": "A recovery code was used to sign in to your ByteCore account.",
}


class AccountConflict(Exception):
    """The requested email is held by another account or registration."""


def _require_keys(body: dict, required: set, optional: frozenset = frozenset()) -> None:
    keys = set(body)
    if not required <= keys or keys - required - optional:
        raise ValueError("Invalid account request")


def require_cookie(body: dict) -> str:
    cookie = body.get("cookie")
    if not isinstance(cookie, str) or not cookie or len(cookie) > 16384:
        raise ValueError("Invalid account request")
    return cookie


def validate_email(value) -> str:
    if not isinstance(value, str):
        raise ValueError("Invalid email")
    email = value.strip()
    if (not 3 <= len(email) <= 254 or email.count("@") != 1 or not _EMAIL.match(email)
            or any(character.isspace() or unicodedata.category(character).startswith("C") for character in email)):
        raise ValueError("Invalid email")
    return email


def _code(value) -> str:
    if not isinstance(value, str) or not _CODE.match(value):
        raise ValueError("Invalid code")
    return value


def mask_email(email: str) -> str:
    local, _, domain = email.partition("@")
    labels = domain.split(".")
    return f"{local[:1]}***@{labels[0][:1]}***.{labels[-1]}"


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


# --- status ---

def _google_connected(credentials: dict) -> bool:
    oidc = credentials.get("oidc")
    if oidc is None:
        return False
    config = oidc.get("config") if isinstance(oidc, dict) else None
    providers = config.get("providers") if isinstance(config, dict) else None
    if isinstance(providers, list):
        # Kratos leaves a blank placeholder entry after the last provider is unlinked.
        providers = [provider for provider in providers if not (
            isinstance(provider, dict) and provider.get("provider") == "" and provider.get("subject") == "")]
    if not isinstance(providers, list) or any(
            not isinstance(provider, dict) or not isinstance(provider.get("provider"), str)
            or not provider["provider"] or not isinstance(provider.get("subject"), str)
            or not provider["subject"] for provider in providers):
        raise BridgeError("Invalid Google credential status")
    return any(provider["provider"] == "google" for provider in providers)


def _credential_config(credentials: dict, kind: str):
    credential = credentials.get(kind)
    if credential is None:
        return None
    if not isinstance(credential, dict):
        raise BridgeError("Invalid credential status")
    config = credential.get("config")
    if config is None:
        config = {}
    if not isinstance(config, dict):
        raise BridgeError("Invalid credential status")
    return config


def credential_projection(identity: dict) -> tuple:
    credentials = identity.get("credentials")
    if credentials is None:
        credentials = {}
    if not isinstance(credentials, dict):
        raise BridgeError("Invalid credential status")

    # Kratos keeps an identifier-only password credential (no hash) for
    # identities without a password, e.g. Google-only customers.
    password = _credential_config(credentials, "password")
    has_password = password is not None and isinstance(password.get("hashed_password"), str) \
        and bool(password["hashed_password"])

    totp = _credential_config(credentials, "totp")
    has_totp = totp is not None and isinstance(totp.get("totp_url"), str) and bool(totp["totp_url"])

    lookup = _credential_config(credentials, "lookup_secret")
    codes = [] if lookup is None else lookup.get("recovery_codes") or []
    if not isinstance(codes, list) or any(
            not isinstance(code, dict) or not isinstance(code.get("code"), str)
            or not (code.get("used_at") is None or isinstance(code.get("used_at"), str)) for code in codes):
        raise BridgeError("Invalid credential status")
    remaining = sum(1 for code in codes if not code.get("used_at") or code["used_at"] == _UNUSED_TIME)

    return {"password": has_password, "google": _google_connected(credentials), "totp": has_totp,
            "lookup_secret": remaining > 0}, remaining


def _traits(identity: dict) -> dict:
    traits = identity.get("traits")
    if (not isinstance(traits, dict) or not isinstance(traits.get("email"), str) or not traits["email"]
            or not isinstance(traits.get("username"), str)
            or not (traits.get("dateOfBirth") is None or isinstance(traits.get("dateOfBirth"), str))):
        raise BridgeError("Invalid identity traits")
    return traits


def _email_verified(identity: dict, email: str) -> bool:
    addresses = identity.get("verifiable_addresses") or []
    if not isinstance(addresses, list):
        raise BridgeError("Invalid identity addresses")
    return any(isinstance(address, dict) and address.get("via") == "email" and address.get("verified") is True
               and str(address.get("value", "")).casefold() == email.casefold() for address in addresses)


def account_status(kratos_client: KratosClient, session: dict) -> dict:
    identity = kratos_client.get_identity_with_credentials(session["identity_id"])
    credentials, remaining = credential_projection(identity)
    traits = _traits(identity)
    return {
        "session": session,
        "credentials": credentials,
        "recovery_codes_remaining": remaining,
        "profile": {"email": traits["email"], "email_verified": _email_verified(identity, traits["email"]),
                    "username": traits["username"], "date_of_birth": traits.get("dateOfBirth")},
    }


# --- notifications ---

def validate_notification(body: dict) -> dict:
    """Validates before any upstream call; returns the template parameters."""
    template = body.get("template")
    base = {"cookie", "template"}
    if template == "step_up_code":
        _require_keys(body, base | {"code"})
        return {"template": template, "code": _code(body["code"])}
    if template == "email_change_code":
        _require_keys(body, base | {"code", "new_email"})
        return {"template": template, "code": _code(body["code"]), "new_email": validate_email(body["new_email"])}
    if template == "security_event":
        event = body.get("event")
        if event not in SECURITY_EVENTS:
            raise ValueError("Invalid event")
        if event == "mfa_recovery_used":
            _require_keys(body, base | {"event"}, frozenset({"remaining"}))
        else:
            _require_keys(body, base | {"event"})
        remaining = body.get("remaining")
        if remaining is not None and (isinstance(remaining, bool) or not isinstance(remaining, int)
                                      or not 0 <= remaining <= 64):
            raise ValueError("Invalid remaining count")
        return {"template": template, "event": event, "remaining": remaining}
    raise ValueError("Invalid template")


def _render(notification: dict) -> tuple:
    template = notification["template"]
    if template == "step_up_code":
        return "Your ByteCore security code", (
            f"Your ByteCore security code is {notification['code']}.\n\n"
            "It expires in 10 minutes and confirms a security change to your account.\n\n"
            "If you didn't request this, ignore this email and review your account security.\n")
    if template == "email_change_code":
        return "Confirm your new ByteCore email", (
            f"Your ByteCore confirmation code is {notification['code']}.\n\n"
            "Enter it to confirm this as the new sign-in email for your ByteCore account. "
            "It expires in 15 minutes.\n\n"
            "If you didn't request this, ignore this email.\n")
    if template == "email_changed":
        return "Your ByteCore sign-in email was changed", (
            f"Your ByteCore sign-in email was changed to {mask_email(notification['new_email'])} "
            f"at {_utc_now()}.\n\n"
            "If this wasn't you, recover your account immediately.\n")
    sentence = SECURITY_EVENTS[notification["event"]]
    if notification["event"] == "mfa_recovery_used" and notification.get("remaining") is not None:
        sentence += f" You have {notification['remaining']} unused recovery codes left."
    return "ByteCore security alert", (
        f"{sentence}\n\nTime: {_utc_now()}\n\n"
        "If this wasn't you, change your password and review your account security right away.\n")


def _current_email(kratos_client: KratosClient, identity_id: str) -> str:
    identity = kratos_client.get_identity(identity_id)
    if not isinstance(identity, dict) or identity.get("id") != identity_id:
        raise BridgeError("Invalid identity response")
    return _traits(identity)["email"]


def _send(mailer: SmtpMailer, recipient: str, notification: dict) -> None:
    subject, text = _render(notification)
    try:
        mailer.send(recipient, subject, text)
    except MailDeliveryError as error:
        logger.error("account notification failed (%s): %s", notification["template"], error)
        raise BridgeError("Notification delivery failed") from None


def notify(kratos_client: KratosClient, mailer: SmtpMailer, session: dict, notification: dict) -> None:
    if notification["template"] == "email_change_code":
        recipient = notification["new_email"]
    else:
        recipient = _current_email(kratos_client, session["identity_id"])
    _send(mailer, recipient, notification)


# --- email change ---

def change_email(kratos_client: KratosClient, spring_auth_client: SpringAuthClient, mailer: SmtpMailer,
                 session: dict, email: str) -> dict:
    identity_id = session["identity_id"]
    old_email = _current_email(kratos_client, identity_id)
    if old_email.casefold() == email.casefold():
        raise ValueError("Email is unchanged")
    availability = spring_auth_client.registration_availability(email)
    if not isinstance(availability, dict) or not isinstance(availability.get("emailAvailable"), bool):
        raise BridgeError("Invalid availability response")
    if not availability["emailAvailable"]:
        raise AccountConflict()
    if any(other != identity_id for other in kratos_client.identity_ids_by_identifier(email)):
        raise AccountConflict()

    try:
        spring_auth_client.change_email(identity_id, email)
    except requests.HTTPError as error:
        if error.response is not None and error.response.status_code == 409:
            raise AccountConflict() from None
        raise
    try:
        kratos_client.update_identity_email(identity_id, email)
    except Exception as error:  # noqa: BLE001 - compensate, then fail closed
        logger.error("email change identity update failed (%s)", type(error).__name__)
        _compensate(kratos_client, spring_auth_client, identity_id, old_email)
        raise BridgeError("Email change failed") from None

    try:
        _send(mailer, old_email, {"template": "email_changed", "new_email": email})
    except BridgeError:
        logger.error("email change completed without old-address notification")
    return {"email": email}


def _compensate(kratos_client, spring_auth_client, identity_id: str, old_email: str) -> None:
    # Idempotent: restores and re-verifies the old address only if the
    # identity was already changed.
    try:
        kratos_client.update_identity_email(identity_id, old_email)
    except Exception as error:  # noqa: BLE001
        logger.error("email change identity compensation failed (%s)", type(error).__name__)
    try:
        spring_auth_client.change_email(identity_id, old_email)
    except Exception as error:  # noqa: BLE001
        logger.error("email change canonical compensation failed (%s)", type(error).__name__)
