import unittest
from unittest.mock import Mock, patch

from clients.kratos import KratosClient, KratosUpstreamError


class GoogleConnectionTest(unittest.TestCase):
    def setUp(self):
        self.client = KratosClient("http://kratos:4434", "http://kratos:4433")
        self.identity_id = "11111111-1111-4111-8111-111111111111"

    @patch("clients.kratos.requests.get")
    def test_only_google_stable_subject_produces_connected_boolean(self, get):
        response = Mock()
        get.return_value = response
        for providers, expected in [
            ([], False),
            ([{"provider": "google", "subject": "stable-subject", "initial_access_token": "private-fixture"}], True),
            ([{"provider": "other", "subject": "stable-subject"}], False),
        ]:
            response.json.return_value = {"id": self.identity_id,
                "credentials": {"oidc": {"config": {"providers": providers}}}}
            self.assertIs(self.client.google_connected(self.identity_id), expected)
        self.assertEqual(get.call_args.kwargs["params"], {"include_credential": "oidc"})
        response.json.return_value = {"id": self.identity_id}
        self.assertIs(self.client.google_connected(self.identity_id), False)

    @patch("clients.kratos.requests.get")
    def test_foreign_or_malformed_identity_is_not_a_disconnected_success(self, get):
        response = Mock()
        get.return_value = response
        for identity in [
            {"id": "22222222-2222-4222-8222-222222222222"},
            {"id": self.identity_id, "credentials": []},
            {"id": self.identity_id, "credentials": {"oidc": {"config": {}}}},
            {"id": self.identity_id, "credentials": {"oidc": {"config": {"providers": [
                {"provider": "google", "subject": ""}]}}}},
        ]:
            response.json.return_value = identity
            with self.assertRaises(KratosUpstreamError):
                self.client.google_connected(self.identity_id)

    @patch("clients.kratos.requests.get")
    def test_untrusted_identifier_rejected_before_request(self, get):
        with self.assertRaises(ValueError):
            self.client.google_connected("../../other")
        get.assert_not_called()
