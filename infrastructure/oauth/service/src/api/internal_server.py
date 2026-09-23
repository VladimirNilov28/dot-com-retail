import logging
import socket
import struct
from http.server import BaseHTTPRequestHandler
from typing import Optional
from urllib.parse import urlparse

from clients.hydra import HydraClient
from clients.kratos import KratosAuthenticationError, KratosClient
from config import Config
from models import ErrorResponse, InvalidRequestError, TokenRequest
from services.dev_token import issue_dev_token

logger = logging.getLogger(__name__)


def _default_gateway_ip() -> Optional[str]:
    """The docker bridge's gateway address, as seen from inside this
    container. Docker's bridge NAT rewrites the source of host-loopback
    traffic (arriving via the compose 127.0.0.1:port:port publish, the real
    enforcement for this endpoint) to this address rather than 127.0.0.1 —
    a sibling container has its own distinct address, never this one."""
    try:
        with open("/proc/net/route") as f:
            for line in f.readlines()[1:]:
                fields = line.split()
                if len(fields) >= 3 and fields[1] == "00000000":
                    return socket.inet_ntoa(struct.pack("<L", int(fields[2], 16)))
    except OSError:
        pass
    return None


# Real enforcement is the compose port-publish (127.0.0.1:port:port). This
# allow-list is a secondary, defense-in-depth layer — see _default_gateway_ip
# for why the docker bridge gateway address is included alongside loopback.
_ALLOWED_ADDRESSES = {"127.0.0.1", "::1", _default_gateway_ip()} - {None}


def make_internal_handler(config: Config, hydra_client: HydraClient, kratos_client: KratosClient):
    class InternalRequestHandler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # noqa: A002 - matches BaseHTTPRequestHandler signature
            logger.info("%s - %s", self.address_string(), fmt % args)

        def do_POST(self):
            if self.client_address[0] not in _ALLOWED_ADDRESSES:
                self._respond_json(403, ErrorResponse(error="forbidden", error_description="loopback access only"))
                return

            if urlparse(self.path).path != "/internal/token":
                self._respond(404, b"not found")
                return

            try:
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                token_request = TokenRequest.from_json_bytes(body)
            except InvalidRequestError as exc:
                self._respond_json(400, ErrorResponse(error="invalid_request", error_description=str(exc)))
                return

            try:
                token_response = issue_dev_token(
                    config, hydra_client, kratos_client, token_request.email, token_request.password
                )
            except KratosAuthenticationError:
                self._respond_json(
                    401, ErrorResponse(error="invalid_grant", error_description="invalid email or password")
                )
                return
            except Exception as exc:  # noqa: BLE001 - top-level request error boundary
                logger.error("dev token issuance failed: %s", exc)
                self._respond_json(502, ErrorResponse(error="upstream_error", error_description=str(exc)))
                return

            self._respond(200, token_response.to_json_bytes())

        def _respond_json(self, status: int, body):
            self._respond(status, body.to_json_bytes())

        def _respond(self, status: int, body: bytes):
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return InternalRequestHandler
