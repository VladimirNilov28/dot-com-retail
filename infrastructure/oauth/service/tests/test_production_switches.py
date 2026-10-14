import os
import sys
import unittest
from unittest.mock import MagicMock, Mock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from clients.mailer import SmtpMailer  # noqa: E402
from config import ConfigError, load_config  # noqa: E402
from services.bootstrap import AccessControlSpec, ClientSpec, Scope, reconcile_hydra_clients  # noqa: E402

BASE_ENV = {
    "HYDRA_ADMIN_URL": "http://hydra:4445",
    "HYDRA_PUBLIC_URL": "http://hydra:4444",
    "KRATOS_ADMIN_URL": "http://kratos:4434",
    "KRATOS_PUBLIC_URL": "http://kratos:4433",
    "SPRING_INTERNAL_BASE_URL": "http://backend:8080",
    "ADMIN_USERNAME": "admin",
    "ADMIN_PASSWORD": "admin-dev-password",
    "OAUTH_SERVICE_CLIENT_SECRET": "oauth-service-dev-secret",
    "BFF_CLIENT_SECRET": "bff-unit-test-" * 4,
    "AUTH_BRIDGE_SECRET": "bridge-unit-test-" * 4,
    "CAPTCHA_SECRET": "captcha-unit-test-" * 4,
    "AUTH_DATABASE_URL": "postgres://unit@localhost/unit",
}


class ProductionConfigTest(unittest.TestCase):
    def load(self, **overrides):
        with patch.dict(os.environ, {**BASE_ENV, **overrides}, clear=True):
            return load_config()

    def test_dev_defaults_keep_plain_smtp_and_dev_token_endpoint(self):
        config = self.load()
        self.assertTrue(config.dev_token_endpoint_enabled)
        self.assertFalse(config.smtp_starttls)
        self.assertFalse(config.smtp_implicit_tls)
        self.assertEqual(config.smtp_username, "")

    def test_production_switches_are_parsed(self):
        config = self.load(OAUTH_DEV_TOKEN_ENDPOINT="disabled", SMTP_STARTTLS="true",
                           SMTP_USERNAME="mailer", SMTP_PASSWORD="pw")
        self.assertFalse(config.dev_token_endpoint_enabled)
        self.assertTrue(config.smtp_starttls)
        self.assertEqual(config.smtp_username, "mailer")

    def test_invalid_or_conflicting_flags_fail(self):
        with self.assertRaises(ConfigError):
            self.load(OAUTH_DEV_TOKEN_ENDPOINT="maybe")
        with self.assertRaises(ConfigError):
            self.load(SMTP_STARTTLS="true", SMTP_IMPLICIT_TLS="true")


class DevClientSkipTest(unittest.TestCase):
    spec = AccessControlSpec(
        version=1,
        scopes=[Scope(name="user:read", description="")],
        roles=[],
        clients=[ClientSpec(client_id="bytecore-web", client_name="dev", grant_types=["authorization_code"],
                            response_types=["code"], token_endpoint_auth_method="none",
                            redirect_uris=["http://localhost:4200/auth/callback"], scopes=None)],
    )

    def test_dev_client_not_created_when_endpoint_disabled(self):
        hydra = Mock()
        hydra.get_client.return_value = None
        config = Mock(dev_client_id="bytecore-web", dev_token_endpoint_enabled=False,
                      bff_client_id="bytecore-storefront", oauth_service_client_id="oauth-service-internal")
        reconcile_hydra_clients(hydra, self.spec, config)
        hydra.create_client.assert_not_called()
        hydra.update_client.assert_not_called()

    def test_dev_client_created_in_development(self):
        hydra = Mock()
        hydra.get_client.return_value = None
        config = Mock(dev_client_id="bytecore-web", dev_token_endpoint_enabled=True,
                      bff_client_id="bytecore-storefront", oauth_service_client_id="oauth-service-internal")
        reconcile_hydra_clients(hydra, self.spec, config)
        hydra.create_client.assert_called_once()


class SmtpMailerTest(unittest.TestCase):
    def test_plain_smtp_without_login_by_default(self):
        with patch("clients.mailer.smtplib.SMTP") as smtp_cls:
            smtp = MagicMock()
            smtp_cls.return_value.__enter__.return_value = smtp
            SmtpMailer("mailpit", 1025, "from@example.test").send("to@example.test", "s", "b")
        smtp.starttls.assert_not_called()
        smtp.login.assert_not_called()
        smtp.send_message.assert_called_once()

    def test_starttls_and_login(self):
        with patch("clients.mailer.smtplib.SMTP") as smtp_cls:
            smtp = MagicMock()
            smtp_cls.return_value.__enter__.return_value = smtp
            SmtpMailer("smtp.example.test", 587, "from@example.test", username="u", password="p",
                       starttls=True).send("to@example.test", "s", "b")
        smtp.starttls.assert_called_once()
        smtp.login.assert_called_once_with("u", "p")

    def test_implicit_tls_uses_smtp_ssl(self):
        with patch("clients.mailer.smtplib.SMTP_SSL") as ssl_cls, patch("clients.mailer.smtplib.SMTP") as plain:
            smtp = MagicMock()
            ssl_cls.return_value.__enter__.return_value = smtp
            SmtpMailer("smtp.example.test", 465, "from@example.test", implicit_tls=True).send("to@example.test", "s", "b")
        plain.assert_not_called()
        smtp.send_message.assert_called_once()


if __name__ == "__main__":
    unittest.main()
