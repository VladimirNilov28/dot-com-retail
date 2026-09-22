import logging
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import bootstrap
import bridge
from bootstrap import AccessControlSpecError
from config import Config, ConfigError, load_config
from hydra_client import HydraNotReadyError
from kratos_client import KratosNotReadyError

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def make_handler(config: Config):
    class BridgeRequestHandler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A002 - matches BaseHTTPRequestHandler signature
            logger.info("%s - %s", self.address_string(), fmt % args)

        def do_GET(self):
            parsed = urlparse(self.path)
            query = {k: v[0] for k, v in parse_qs(parsed.query).items()}

            try:
                if parsed.path == "/login":
                    redirect_to = bridge.handle_login(
                        config, query["login_challenge"], self.headers.get("Cookie")
                    )
                    self._redirect(redirect_to)
                elif parsed.path == "/consent":
                    redirect_to = bridge.handle_consent(config, query["consent_challenge"])
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


def main() -> int:
    try:
        config = load_config()
    except ConfigError as exc:
        logger.error("%s", exc)
        return 1

    try:
        bootstrap.run(config)
    except (HydraNotReadyError, KratosNotReadyError, AccessControlSpecError) as exc:
        logger.error("bootstrap failed: %s", exc)
        return 1

    server = ThreadingHTTPServer((config.bridge_host, config.bridge_port), make_handler(config))
    logger.info("serving login/consent bridge on %s:%s", config.bridge_host, config.bridge_port)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
