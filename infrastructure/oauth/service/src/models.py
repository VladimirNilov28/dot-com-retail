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
class TokenResponse:
    access_token: str
    token_type: str
    expires_in: Optional[int] = None
    scope: Optional[str] = None

    def to_json_bytes(self) -> bytes:
        return json.dumps({k: v for k, v in asdict(self).items() if v is not None}).encode()


@dataclass(frozen=True)
class ErrorResponse:
    error: str
    error_description: Optional[str] = None

    def to_json_bytes(self) -> bytes:
        return json.dumps({k: v for k, v in asdict(self).items() if v is not None}).encode()
