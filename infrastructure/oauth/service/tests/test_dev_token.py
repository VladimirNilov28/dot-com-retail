import os
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import Config  # noqa: E402
from auth_fixtures import active_session  # noqa: E402
from clients.kratos import KratosAuthenticationError, KratosClient  # noqa: E402
from services.dev_token import issue_dev_token  # noqa: E402


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


class IssueDevTokenTest(unittest.TestCase):
    def setUp(self):
        self.config = make_config()
        self.hydra_client = Mock()
        self.kratos_client = Mock()
        self.spring_auth_client = Mock()

        self.kratos_client.authenticate_with_password.return_value = {
            "session_token": "kratos-native-session-token",
            "session": active_session(),
        }
        self.kratos_client.whoami.return_value = active_session()
        self.kratos_client.get_identity.return_value = {
            "metadata_admin": {"spring_user_id": 1},
        }
        self.spring_auth_client.resolve_user.return_value = {
            "id": 1,
            "username": "jane",
            "role": "USER",
        }

        self.hydra_client.get_client.return_value = {
            "scope": "user:read user:write offline_access",
        }
        self.hydra_client.new_authorization_session.return_value = Mock()
        self.hydra_client.start_authorization.return_value = (
            "http://hydra:4444/login?login_challenge=login-chal"
        )
        self.hydra_client.accept_login_request.return_value = {
            "redirect_to": "http://hydra:4444/oauth2/auth?consent_challenge=consent-chal"
        }
        self.hydra_client.follow_redirect.side_effect = [
            "http://hydra:4444/oauth2/auth?consent_challenge=consent-chal",
            "http://localhost:4200/auth/callback?code=auth-code&state=xyz",
        ]
        self.hydra_client.get_consent_request.return_value = {
            "requested_scope": ["user:read", "user:write", "offline_access"],
            "requested_access_token_audience": [],
            "context": {"role": "USER"},
        }
        self.hydra_client.accept_consent_request.return_value = {
            "redirect_to": "http://hydra:4444/oauth2/auth?consent_challenge=consent-chal"
        }
        self.hydra_client.exchange_code_for_token.return_value = {
            "access_token": "access-token-value",
            "refresh_token": "refresh-token-value",
            "token_type": "bearer",
            "expires_in": 1800,
            "scope": "user:read user:write offline_access",
        }

    def test_requests_the_client_full_registered_scope_including_offline_access(self):
        issue_dev_token(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "jane@example.com", "pw"
        )

        _, kwargs = self.hydra_client.start_authorization.call_args
        self.assertEqual(kwargs["scope"], "user:read user:write offline_access")

    def test_response_includes_refresh_token_when_hydra_issues_one(self):
        response = issue_dev_token(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "jane@example.com", "pw"
        )

        self.assertEqual(response.refresh_token, "refresh-token-value")

    def test_response_includes_kratos_session_token_for_later_logout(self):
        response = issue_dev_token(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "jane@example.com", "pw"
        )

        self.assertEqual(response.kratos_session_token, "kratos-native-session-token")

    def test_response_has_no_refresh_token_when_hydra_does_not_issue_one(self):
        self.hydra_client.exchange_code_for_token.return_value = {
            "access_token": "access-token-value",
            "token_type": "bearer",
            "expires_in": 1800,
            "scope": "user:read user:write",
        }

        response = issue_dev_token(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "jane@example.com", "pw"
        )

        self.assertIsNone(response.refresh_token)

    def test_password_session_is_revalidated_before_hydra_authorization(self):
        calls = Mock()
        calls.attach_mock(self.kratos_client.whoami, "whoami")
        calls.attach_mock(self.hydra_client.start_authorization, "authorize")

        issue_dev_token(
            self.config, self.hydra_client, self.kratos_client,
            self.spring_auth_client, "jane@example.com", "pw",
        )

        self.kratos_client.whoami.assert_called_once_with(
            None, session_token="kratos-native-session-token",
        )
        self.assertEqual(calls.mock_calls[0][0], "whoami")

    def test_validated_aal2_native_session_can_issue_token_without_password(self):
        self.kratos_client.whoami.return_value = active_session("aal2")
        response = issue_dev_token(
            self.config, self.hydra_client, self.kratos_client,
            self.spring_auth_client, None, None,
            kratos_session_token="completed-aal2-session",
        )
        self.kratos_client.authenticate_with_password.assert_not_called()
        self.kratos_client.whoami.assert_called_once_with(None, session_token="completed-aal2-session")
        self.assertEqual(response.kratos_session_token, "completed-aal2-session")
        self.assertEqual(response.scope, "user:read user:write offline_access")
        self.hydra_client.accept_login_request.assert_called_once_with(
            "login-chal", "1", context={"role": "USER"},
        )

    def test_invalid_native_session_never_reaches_hydra(self):
        self.kratos_client.whoami.return_value = None
        with self.assertRaises(KratosAuthenticationError):
            issue_dev_token(
                self.config, self.hydra_client, self.kratos_client,
                self.spring_auth_client, "jane@example.com", "pw",
            )
        self.hydra_client.start_authorization.assert_not_called()
        self.hydra_client.accept_login_request.assert_not_called()
        self.hydra_client.exchange_code_for_token.assert_not_called()
        self.spring_auth_client.resolve_user.assert_not_called()

    def test_password_login_without_native_session_token_fails_closed(self):
        self.kratos_client.authenticate_with_password.return_value = {"session": active_session()}
        with self.assertRaises(KratosAuthenticationError):
            issue_dev_token(
                self.config, self.hydra_client, self.kratos_client,
                self.spring_auth_client, "jane@example.com", "pw",
            )
        self.hydra_client.start_authorization.assert_not_called()

    @patch("clients.kratos.requests.get")
    @patch("clients.kratos.requests.post")
    def test_password_only_provisional_session_is_not_an_oauth_login(self, post, get):
        kratos = KratosClient(self.config.kratos_admin_url, self.config.kratos_public_url)
        def kratos_response(url, **kwargs):
            if url.endswith("/sessions/whoami"):
                return Mock(status_code=403, json=Mock(return_value={"error": {"id": "session_aal2_required"}}))
            if "/admin/identities/" in url:
                return Mock(status_code=200, json=Mock(return_value={"metadata_admin": {"spring_user_id": 1}}))
            return Mock(status_code=200, json=Mock(return_value={
                "ui": {"action": "http://127.0.0.1:4433/self-service/login?flow=fixture"},
            }))

        get.side_effect = kratos_response
        post.return_value = Mock(status_code=200, json=Mock(return_value={
            "session_token": "provisional-aal1-token",
            "session": active_session(),
        }))
        with self.assertRaises(KratosAuthenticationError):
            issue_dev_token(
                self.config, self.hydra_client, kratos, self.spring_auth_client,
                "jane@example.com", "pw",
            )
        self.hydra_client.start_authorization.assert_not_called()
        self.hydra_client.exchange_code_for_token.assert_not_called()


if __name__ == "__main__":
    unittest.main()
