from pathlib import Path
import unittest

import yaml


OAUTH = Path(__file__).resolve().parents[2]


class PasswordConfigurationTest(unittest.TestCase):
    def test_base_policy_keeps_breach_and_similarity_protection_and_minimum(self):
        config = yaml.safe_load((OAUTH / "kratos" / "kratos.yml").read_text())
        password = config["selfservice"]["methods"]["password"]
        self.assertTrue(password["enabled"])
        self.assertEqual(password["config"]["min_password_length"], 8)
        self.assertTrue(password["config"]["identifier_similarity_check_enabled"])
        self.assertTrue(password["config"]["haveibeenpwned_enabled"])

    def test_only_explicit_local_compose_uses_supported_kratos_breach_override(self):
        compose = yaml.safe_load((OAUTH.parent / "compose.yml").read_text())
        key = "SELFSERVICE_METHODS_PASSWORD_CONFIG_HAVEIBEENPWNED_ENABLED"
        self.assertEqual(compose["services"]["kratos"]["environment"][key], "false")
        for name, service in compose["services"].items():
            if name != "kratos":
                self.assertNotIn(key, service.get("environment", {}))
        environment = compose["services"]["kratos"]["environment"]
        self.assertNotIn("SELFSERVICE_METHODS_PASSWORD_CONFIG_MIN_PASSWORD_LENGTH", environment)
        self.assertNotIn("SELFSERVICE_METHODS_PASSWORD_CONFIG_IDENTIFIER_SIMILARITY_CHECK_ENABLED", environment)
        self.assertNotIn("NODE_ENV", environment)

    def test_runtime_renderer_preserves_base_password_policy(self):
        entrypoint = (OAUTH / "kratos" / "entrypoint.sh").read_text()
        self.assertNotIn("haveibeenpwned_enabled", entrypoint)
        self.assertIn("/home/ory/bytecore-runtime", entrypoint)
        self.assertIn('exec kratos "$@" --config "$runtime/bytecore-registration.json"', entrypoint)


if __name__ == "__main__":
    unittest.main()
