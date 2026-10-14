import unittest
from unittest.mock import Mock, patch

from clients.kratos import KratosClient, KratosUpstreamError
from services.account import credential_projection
from services.login_consent import BridgeError


class GoogleConnectionTest(unittest.TestCase):
    def setUp(self):
        self.client = KratosClient("http://kratos:4434", "http://kratos:4433")
        self.identity_id = "11111111-1111-4111-8111-111111111111"

    def test_only_google_stable_subject_produces_connected_boolean(self):
        for providers, expected in [
            ([], False),
            ([{"provider": "google", "subject": "stable-subject", "initial_access_token": "private-fixture"}], True),
            ([{"provider": "other", "subject": "stable-subject"}], False),
        ]:
            identity = {"id": self.identity_id, "credentials": {"oidc": {"config": {"providers": providers}}}}
            self.assertIs(credential_projection(identity)[0]["google"], expected)
        self.assertIs(credential_projection({"id": self.identity_id})[0]["google"], False)

    def test_malformed_credentials_are_not_a_disconnected_success(self):
        for credentials in [
            [],
            {"oidc": {"config": {}}},
            {"oidc": {"config": {"providers": [{"provider": "google", "subject": ""}]}}},
            {"oidc": "invalid"},
        ]:
            with self.assertRaises(BridgeError):
                credential_projection({"id": self.identity_id, "credentials": credentials})

    @patch("clients.kratos.requests.get")
    def test_identity_with_credentials_requests_all_account_types_and_rejects_foreign_identity(self, get):
        response = Mock()
        get.return_value = response
        response.json.return_value = {"id": self.identity_id}
        self.assertEqual(self.client.get_identity_with_credentials(self.identity_id), {"id": self.identity_id})
        self.assertEqual(get.call_args.kwargs["params"], [
            ("include_credential", "oidc"), ("include_credential", "password"),
            ("include_credential", "totp"), ("include_credential", "lookup_secret")])
        response.json.return_value = {"id": "22222222-2222-4222-8222-222222222222"}
        with self.assertRaises(KratosUpstreamError):
            self.client.get_identity_with_credentials(self.identity_id)

    @patch("clients.kratos.requests.get")
    def test_untrusted_identifier_rejected_before_request(self, get):
        for call in (self.client.get_identity_with_credentials, lambda value: self.client.update_identity_email(
                value, "new@example.com")):
            with self.assertRaises(ValueError):
                call("../../other")
        get.assert_not_called()
