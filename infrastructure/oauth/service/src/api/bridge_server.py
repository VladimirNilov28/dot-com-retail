import logging
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

from clients.hydra import HydraClient
from clients.kratos import KratosClient
from clients.spring_auth import SpringAuthClient
from config import Config
from models import ErrorResponse, InvalidRequestError, LogoutRequest
from services import login_consent
from services.logout import perform_logout

logger = logging.getLogger(__name__)


def make_bridge_handler(
    config: Config, hydra_client: HydraClient, kratos_client: KratosClient, spring_auth_client: SpringAuthClient
):
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

        def _redirect(self, location: str):
            self.send_response(302)
            self.send_header("Location", location)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()

        def _respond_json(self, status: int, body):
            self._respond(status, body.to_json_bytes())

        def _respond(self, status: int, body: bytes):
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)


    return BridgeRequestHandler
