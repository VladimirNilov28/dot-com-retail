import http.client
import json
import os
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from api.bridge_server import make_bridge_handler  # noqa: E402
from api.internal_server import make_internal_handler  # noqa: E402
from clients.kratos import KratosClient  # noqa: E402
from models import TokenResponse  # noqa: E402
from test_login_consent import make_config  # noqa: E402


class AuthHttpErrorsTest(unittest.TestCase):
    def start_server(self, handler):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 5)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
        self.addCleanup(connection.close)
        return connection

    @patch("api.internal_server.issue_dev_token")
    def test_native_upstream_errors_are_not_returned_or_logged(self, issue):
        issue.side_effect = requests.ConnectionError("upstream?totp_code=private-otp&token=private-token")
        connection = self.start_server(make_internal_handler(make_config(), Mock(), Mock(), Mock()))
        with self.assertLogs("api.internal_server", level="INFO") as logs:
            connection.request(
                "POST", "/internal/token",
                body=json.dumps({"email": "user@example.com", "password": "private-password"}),
                headers={"Content-Type": "application/json"},
            )
            response = connection.getresponse()
            body = response.read().decode()
        self.assertEqual(response.status, 502)
        self.assertEqual(json.loads(body)["error"], "upstream_error")
        for sensitive in ("private-otp", "private-token", "private-password"):
            self.assertNotIn(sensitive, body)
            self.assertNotIn(sensitive, "\n".join(logs.output))

    @patch("api.bridge_server.login_consent.handle_login")
    def test_browser_upstream_errors_are_not_returned_or_logged(self, login):
        login.side_effect = requests.ConnectionError("upstream?secret=private-secret&lookup=private-code")
        connection = self.start_server(make_bridge_handler(make_config(), Mock(), Mock(), Mock()))
        with self.assertLogs("api.bridge_server", level="INFO") as logs:
            connection.request("GET", "/login?login_challenge=fixture")
            response = connection.getresponse()
            body = response.read().decode()
        self.assertEqual(response.status, 502)
        for sensitive in ("private-secret", "private-code"):
            self.assertNotIn(sensitive, body)
            self.assertNotIn(sensitive, "\n".join(logs.output))

    @patch("clients.kratos.requests.post")
    @patch("clients.kratos.requests.get")
    def test_provisional_password_session_returns_sanitized_second_factor_error(self, get, post):
        config = make_config()
        kratos = KratosClient(config.kratos_admin_url, config.kratos_public_url)
        hydra = Mock()
        spring = Mock()
        spring.resolve_user.return_value = {"id": 42, "role": "USER"}
        hydra.get_client.return_value = {"scope": "user:read"}
        hydra.start_authorization.return_value = "http://hydra/login?login_challenge=fixture"
        hydra.accept_login_request.return_value = {"redirect_to": "http://hydra/next"}
        hydra.follow_redirect.side_effect = [
            "http://hydra/consent?consent_challenge=fixture",
            "http://localhost/callback?code=fixture",
        ]
        hydra.get_consent_request.return_value = {"requested_scope": ["user:read"], "context": {"role": "USER"}}
        hydra.accept_consent_request.return_value = {"redirect_to": "http://hydra/next"}
        hydra.exchange_code_for_token.return_value = {"access_token": "private-oauth-token"}

        def kratos_response(url, **kwargs):
            if url.endswith("/sessions/whoami"):
                return Mock(status_code=403, json=Mock(return_value={
                    "error": {"id": "session_aal2_required", "reason": "private-otp"},
                }))
            if "/admin/identities/" in url:
                return Mock(status_code=200, json=Mock(return_value={"metadata_admin": {"spring_user_id": 42}}))
            return Mock(status_code=200, json=Mock(return_value={
                "ui": {"action": "http://127.0.0.1:4433/self-service/login?flow=fixture"},
            }))

        get.side_effect = kratos_response
        post.return_value = Mock(status_code=200, json=Mock(return_value={
            "session_token": "private-session-token",
            "session": {"active": True, "identity": {"id": "fixture"}, "authenticator_assurance_level": "aal1"},
        }))
        connection = self.start_server(make_internal_handler(config, hydra, kratos, spring))
        with self.assertLogs("api.internal_server", level="INFO") as logs:
            connection.request(
                "POST", "/internal/token",
                body=json.dumps({"email": "user@example.com", "password": "private-password"}),
                headers={"Content-Type": "application/json"},
            )
            response = connection.getresponse()
            body = response.read().decode()
        self.assertEqual(response.status, 403)
        self.assertEqual(json.loads(body)["error"], "second_factor_required")
        for sensitive in ("private-otp", "private-session-token", "private-password"):
            self.assertNotIn(sensitive, body)
            self.assertNotIn(sensitive, "\n".join(logs.output))
        hydra.accept_login_request.assert_not_called()
        hydra.exchange_code_for_token.assert_not_called()

    @patch("api.internal_server.issue_dev_token")
    def test_mixed_credentials_are_bad_request_without_authentication(self, issue):
        issue.return_value = TokenResponse(access_token="fixture-token", token_type="bearer")
        connection = self.start_server(make_internal_handler(make_config(), Mock(), Mock(), Mock()))
        connection.request(
            "POST", "/internal/token",
            body=json.dumps({"email": "user@example.com", "password": "fixture", "kratos_session_token": "fixture"}),
            headers={"Content-Type": "application/json"},
        )
        response = connection.getresponse()
        response.read()
        self.assertEqual(response.status, 400)
        issue.assert_not_called()


if __name__ == "__main__":
    unittest.main()
