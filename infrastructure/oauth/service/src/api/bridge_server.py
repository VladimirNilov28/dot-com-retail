import logging
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

from clients.hydra import HydraClient
from clients.kratos import KratosClient
from config import Config
from services import login_consent

logger = logging.getLogger(__name__)


def make_bridge_handler(config: Config, hydra_client: HydraClient, kratos_client: KratosClient):
    class BridgeRequestHandler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A002 - matches BaseHTTPRequestHandler signature
            logger.info("%s - %s", self.address_string(), fmt % args)

        def do_GET(self):
            parsed = urlparse(self.path)
            query = {k: v[0] for k, v in parse_qs(parsed.query).items()}

            try:
                if parsed.path == "/login":
                    redirect_to = login_consent.handle_login(
                        config, hydra_client, kratos_client, query["login_challenge"], self.headers.get("Cookie")
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
                logger.error("request failed: %s", exc)
                self._respond(502, str(exc).encode())

        def _redirect(self, location: str):
            self.send_response(302)
            self.send_header("Location", location)
            self.end_headers()

        def _respond(self, status: int, body: bytes):
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return BridgeRequestHandler
