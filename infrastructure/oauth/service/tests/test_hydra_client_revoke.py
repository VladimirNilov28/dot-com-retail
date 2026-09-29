import os
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from clients.hydra import HydraClient  # noqa: E402


class RevokeTokenTest(unittest.TestCase):
    def setUp(self):
        self.client = HydraClient("http://hydra:4445", "http://hydra:4444")

    @patch("clients.hydra.requests.post")
    def test_revokes_without_client_secret_for_public_client(self, mock_post):
        mock_response = Mock(status_code=200)
        mock_post.return_value = mock_response

        self.client.revoke_token("bytecore-web", "refresh-token-value")

        mock_post.assert_called_once_with(
            "http://hydra:4444/oauth2/revoke",
            data={"client_id": "bytecore-web", "token": "refresh-token-value"},
            timeout=10,
        )
        mock_response.raise_for_status.assert_called_once()

    @patch("clients.hydra.requests.post")
    def test_revokes_with_client_secret_when_provided(self, mock_post):
        mock_response = Mock(status_code=200)
        mock_post.return_value = mock_response

        self.client.revoke_token("oauth-service-internal", "some-token", client_secret="s3cr3t")

        mock_post.assert_called_once_with(
            "http://hydra:4444/oauth2/revoke",
            data={"client_id": "oauth-service-internal", "token": "some-token", "client_secret": "s3cr3t"},
            timeout=10,
        )

    @patch("clients.hydra.requests.post")
    def test_propagates_http_errors_from_hydra(self, mock_post):
        import requests

        mock_response = Mock(status_code=401)
        mock_response.raise_for_status.side_effect = requests.HTTPError("401 client authentication failed")
        mock_post.return_value = mock_response

        with self.assertRaises(requests.HTTPError):
            self.client.revoke_token("bytecore-web", "refresh-token-value")


if __name__ == "__main__":
    unittest.main()
