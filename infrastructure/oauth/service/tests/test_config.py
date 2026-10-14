import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import ConfigError, load_config  # noqa: E402


class LoadConfigTest(unittest.TestCase):
    def _env(self, **overrides):
        env = {
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
            "AUTH_DATABASE_URL": "postgres://unit:unit@localhost/unit",
        }
        env.update(overrides)
        return env

    def test_admin_username_required(self):
        with patch.dict(os.environ, self._env(ADMIN_USERNAME=""), clear=True):
            with self.assertRaises(ConfigError):
                load_config()

    def test_oauth_service_client_secret_required(self):
        with patch.dict(os.environ, self._env(OAUTH_SERVICE_CLIENT_SECRET=""), clear=True):
            with self.assertRaises(ConfigError):
                load_config()

    def test_admin_password_still_required(self):
        with patch.dict(os.environ, self._env(ADMIN_PASSWORD=""), clear=True):
            with self.assertRaises(ConfigError):
                load_config()

    def test_browser_secrets_fail_closed_for_placeholders(self):
        for name in ("BFF_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "CAPTCHA_SECRET"):
            with self.subTest(name=name), patch.dict(
                os.environ, self._env(**{name: "replace-with-independent-private-secret"}), clear=True
            ):
                with self.assertRaises(ConfigError):
                    load_config()

    def test_derives_admin_email_from_username(self):
        with patch.dict(os.environ, self._env(ADMIN_USERNAME="admin"), clear=True):
            config = load_config()

        self.assertEqual(config.admin_username, "admin")
        self.assertEqual(config.admin_email, "admin@bytecore.ee")

    def test_fixed_bff_dispatch_callback_cannot_be_an_untrusted_or_insecure_redirect(self):
        for uri in ("http://remote.example.com/auth/callback",
                    "https://user:password@shop.example.com/auth/callback",
                    "https://shop.example.com/another-route",
                    "https://shop.example.com/auth/callback?next=https://other.example",
                    "https://shop.example.com/auth/callback#fragment"):
            with self.subTest(uri=uri), patch.dict(os.environ, self._env(BFF_REDIRECT_URI=uri), clear=True):
                with self.assertRaises(ConfigError):
                    load_config()

    def test_no_admin_email_env_var_is_read(self):
        # ADMIN_EMAIL is no longer a supported input — only ADMIN_USERNAME
        # derives the email, so a stray ADMIN_EMAIL must not affect it.
        with patch.dict(
            os.environ,
            self._env(ADMIN_USERNAME="admin", ADMIN_EMAIL="ignored@example.com"),
            clear=True,
        ):
            config = load_config()

        self.assertEqual(config.admin_email, "admin@bytecore.ee")


if __name__ == "__main__":
    unittest.main()
