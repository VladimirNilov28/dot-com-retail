import os
import sys
import unittest
from datetime import date
from unittest.mock import Mock, patch
import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import Config  # noqa: E402
from services import bootstrap  # noqa: E402
import main  # noqa: E402


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
        self.spring_auth_client.resolve_user.return_value = self.spring_auth_client.provision_user.return_value
        self.spring_auth_client.provision_bootstrap_admin.return_value = self.spring_auth_client.provision_user.return_value

    def trusted_identity(self, **metadata_overrides):
        metadata = {
            "spring_user_id": 1,
            "bootstrap": {"version": 1, "username": "admin", "email": "admin@bytecore.ee"},
        }
        metadata.update(metadata_overrides)
        return {
            "id": "kratos-identity-uuid",
            "schema_id": "default",
            "traits": {"email": "admin@bytecore.ee"},
            "metadata_admin": metadata,
        }

    def test_provisions_spring_user_with_deterministic_fixture_fields(self):
        self.kratos_client.find_identity_by_email.return_value = None

        bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)

        self.spring_auth_client.provision_bootstrap_admin.assert_called_once_with(
            "admin", "admin@bytecore.ee", date(2000, 1, 1)
        )
        self.spring_auth_client.provision_user.assert_not_called()

    def test_creates_kratos_identity_linked_to_spring_user_id_when_missing(self):
        self.kratos_client.find_identity_by_email.return_value = None

        bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)

        self.kratos_client.create_identity.assert_called_once()
        _, kwargs = self.kratos_client.create_identity.call_args
        self.assertEqual(kwargs.get("metadata_admin"), self.trusted_identity()["metadata_admin"])

    def test_is_idempotent_when_identity_already_linked(self):
        self.kratos_client.find_identity_by_email.return_value = self.trusted_identity()

        bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)

        self.kratos_client.create_identity.assert_not_called()
        self.kratos_client.set_identity_metadata_admin.assert_not_called()
        self.spring_auth_client.resolve_user.assert_called_once_with(1)
        self.spring_auth_client.provision_user.assert_not_called()
        self.spring_auth_client.provision_bootstrap_admin.assert_not_called()

    def test_preclaimed_unlinked_self_or_foreign_linked_identities_are_rejected(self):
        for metadata in ({}, {"spring_user_id": 1}, {"spring_user_id": 2}, {"spring_user_id": "self"}):
            with self.subTest(metadata=metadata):
                self.kratos_client.find_identity_by_email.return_value = {
                    "id": "preclaimed-identity", "traits": {"email": self.config.admin_email},
                    "schema_id": "default", "metadata_admin": metadata,
                }
                with self.assertRaisesRegex(RuntimeError, "bootstrap"):
                    bootstrap.reconcile_kratos_admin_identity(
                        self.kratos_client, self.spring_auth_client, self.config
                    )
                self.assert_no_privileged_mutation()
                self.spring_auth_client.resolve_user.assert_not_called()

    def assert_no_privileged_mutation(self):
        self.spring_auth_client.provision_user.assert_not_called()
        self.spring_auth_client.provision_bootstrap_admin.assert_not_called()
        self.kratos_client.create_identity.assert_not_called()
        self.kratos_client.set_identity_metadata_admin.assert_not_called()

    def test_mismatched_bootstrap_metadata_traits_schema_and_links_fail_closed(self):
        identities = [
            self.trusted_identity(bootstrap={"version": 1, "username": "other", "email": self.config.admin_email}),
            self.trusted_identity(bootstrap={"version": 1, "username": "admin", "email": "foreign@example.com"}),
            self.trusted_identity(bootstrap={"version": 2, "username": "admin", "email": self.config.admin_email}),
            self.trusted_identity(spring_user_id=None),
            self.trusted_identity(spring_user_id="1"),
            self.trusted_identity(spring_user_id=True),
            {**self.trusted_identity(), "traits": {"email": "foreign@example.com"}},
            {**self.trusted_identity(), "schema_id": "foreign"},
        ]
        for identity in identities:
            with self.subTest(identity=identity):
                self.kratos_client.find_identity_by_email.return_value = identity
                with self.assertRaises(RuntimeError):
                    bootstrap.reconcile_kratos_admin_identity(
                        self.kratos_client, self.spring_auth_client, self.config
                    )
                self.assert_no_privileged_mutation()

    def test_trusted_link_must_resolve_to_exact_canonical_admin_without_role_healing(self):
        self.kratos_client.find_identity_by_email.return_value = self.trusted_identity()
        canonical = self.spring_auth_client.resolve_user.return_value
        for mismatch in ({"id": 2}, {"username": "other"}, {"email": "other@example.com"}, {"role": "USER"}):
            with self.subTest(mismatch=mismatch):
                self.spring_auth_client.resolve_user.return_value = {**canonical, **mismatch}
                with self.assertRaises(RuntimeError):
                    bootstrap.reconcile_kratos_admin_identity(
                        self.kratos_client, self.spring_auth_client, self.config
                    )
                self.assert_no_privileged_mutation()

    def test_stale_link_errors_do_not_disclose_credentials(self):
        self.kratos_client.find_identity_by_email.return_value = self.trusted_identity()
        self.spring_auth_client.resolve_user.side_effect = requests.HTTPError(
            f"stale link password={self.config.admin_password} token={self.config.oauth_service_client_secret}"
        )
        with self.assertRaises(RuntimeError) as raised:
            bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)
        self.assertNotIn(self.config.admin_password, str(raised.exception))
        self.assertNotIn(self.config.oauth_service_client_secret, str(raised.exception))
        self.assertIsNone(raised.exception.__cause__)
        self.assert_no_privileged_mutation()

    def test_canonical_preclaim_conflict_has_no_kratos_creation_or_legacy_fallback(self):
        self.kratos_client.find_identity_by_email.return_value = None
        self.spring_auth_client.provision_bootstrap_admin.side_effect = requests.HTTPError(
            f"conflict password={self.config.admin_password} token={self.config.oauth_service_client_secret}"
        )
        with self.assertRaises(RuntimeError) as raised:
            bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)
        self.assertNotIn(self.config.admin_password, str(raised.exception))
        self.assertNotIn(self.config.oauth_service_client_secret, str(raised.exception))
        self.spring_auth_client.provision_user.assert_not_called()
        self.kratos_client.create_identity.assert_not_called()

    def test_new_canonical_response_must_be_exact_admin_before_linking(self):
        self.kratos_client.find_identity_by_email.return_value = None
        canonical = self.spring_auth_client.provision_bootstrap_admin.return_value
        for mismatch in ({"id": None}, {"username": "other"}, {"email": "other@example.com"}, {"role": "USER"}):
            with self.subTest(mismatch=mismatch):
                self.spring_auth_client.provision_bootstrap_admin.return_value = {**canonical, **mismatch}
                with self.assertRaises(RuntimeError):
                    bootstrap.reconcile_kratos_admin_identity(
                        self.kratos_client, self.spring_auth_client, self.config
                    )
                self.spring_auth_client.provision_user.assert_not_called()
                self.kratos_client.create_identity.assert_not_called()

    def test_kratos_creation_failure_is_sanitized_and_never_adopts_raced_identity(self):
        self.kratos_client.find_identity_by_email.return_value = None
        self.kratos_client.create_identity.side_effect = requests.HTTPError(
            f"conflict password={self.config.admin_password} token={self.config.oauth_service_client_secret}"
        )
        with self.assertRaises(RuntimeError) as raised:
            bootstrap.reconcile_kratos_admin_identity(self.kratos_client, self.spring_auth_client, self.config)
        self.assertNotIn(self.config.admin_password, str(raised.exception))
        self.assertNotIn(self.config.oauth_service_client_secret, str(raised.exception))
        self.kratos_client.find_identity_by_email.assert_called_once()
        self.kratos_client.set_identity_metadata_admin.assert_not_called()

    def test_untrusted_bootstrap_stops_startup_with_sanitized_error(self):
        with (
            patch("main.load_config", return_value=self.config),
            patch("main.bootstrap.run", side_effect=bootstrap.BootstrapIdentityError("untrusted bootstrap")),
            patch("main.ThreadingHTTPServer") as server,
            self.assertLogs("main", level="ERROR") as logs,
        ):
            self.assertEqual(main.main(), 1)
        server.assert_not_called()
        output = "\n".join(logs.output)
        self.assertIn("untrusted bootstrap", output)
        self.assertNotIn(self.config.admin_password, output)
        self.assertNotIn(self.config.oauth_service_client_secret, output)


if __name__ == "__main__":
    unittest.main()
