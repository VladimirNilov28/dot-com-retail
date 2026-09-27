import os
import sys
import unittest
from datetime import date
from unittest.mock import Mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import Config  # noqa: E402
from services import bootstrap  # noqa: E402


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


class ReconcileKratosAdminIdentityTest(unittest.TestCase):
    def setUp(self):
        self.config = make_config()
        self.kratos_client = Mock()
        self.spring_auth_client = Mock()
        self.spring_auth_client.provision_user.return_value = {
            "id": 1,
            "username": "admin",
            "email": "admin@bytecore.ee",
            "role": "ADMIN",
        }

    def test_provisions_spring_user_with_deterministic_fixture_fields(self):
        self.kratos_client.find_identity_by_email.return_value = None

        bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)

        self.spring_auth_client.provision_user.assert_called_once_with(
            "admin", "admin@bytecore.ee", date(2000, 1, 1), "ADMIN"
        )

    def test_creates_kratos_identity_linked_to_spring_user_id_when_missing(self):
        self.kratos_client.find_identity_by_email.return_value = None

        bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)

        self.kratos_client.create_identity.assert_called_once()
        _, kwargs = self.kratos_client.create_identity.call_args
        self.assertEqual(kwargs.get("metadata_admin"), {"spring_user_id": 1})

    def test_is_idempotent_when_identity_already_linked(self):
        self.kratos_client.find_identity_by_email.return_value = {
            "id": "kratos-identity-uuid",
            "metadata_admin": {"spring_user_id": 1},
        }

        bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)

        self.kratos_client.create_identity.assert_not_called()
        self.kratos_client.set_identity_metadata_admin.assert_not_called()

    def test_links_existing_unlinked_identity_to_spring_user_id(self):
        self.kratos_client.find_identity_by_email.return_value = {
            "id": "kratos-identity-uuid",
            "metadata_admin": {},
        }

        bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)

        self.kratos_client.create_identity.assert_not_called()
        self.kratos_client.set_identity_metadata_admin.assert_called_once_with(
            "kratos-identity-uuid", {"spring_user_id": 1}
        )


if __name__ == "__main__":
    unittest.main()
