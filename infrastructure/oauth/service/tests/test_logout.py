import os
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import Config  # noqa: E402
from services.logout import perform_logout  # noqa: E402


def make_config(**overrides) -> Config:
    defaults = dict(
        hydra_admin_url="http://hydra:4445",
        hydra_public_url="http://hydra:4444",
        kratos_admin_url="http://kratos:4434",
        kratos_public_url="http://kratos:4433",
        kratos_browser_url="http://127.0.0.1:4433",
        access_control_file="/app/access-control.yml",
        spring_internal_base_url="http://backend:8080",
        admin_username="admin",
        admin_email="admin@bytecore.ee",
        admin_password="admin-dev-password",
        bridge_host="0.0.0.0",
        bridge_port=4446,
        internal_token_host="0.0.0.0",
        internal_token_port=4447,
        dev_client_id="bytecore-web",
        dev_redirect_uri="http://localhost:4200/auth/callback",
        oauth_service_client_id="oauth-service-internal",
        oauth_service_client_secret="oauth-service-dev-secret",
        retry_attempts=10,
        retry_delay_seconds=2.0,
    )
    defaults.update(overrides)
    return Config(**defaults)


class PerformLogoutTest(unittest.TestCase):
    def setUp(self):
        self.config = make_config()
        self.hydra_client = Mock()
        self.kratos_client = Mock()

    def test_revokes_refresh_token_via_hydra(self):
        perform_logout(self.config, self.hydra_client, self.kratos_client, "refresh-token-value")

        self.hydra_client.revoke_token.assert_called_once_with("bytecore-web", "refresh-token-value")

    def test_does_not_call_kratos_logout_when_no_session_token_given(self):
        perform_logout(self.config, self.hydra_client, self.kratos_client, "refresh-token-value")

        self.kratos_client.logout.assert_not_called()

    def test_calls_kratos_logout_when_session_token_given(self):
        perform_logout(
            self.config,
            self.hydra_client,
            self.kratos_client,
            "refresh-token-value",
            kratos_session_token="kratos-session-token",
        )

        self.kratos_client.logout.assert_called_once_with("kratos-session-token")

    def test_raises_when_hydra_revocation_fails(self):
        self.hydra_client.revoke_token.side_effect = RuntimeError("hydra unreachable")

        with self.assertRaises(RuntimeError):
            perform_logout(self.config, self.hydra_client, self.kratos_client, "refresh-token-value")

        self.kratos_client.logout.assert_not_called()

    def test_kratos_logout_failure_does_not_raise_once_hydra_revocation_succeeded(self):
        self.kratos_client.logout.side_effect = RuntimeError("kratos session already gone")

        perform_logout(
            self.config,
            self.hydra_client,
            self.kratos_client,
            "refresh-token-value",
            kratos_session_token="kratos-session-token",
        )

        self.hydra_client.revoke_token.assert_called_once()


if __name__ == "__main__":
    unittest.main()
