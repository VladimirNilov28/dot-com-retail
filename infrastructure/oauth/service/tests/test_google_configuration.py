import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock
from uuid import uuid4

import _jsonnet
import yaml

from services.login_consent import resolve_canonical_user, UnverifiedIdentityError
from services.registration import RegistrationRejected, RegistrationService


KRATOS = Path(__file__).resolve().parents[2] / "kratos"


class GoogleConfigurationTest(unittest.TestCase):
    def render(self, **environment):
        config = yaml.safe_load((KRATOS / "kratos.yml").read_text())
        variables = {key: value for key, value in os.environ.items() if not key.startswith("GOOGLE_")}
        variables.update(environment)
        return subprocess.run(
            ["jq", "-f", str(KRATOS / "google-config.jq")],
            input=json.dumps(config), text=True, capture_output=True, env=variables,
        )

    def test_disabled_without_credentials(self):
        result = self.render()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["selfservice"]["methods"]["oidc"], {"enabled": False})

    def test_incomplete_or_placeholder_credentials_fail_closed(self):
        for environment in (
            {"GOOGLE_CLIENT_ID": "client"},
            {"GOOGLE_CLIENT_SECRET": "secret"},
            {"GOOGLE_CLIENT_ID": "client", "GOOGLE_CLIENT_SECRET": "replace-with-secret"},
            {"GOOGLE_CLIENT_ID": " ", "GOOGLE_CLIENT_SECRET": "secret"},
        ):
            with self.subTest(environment=environment):
                self.assertNotEqual(self.render(**environment).returncode, 0)

    def test_enabled_provider_uses_bff_origin_and_preserves_native_hooks(self):
        result = self.render(GOOGLE_CLIENT_ID="test-client", GOOGLE_CLIENT_SECRET="test-secret")
        self.assertEqual(result.returncode, 0, result.stderr)
        config = json.loads(result.stdout)["selfservice"]
        oidc = config["methods"]["oidc"]
        self.assertTrue(oidc["enabled"])
        self.assertEqual(oidc["config"]["base_redirect_uri"], "http://127.0.0.1:3000")
        provider = oidc["config"]["providers"][0]
        self.assertEqual(provider["id"], "google")
        self.assertEqual(provider["pkce"], "never")
        self.assertEqual(provider["account_linking_mode"], "confirm_with_existing_credential")
        native = config["flows"]["registration"]["after"]["password"]["hooks"]
        social = config["flows"]["registration"]["after"]["oidc"]["hooks"]
        self.assertEqual(native[0]["config"]["body"], "file:///etc/kratos/registration-hook.jsonnet")
        self.assertEqual(social[0]["config"]["url"], native[0]["config"]["url"])
        self.assertTrue(social[0]["config"]["can_interrupt"])
        self.assertEqual(social[1], native[1])
        self.assertEqual(social[2], {"hook": "session"})

    def test_callback_base_is_an_origin_not_an_arbitrary_redirect(self):
        for base in ("http://evil.example", "https://user@evil.example", "https://example.com/auth/social-callback",
                     "https://example.com?next=evil", "http://127.0.0.1:4433"):
            with self.subTest(base=base):
                self.assertNotEqual(self.render(
                    GOOGLE_CLIENT_ID="client", GOOGLE_CLIENT_SECRET="secret", GOOGLE_CALLBACK_BASE_URL=base,
                ).returncode, 0)
        result = self.render(
            GOOGLE_CLIENT_ID="client", GOOGLE_CLIENT_SECRET="secret",
            GOOGLE_CALLBACK_BASE_URL="https://shop.example.com",
        )
        self.assertEqual(result.returncode, 0, result.stderr)


class GoogleMapperTest(unittest.TestCase):
    def map(self, **overrides):
        claims = {"sub": "provider-subject", "email": "customer@example.com", "email_verified": True}
        claims.update(overrides)
        return json.loads(_jsonnet.evaluate_file(
            str(KRATOS / "google-mapper.jsonnet"), ext_codes={"claims": json.dumps(claims)},
        ))

    def test_maps_only_verified_email_not_canonical_identity_or_role(self):
        identity = self.map(
            username="injected", dateOfBirth="1970-01-01", spring_user_id=1, role="ADMIN",
        )["identity"]
        self.assertEqual(identity["traits"], {"email": "customer@example.com"})
        self.assertEqual(identity["verified_addresses"], [{"via": "email", "value": "customer@example.com"}])
        self.assertEqual(identity["metadata_admin"], {"bytecore_oidc": {
            "provider": "google", "subject": "provider-subject", "email": "customer@example.com",
        }})

    def test_invalid_provider_claims_do_not_create_a_verified_identity(self):
        for overrides in (
            {"email_verified": False}, {"email_verified": "true"}, {"sub": ""},
            {"email": ""}, {"sub": None}, {"email": None},
        ):
            with self.subTest(overrides=overrides), self.assertRaises(RuntimeError):
                self.map(**overrides)

    def hook(self, identity):
        # Match Identity.MarshalJSON and the verified-address application in
        # Kratos v26.2.0, not the raw mapper result (which includes private metadata).
        public_identity = {
            "schema_id": identity["schema_id"],
            "traits": identity["traits"],
            "verifiable_addresses": identity.get("verifiable_addresses", [
                {**address, "verified": True, "status": "completed"}
                for address in identity.get("verified_addresses", [])
            ]),
        }
        context = {
            "identity": public_identity,
            "flow": {
                "id": "flow", "type": "browser", "expires_at": "expiry",
                "transient_payload": {"altcha": "browser-proof"},
            },
        }
        return json.loads(_jsonnet.evaluate_snippet(
            "google-hook-test",
            "(%s)(%s)" % ((KRATOS / "google-registration-hook.jsonnet").read_text(), json.dumps(context)),
            import_callback=self.reject_hook_import,
        ))

    @staticmethod
    def reject_hook_import(directory, path):
        raise RuntimeError("Kratos webhook imports are unavailable")

    def test_onboarding_requires_real_user_fields_and_retains_captcha(self):
        identity = self.map()["identity"]
        identity["schema_id"] = "customer-v1"
        body = self.hook(identity)
        self.assertIsNone(body["traits"]["username"])
        self.assertIsNone(body["traits"]["dateOfBirth"])
        self.assertEqual(body["altcha"], "browser-proof")
        identity["traits"].update(username="chosen-name", dateOfBirth="2000-01-02")
        self.assertEqual(self.hook(identity)["traits"], identity["traits"])

    def test_social_hook_cannot_bypass_native_captcha_or_required_fields(self):
        identity = self.map()["identity"]
        identity["schema_id"] = "customer-v1"
        body = self.hook(identity)
        body["flow_id"] = str(uuid4())
        spring = Mock()
        registration = RegistrationService(Mock(), spring)
        with self.assertRaises(RegistrationRejected):
            registration.pre_persist(body, str(uuid4()))
        body["traits"].update(username="chosen-name", dateOfBirth="2000-01-02")
        body["altcha"] = None
        with self.assertRaises(RegistrationRejected):
            registration.pre_persist(body, str(uuid4()))
        spring.reserve_registration.assert_not_called()

    def test_public_hook_context_rejects_unverified_missing_duplicate_or_substituted_email(self):
        for addresses in (
            [],
            [{"via": "email", "value": "customer@example.com", "verified": False, "status": "pending"}],
            [{"via": "email", "value": "other@example.com", "verified": True, "status": "completed"}],
            [{"via": "email", "value": "customer@example.com", "verified": True, "status": "completed"}] * 2,
        ):
            identity = self.map()["identity"]
            identity["schema_id"] = "customer-v1"
            identity["verifiable_addresses"] = addresses
            with self.subTest(addresses=addresses), self.assertRaises(RuntimeError):
                self.hook(identity)

    def test_real_webhook_projection_needs_no_private_metadata_and_preserves_profile_and_captcha(self):
        identity = self.map()["identity"]
        identity["schema_id"] = "customer-v1"
        identity["traits"].update(username="chosen-name", dateOfBirth="2000-01-02")
        identity.pop("metadata_admin")
        body = self.hook(identity)
        self.assertEqual(body["traits"], identity["traits"])
        self.assertEqual(body["altcha"], "browser-proof")
        identity["traits"]["email"] = "substituted@example.com"
        with self.assertRaises(RuntimeError):
            self.hook(identity)

    def test_self_contained_google_hook_matches_native_registration_payload_with_imports_disabled(self):
        identity = self.map()["identity"]
        identity["schema_id"] = "customer-v1"
        identity["traits"].update(username="chosen-name", dateOfBirth="2000-01-02")
        context = {"identity": {"schema_id": identity["schema_id"], "traits": identity["traits"]},
            "flow": {"id": "flow", "type": "browser", "expires_at": "expiry",
                "transient_payload": {"altcha": "browser-proof"}}}
        for payload in [{"altcha": "browser-proof"}, {}, None]:
            context["flow"]["transient_payload"] = payload
            native = json.loads(_jsonnet.evaluate_snippet(
                "native-hook-test",
                "(%s)(%s)" % ((KRATOS / "registration-hook.jsonnet").read_text(), json.dumps(context)),
                import_callback=self.reject_hook_import,
            ))
            public_identity = {**context["identity"], "verifiable_addresses": [{
                "via": "email", "value": identity["traits"]["email"], "verified": True, "status": "completed"}]}
            google = json.loads(_jsonnet.evaluate_snippet(
                "google-hook-test", "(%s)(%s)" % ((KRATOS / "google-registration-hook.jsonnet").read_text(),
                    json.dumps({**context, "identity": public_identity})),
                import_callback=self.reject_hook_import,
            ))
            self.assertEqual(google, native)

    def test_provider_claims_cannot_supply_canonical_roles_or_adopt_by_email(self):
        kratos = Mock()
        spring = Mock()
        identity = self.map(role="ADMIN", spring_user_id=99)["identity"]
        identity.update(id="identity", schema_id="customer-v1")
        identity["traits"].update(username="chosen-name", dateOfBirth="2000-01-02")
        identity["verifiable_addresses"] = [{"via": "email", "value": "customer@example.com", "verified": True}]
        kratos.get_identity.return_value = identity
        spring.finalize_registration.return_value = {"id": 7, "role": "USER"}
        result = resolve_canonical_user(kratos, spring, "identity")
        self.assertEqual(result, {"id": 7, "role": "USER"})
        spring.finalize_registration.assert_called_once_with("identity")
        spring.provision_user.assert_not_called()
        spring.resolve_user.assert_not_called()
        identity["verifiable_addresses"][0]["verified"] = False
        with self.assertRaises(UnverifiedIdentityError):
            resolve_canonical_user(kratos, spring, "identity")


if __name__ == "__main__":
    unittest.main()
