import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import ConfigError, load_config  # noqa: E402


class LoadConfigTest(unittest.TestCase):
    def _env(self, **overrides):
        env = {
            "ADMIN_USERNAME": "admin",
            "ADMIN_PASSWORD": "admin-dev-password",
            "OAUTH_SERVICE_CLIENT_SECRET": "oauth-service-dev-secret",
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

    def test_derives_admin_email_from_username(self):
        with patch.dict(os.environ, self._env(ADMIN_USERNAME="admin"), clear=True):
            config = load_config()

        self.assertEqual(config.admin_username, "admin")
        self.assertEqual(config.admin_email, "admin@bytecore.ee")

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
