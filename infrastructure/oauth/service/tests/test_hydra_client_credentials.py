import os
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from clients.hydra import HydraClient  # noqa: E402


class ClientCredentialsTokenTest(unittest.TestCase):
    def setUp(self):
        self.client = HydraClient("http://hydra:4445", "http://hydra:4444")

    @patch("clients.hydra.requests.post")
    def test_requests_a_client_credentials_token_and_returns_access_token(self, mock_post):
        mock_response = Mock(status_code=200)
        mock_response.json.return_value = {"access_token": "abc123", "token_type": "bearer"}
        mock_post.return_value = mock_response

        token = self.client.client_credentials_token("oauth-service-internal", "s3cr3t", "internal:provision-user")

        mock_post.assert_called_once_with(
            "http://hydra:4444/oauth2/token",
            data={
                "grant_type": "client_credentials",
                "client_id": "oauth-service-internal",
                "client_secret": "s3cr3t",
                "scope": "internal:provision-user",
            },
            timeout=10,
        )
        mock_response.raise_for_status.assert_called_once()
        self.assertEqual(token, "abc123")


if __name__ == "__main__":
    unittest.main()
