import logging
import hmac
import json
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

from clients.hydra import HydraClient
from clients.kratos import KratosClient
from clients.spring_auth import SpringAuthClient
from config import Config
from models import ErrorResponse, InvalidRequestError, LogoutRequest
from services import login_consent
from services.logout import perform_logout
from services.registration import RegistrationService, RegistrationRejected, CaptchaUnavailable
from clients.kratos import KratosSecondFactorRequiredError
import psycopg
import requests

logger = logging.getLogger(__name__)


def make_bridge_handler(
    config: Config, hydra_client: HydraClient, kratos_client: KratosClient, spring_auth_client: SpringAuthClient
):
    registration = RegistrationService(config, spring_auth_client)

    class BridgeRequestHandler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A002 - matches BaseHTTPRequestHandler signature
            logger.info("%s - %s %s", self.address_string(), self.command, urlparse(self.path).path)

        def do_GET(self):
            parsed = urlparse(self.path)
            query = {k: v[0] for k, v in parse_qs(parsed.query).items()}

            try:
                if parsed.path == "/login":
                    redirect_to = login_consent.handle_login(
                        config,
                        hydra_client,
                        kratos_client,
                        spring_auth_client,
                        query["login_challenge"],
                        self.headers.get("Cookie"),
                    )
                    self._redirect(redirect_to)
                elif parsed.path == "/consent":
                    redirect_to = login_consent.handle_consent(
                        config, hydra_client, query["consent_challenge"]
                    )
                    self._redirect(redirect_to)
                elif parsed.path == "/healthz":
                    self._respond(200, b"ok")
                else:
                    self._respond(404, b"not found")
            except KeyError as exc:
                self._respond(400, f"missing query parameter: {exc}".encode())
            except Exception as exc:  # noqa: BLE001 - top-level request error boundary
                logger.error("request failed (%s)", type(exc).__name__)
                self._respond(502, b"authentication upstream failed")

        def do_POST(self):
            parsed = urlparse(self.path)
            if parsed.path.startswith("/internal/"):
                self._internal(parsed.path)
                return
            if parsed.path != "/logout":
                self._respond(404, b"not found")
                return

            try:
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                logout_request = LogoutRequest.from_json_bytes(body)
            except InvalidRequestError as exc:
                self._respond_json(400, ErrorResponse(error="invalid_request", error_description=str(exc)))
                return

            try:
                perform_logout(
                    config,
                    hydra_client,
                    kratos_client,
                    logout_request.refresh_token,
                    logout_request.kratos_session_token,
                )
            except Exception as exc:  # noqa: BLE001 - top-level request error boundary
                logger.error("logout failed (%s)", type(exc).__name__)
                self._respond_json(502, ErrorResponse(error="upstream_error", error_description="logout upstream failed"))
                return

            self._respond(204, b"")

        def _internal(self, path):
            if len(config.auth_bridge_secret) < 32 or config.auth_bridge_secret.startswith("replace-with"):
                self._dictionary(503, {"error": "authentication_unavailable"})
                return
            if not hmac.compare_digest(
                self.headers.get("Authorization", "").encode("utf-8"),
                ("Bearer " + config.auth_bridge_secret).encode("utf-8")
            ):
                self._dictionary(403, {"error": "forbidden"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 65536:
                    self._dictionary(413, {"error": "invalid_request"})
                    return
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("Invalid request object")
                if path == "/internal/captcha/challenge":
                    self._dictionary(200, registration.challenge(body["flow_id"]))
                elif path == "/internal/registration/pre-persist":
                    result = registration.pre_persist(body, self.headers.get("Ory-Webhook-Trigger-ID", ""))
                    self._dictionary(200, result)
                elif path == "/internal/registration/bind":
                    spring_auth_client.bind_registration(body["identity_id"])
                    self._dictionary(200, {})
                elif path == "/internal/login":
                    self._dictionary(200, login_consent.bff_login(
                        config, hydra_client, kratos_client, spring_auth_client,
                        body["challenge"], body["cookie"], body["state"],
                    ))
                elif path == "/internal/consent":
                    target = login_consent.handle_consent(
                        config, hydra_client, body["challenge"], config.bff_client_id, body["state"],
                    )
                    self._dictionary(200, {"redirect_to": target})
                elif path == "/internal/session":
                    self._dictionary(200, login_consent.bff_session(
                        kratos_client, spring_auth_client, body["cookie"],
                    ))
                elif path == "/internal/logout":
                    kratos_client.revoke_browser_session(body["session_id"])
                    self._respond(204, b"")
                else:
                    self._dictionary(404, {"error": "not_found"})
            except RegistrationRejected as error:
                self._hook_error(400, str(error))
            except (ValueError, TypeError, KeyError):
                self._hook_error(400, "The form is invalid or expired. Start again.")
            except KratosSecondFactorRequiredError:
                self._dictionary(403, {"error": "second_factor_required"})
            except login_consent.UnverifiedIdentityError:
                self._dictionary(403, {"error": "verification_required"})
            except requests.HTTPError as error:
                if path == "/internal/registration/pre-persist" and error.response.status_code == 409:
                    self._hook_error(400, "This email or username cannot be registered. Try signing in.")
                else:
                    logger.error("private authentication request failed (%s)", type(error).__name__)
                    self._hook_error(502, "Authentication is unavailable. Try again.")
            except (CaptchaUnavailable, psycopg.Error, requests.RequestException, login_consent.BridgeError) as error:
                logger.error("private authentication request failed (%s)", type(error).__name__)
                self._hook_error(502, "Authentication is unavailable. Try again.")
            except Exception as error:  # noqa: BLE001 - request boundary, never logs credentials
                logger.error("private authentication request failed (%s)", type(error).__name__)
                self._hook_error(502, "Authentication is unavailable. Try again.")

        def _hook_error(self, status, message):
            self._dictionary(status, {"error": "authentication_rejected", "messages": [{
                "instance_ptr": "#/", "messages": [{"id": 4000001, "type": "error", "text": message}],
            }]})

        def _dictionary(self, status, body):
            self._respond(status, json.dumps(body, separators=(",", ":")).encode(), "application/json")

        def _redirect(self, location: str):
            self.send_response(302)
            self.send_header("Location", location)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()

        def _respond_json(self, status: int, body):
            self._respond(status, body.to_json_bytes(), "application/json")

        def _respond(self, status: int, body: bytes, content_type="text/plain; charset=utf-8"):
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)


    return BridgeRequestHandler
