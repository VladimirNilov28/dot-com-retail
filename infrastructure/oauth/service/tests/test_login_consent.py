import os
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import Config  # noqa: E402
from services.login_consent import (  # noqa: E402
    UnprovisionedIdentityError,
    handle_login,
    resolve_canonical_user,
)


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


class ResolveCanonicalUserTest(unittest.TestCase):
    def test_resolves_linked_identity_via_spring(self):
        kratos_client = Mock()
        kratos_client.get_identity.return_value = {
            "id": "kratos-identity-uuid",
            "metadata_admin": {"spring_user_id": 42},
        }
        spring_auth_client = Mock()
        spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}

        result = resolve_canonical_user(kratos_client, spring_auth_client, "kratos-identity-uuid")

        spring_auth_client.resolve_user.assert_called_once_with(42)
        self.assertEqual(result, {"id": 42, "role": "USER"})

    def test_raises_explicitly_for_unlinked_identity(self):
        kratos_client = Mock()
        kratos_client.get_identity.return_value = {"id": "kratos-identity-uuid", "metadata_admin": {}}
        spring_auth_client = Mock()

        with self.assertRaises(UnprovisionedIdentityError):
            resolve_canonical_user(kratos_client, spring_auth_client, "kratos-identity-uuid")

        spring_auth_client.resolve_user.assert_not_called()


class HandleLoginTest(unittest.TestCase):
    def setUp(self):
        self.config = make_config()
        self.hydra_client = Mock()
        self.kratos_client = Mock()
        self.spring_auth_client = Mock()

    def test_accepts_login_with_spring_user_id_as_subject(self):
        self.hydra_client.get_login_request.return_value = {"skip": False}
        self.kratos_client.whoami.return_value = {"identity": {"id": "kratos-identity-uuid"}}
        self.kratos_client.get_identity.return_value = {
            "id": "kratos-identity-uuid",
            "metadata_admin": {"spring_user_id": 42},
        }
        self.spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}
        self.hydra_client.accept_login_request.return_value = {"redirect_to": "http://hydra/next"}

        redirect = handle_login(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "login-challenge", "cookie"
        )

        self.hydra_client.accept_login_request.assert_called_once_with(
            "login-challenge", "42", context={"role": "USER"}
        )
        self.assertEqual(redirect, "http://hydra/next")

    def test_fails_explicitly_for_unprovisioned_identity(self):
        self.hydra_client.get_login_request.return_value = {"skip": False}
        self.kratos_client.whoami.return_value = {"identity": {"id": "kratos-identity-uuid"}}
        self.kratos_client.get_identity.return_value = {"id": "kratos-identity-uuid", "metadata_admin": {}}

        with self.assertRaises(UnprovisionedIdentityError):
            handle_login(
                self.config,
                self.hydra_client,
                self.kratos_client,
                self.spring_auth_client,
                "login-challenge",
                "cookie",
            )

        self.hydra_client.accept_login_request.assert_not_called()

    def test_redirects_to_kratos_when_no_session(self):
        self.hydra_client.get_login_request.return_value = {"skip": False}
        self.kratos_client.whoami.return_value = None
        self.kratos_client.browser_login_url.return_value = "http://kratos/login"

        redirect = handle_login(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "login-challenge", None
        )

        self.assertEqual(redirect, "http://kratos/login")
        self.hydra_client.accept_login_request.assert_not_called()

    def test_resolves_role_via_spring_on_remembered_skip_path(self):
        self.hydra_client.get_login_request.return_value = {"skip": True, "subject": "42"}
        self.spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}
        self.hydra_client.accept_login_request.return_value = {"redirect_to": "http://hydra/next"}

        handle_login(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "login-challenge", None
        )

        self.spring_auth_client.resolve_user.assert_called_once_with(42)
        self.hydra_client.accept_login_request.assert_called_once_with(
            "login-challenge", "42", context={"role": "USER"}
        )


if __name__ == "__main__":
    unittest.main()
