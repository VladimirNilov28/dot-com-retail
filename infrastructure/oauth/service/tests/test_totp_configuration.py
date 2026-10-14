import json
from pathlib import Path
import unittest

import yaml


OAUTH = Path(__file__).resolve().parents[2]


class TotpConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.config = yaml.safe_load((OAUTH / "kratos" / "kratos.yml").read_text())

    def test_native_totp_and_lookup_secrets_are_enabled(self):
        methods = self.config["selfservice"]["methods"]
        self.assertTrue(methods.get("totp", {}).get("enabled"), "Kratos TOTP must be enabled")
        self.assertEqual(methods["totp"].get("config", {}).get("issuer"), "Bytecore Retail")
        self.assertTrue(methods.get("lookup_secret", {}).get("enabled"), "Kratos lookup codes must be enabled")

    def test_optional_second_factors_require_highest_available_assurance(self):
        self.assertEqual(self.config.get("session", {}).get("whoami", {}).get("required_aal"), "highest_available")
        settings = self.config["selfservice"]["flows"]["settings"]
        self.assertEqual(settings.get("required_aal"), "highest_available")
        self.assertEqual(settings.get("privileged_session_max_age"), "15m")

    def test_email_is_native_totp_account_name_without_domain_role_data(self):
        schema = json.loads((OAUTH / "kratos" / "identity.schema.json").read_text())
        traits = schema["properties"]["traits"]["properties"]
        credentials = traits["email"]["ory.sh/kratos"]["credentials"]
        self.assertTrue(credentials.get("totp", {}).get("account_name"))
        self.assertEqual(set(traits), {"email"})

    def test_kratos_logging_does_not_enable_sensitive_values(self):
        self.assertIn(self.config["log"]["level"], ("info", "warn", "error"))
        self.assertFalse(self.config["log"].get("leak_sensitive_values", False))

    def test_admin_and_mail_ports_are_loopback_only(self):
        compose = yaml.safe_load((OAUTH.parent / "compose.yml").read_text())
        for service, port in (("hydra", 4445), ("kratos", 4434), ("mailpit", 8025), ("mailpit", 1025)):
            with self.subTest(service=service, port=port):
                ports = compose["services"][service]["ports"]
                self.assertIn(f"127.0.0.1:{port}:{port}", ports)
                self.assertNotIn(f"{port}:{port}", ports)


if __name__ == "__main__":
    unittest.main()
