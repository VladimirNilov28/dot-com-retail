import os
import sys
import unittest
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from auth_fixtures import active_session  # noqa: E402
from clients.kratos import KratosAuthenticationError, KratosClient  # noqa: E402


class WhoamiAssuranceTest(unittest.TestCase):
    def setUp(self):
        self.client = KratosClient("http://kratos:4434", "http://kratos:4433")

    @patch("clients.kratos.requests.get")
    def test_cookie_session_without_second_factor_is_valid(self, get):
        session = active_session()
        get.return_value = Mock(status_code=200, json=Mock(return_value=session))
        self.assertEqual(self.client.whoami("cookie"), session)
        self.assertEqual(get.call_args.kwargs["headers"]["Cookie"], "cookie")

    @patch("clients.kratos.requests.get")
    def test_native_token_session_uses_kratos_header_not_url(self, get):
        session = active_session("aal2")
        get.return_value = Mock(status_code=200, json=Mock(return_value=session))
        self.assertEqual(self.client.whoami(None, session_token="native-fixture"), session)
        self.assertEqual(get.call_args.args[0], "http://kratos:4433/sessions/whoami")
        self.assertEqual(get.call_args.kwargs["headers"]["X-Session-Token"], "native-fixture")
        self.assertNotIn("Cookie", get.call_args.kwargs["headers"])

    @patch("clients.kratos.requests.get")
    def test_rejected_cookie_is_anonymous(self, get):
        get.return_value = Mock(status_code=401)
        self.assertIsNone(self.client.whoami("expired-cookie"))

    @patch("clients.kratos.requests.get")
    def test_second_factor_required_is_not_anonymous(self, get):
        get.return_value = Mock(
            status_code=403,
            json=Mock(return_value={"error": {"id": "session_aal2_required", "reason": "sensitive-fixture"}}),
        )
        with self.assertRaises(KratosAuthenticationError) as caught:
            self.client.whoami("cookie")
        self.assertNotIn("sensitive-fixture", str(caught.exception))

    @patch("clients.kratos.requests.get")
    def test_inactive_or_unknown_assurance_session_is_not_accepted(self, get):
        inactive = active_session()
        inactive["active"] = False
        missing_aal = active_session()
        del missing_aal["authenticator_assurance_level"]
        unknown_aal = active_session("unrecognized")
        missing_identity = active_session()
        del missing_identity["identity"]
        for session in (inactive, missing_aal, unknown_aal, missing_identity):
            with self.subTest(fields=list(session)):
                get.return_value = Mock(status_code=200, json=Mock(return_value=session))
                with self.assertRaises(KratosAuthenticationError):
                    self.client.whoami("cookie")

    @patch("clients.kratos.requests.get")
    def test_unexpected_http_status_is_an_explicit_upstream_error(self, get):
        for status in (403, 429, 500, 503):
            with self.subTest(status=status):
                get.return_value = Mock(
                    status_code=status,
                    json=Mock(return_value={"error": {"id": "unexpected", "reason": "sensitive-fixture"}}),
                )
                with self.assertRaises(RuntimeError) as caught:
                    self.client.whoami("cookie")
                self.assertNotIn("sensitive-fixture", str(caught.exception))

    @patch("clients.kratos.requests.get")
    def test_network_error_is_sanitized_and_not_anonymous(self, get):
        get.side_effect = requests.ConnectionError("upstream?session_token=sensitive-fixture")
        with self.assertRaises(RuntimeError) as caught:
            self.client.whoami("cookie")
        self.assertNotIn("sensitive-fixture", str(caught.exception))

    @patch("clients.kratos.requests.get")
    def test_missing_credentials_do_not_call_upstream(self, get):
        self.assertIsNone(self.client.whoami(None))
        get.assert_not_called()


if __name__ == "__main__":
    unittest.main()
