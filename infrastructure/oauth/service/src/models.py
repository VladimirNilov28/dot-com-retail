import json
from dataclasses import asdict, dataclass
from typing import Optional


class InvalidRequestError(ValueError):
    """Raised when an incoming request body is missing required fields."""


@dataclass(frozen=True)
class TokenRequest:
    email: str
    password: str

    @staticmethod
    def from_json_bytes(body: bytes) -> "TokenRequest":
        try:
            raw = json.loads(body or b"{}")
        except json.JSONDecodeError as exc:
            raise InvalidRequestError(f"invalid JSON body: {exc}") from exc

        if not isinstance(raw, dict):
            raise InvalidRequestError("expected a JSON object")

        email = raw.get("email")
        password = raw.get("password")
        if not isinstance(email, str) or not email.strip():
            raise InvalidRequestError("'email' is required")
        if not isinstance(password, str) or not password:
            raise InvalidRequestError("'password' is required")

        return TokenRequest(email=email.strip(), password=password)


@dataclass(frozen=True)
class LogoutRequest:
    refresh_token: str
    kratos_session_token: Optional[str] = None

    @staticmethod
    def from_json_bytes(body: bytes) -> "LogoutRequest":
        try:
            raw = json.loads(body or b"{}")
        except json.JSONDecodeError as exc:
            raise InvalidRequestError(f"invalid JSON body: {exc}") from exc

        if not isinstance(raw, dict):
            raise InvalidRequestError("expected a JSON object")

        refresh_token = raw.get("refresh_token")
        if not isinstance(refresh_token, str) or not refresh_token.strip():
            raise InvalidRequestError("'refresh_token' is required")

        kratos_session_token = raw.get("kratos_session_token")
        if kratos_session_token is not None and not isinstance(kratos_session_token, str):
            raise InvalidRequestError("'kratos_session_token' must be a string")

        return LogoutRequest(refresh_token=refresh_token.strip(), kratos_session_token=kratos_session_token)


@dataclass(frozen=True)
class TokenResponse:
    access_token: str
    token_type: str
    expires_in: Optional[int] = None
    scope: Optional[str] = None
    # Present only when Hydra actually issued one (requires the client to
    # have requested/been granted the "offline_access" scope). Frontend/
    # client storage of this value is out of scope here — this endpoint is
    # a dev-only convenience, not a public API.
    refresh_token: Optional[str] = None
    # A Kratos native-login session token for this same authentication,
    # needed to later call POST /logout (Kratos session revocation). Not a
    # Hydra/OAuth2 concept — kept separate from access_token/refresh_token.
    kratos_session_token: Optional[str] = None

    def to_json_bytes(self) -> bytes:
        return json.dumps({k: v for k, v in asdict(self).items() if v is not None}).encode()


@dataclass(frozen=True)
class ErrorResponse:
    error: str
    error_description: Optional[str] = None

    def to_json_bytes(self) -> bytes:
        return json.dumps({k: v for k, v in asdict(self).items() if v is not None}).encode()
