import os
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from clients.kratos import KratosClient  # noqa: E402


class LogoutTest(unittest.TestCase):
    def setUp(self):
        self.client = KratosClient("http://kratos:4434", "http://kratos:4433")

    @patch("clients.kratos.requests.delete")
    def test_calls_kratos_native_logout_with_delete_method(self, mock_delete):
        # Kratos's PerformNativeLogout operation is DELETE /self-service/logout/api
        # (confirmed against ory/kratos's generated client/OpenAPI spec) -- not
        # POST, which Kratos rejects with 405 Method Not Allowed.
        mock_response = Mock(status_code=204)
        mock_delete.return_value = mock_response

        self.client.logout("session-token-value")

        mock_delete.assert_called_once_with(
            "http://kratos:4433/self-service/logout/api",
            json={"session_token": "session-token-value"},
            headers={"Accept": "application/json"},
            timeout=10,
        )
        mock_response.raise_for_status.assert_called_once()

    @patch("clients.kratos.requests.delete")
    def test_propagates_http_errors_from_kratos(self, mock_delete):
        import requests

        mock_response = Mock(status_code=401)
        mock_response.raise_for_status.side_effect = requests.HTTPError("401 invalid session token")
        mock_delete.return_value = mock_response

        with self.assertRaises(requests.HTTPError):
            self.client.logout("session-token-value")


if __name__ == "__main__":
    unittest.main()
