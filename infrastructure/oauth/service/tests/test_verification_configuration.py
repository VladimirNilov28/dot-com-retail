from pathlib import Path
import unittest

import yaml


KRATOS = Path(__file__).resolve().parents[2] / "kratos"


class VerificationConfigurationTest(unittest.TestCase):
    def test_code_method_and_lifetime_are_preserved(self):
        config = yaml.safe_load((KRATOS / "kratos.yml").read_text())
        verification = config["selfservice"]["flows"]["verification"]
        self.assertEqual(verification["use"], "code")
        self.assertEqual(verification["lifespan"], "15m")
        self.assertEqual(verification["ui_url"], "http://127.0.0.1:3000/verify")

    def test_real_templates_use_bound_storefront_fragment_not_provider_get(self):
        config = yaml.safe_load((KRATOS / "kratos.yml").read_text())
        body = config["courier"]["templates"]["verification_code"]["valid"]["email"]["body"]
        for name in ("html", "plaintext"):
            filename = body[name].removeprefix("file:///etc/kratos/")
            template = (KRATOS / filename).read_text()
            self.assertIn("http://127.0.0.1:3000/verify?flow={{ $flow }}#code={{ .VerificationCode }}", template)
            self.assertNotIn("href=\"{{ .VerificationURL }}\"", template)
            self.assertIn("latest email", template)
            self.assertIn("browser where you registered", template)
            self.assertIn(".ExpiresInMinutes", template)
        self.assertIn("COPY templates /etc/kratos/templates", (KRATOS / "Dockerfile").read_text())


if __name__ == "__main__":
    unittest.main()
