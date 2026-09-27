import os
import sys
import unittest
from datetime import date
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from clients.spring_auth import SpringAuthClient, SpringNotReadyError  # noqa: E402


class SpringAuthClientTest(unittest.TestCase):
    def setUp(self):
        self.token_provider = Mock(return_value="hydra-client-credentials-token")
        self.client = SpringAuthClient("http://backend:8080", self.token_provider)

    @patch("clients.spring_auth.requests.post")
    def test_provision_user_posts_with_bearer_token_and_returns_parsed_response(self, mock_post):
        mock_response = Mock(status_code=200)
        mock_response.json.return_value = {
            "id": 1,
            "username": "admin",
            "email": "admin@bytecore.ee",
            "role": "ADMIN",
        }
        mock_post.return_value = mock_response

        result = self.client.provision_user("admin", "admin@bytecore.ee", date(2000, 1, 1), "ADMIN")

        mock_post.assert_called_once_with(
            "http://backend:8080/internal/users",
            json={
                "username": "admin",
                "email": "admin@bytecore.ee",
                "dateOfBirth": "2000-01-01",
                "role": "ADMIN",
            },
            headers={"Authorization": "Bearer hydra-client-credentials-token"},
            timeout=10,
        )
        mock_response.raise_for_status.assert_called_once()
        self.assertEqual(result["id"], 1)
        self.assertEqual(result["role"], "ADMIN")

    @patch("clients.spring_auth.requests.post")
    def test_provision_user_propagates_http_errors(self, mock_post):
        mock_response = Mock(status_code=403)
        mock_response.raise_for_status.side_effect = RuntimeError("403 Forbidden")
        mock_post.return_value = mock_response

        with self.assertRaises(RuntimeError):
            self.client.provision_user("admin", "admin@bytecore.ee", date(2000, 1, 1), "ADMIN")

    @patch("clients.spring_auth.requests.get")
    def test_resolve_user_gets_with_bearer_token_and_returns_parsed_response(self, mock_get):
        mock_response = Mock(status_code=200)
        mock_response.json.return_value = {"id": 42, "username": "jdoe", "email": "jdoe@example.com", "role": "USER"}
        mock_get.return_value = mock_response

        result = self.client.resolve_user(42)

        mock_get.assert_called_once_with(
            "http://backend:8080/internal/users/42",
            headers={"Authorization": "Bearer hydra-client-credentials-token"},
            timeout=10,
        )
        mock_response.raise_for_status.assert_called_once()
        self.assertEqual(result["role"], "USER")

    @patch("clients.spring_auth.time.sleep", return_value=None)
    @patch("clients.spring_auth.requests.get")
    def test_wait_until_ready_returns_on_any_http_response(self, mock_get, mock_sleep):
        mock_get.return_value = Mock(status_code=401)

        self.client.wait_until_ready(attempts=3, delay_seconds=0.01)

        mock_get.assert_called_once()

    @patch("clients.spring_auth.time.sleep", return_value=None)
    @patch("clients.spring_auth.requests.get")
    def test_wait_until_ready_raises_after_exhausting_attempts_on_connection_errors(self, mock_get, mock_sleep):
        mock_get.side_effect = requests.exceptions.ConnectionError("refused")

        with self.assertRaises(SpringNotReadyError):
            self.client.wait_until_ready(attempts=3, delay_seconds=0.01)

        self.assertEqual(mock_get.call_count, 3)

    def test_uses_fresh_token_from_provider_on_each_call(self):
        with patch("clients.spring_auth.requests.post") as mock_post:
            mock_post.return_value = Mock(status_code=200, json=Mock(return_value={"id": 1}))

            self.client.provision_user("admin", "admin@bytecore.ee", date(2000, 1, 1), "ADMIN")
            self.client.provision_user("admin", "admin@bytecore.ee", date(2000, 1, 1), "ADMIN")

        self.assertEqual(self.token_provider.call_count, 2)


if __name__ == "__main__":
    unittest.main()
