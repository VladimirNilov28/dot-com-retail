import json
import unittest
from unittest.mock import MagicMock, Mock, call, patch

import requests

from api.bridge_server import make_bridge_handler
from clients.kratos import KratosClient, KratosUpstreamError
from test_auth_http_errors import AuthHttpErrorsTest
from test_login_consent import make_config

IDENTITY = "11111111-1111-4111-8111-111111111111"
SECRETS = ["$argon2id$private-hash", "private-subject", "otpauth://totp/private", "abcd-1234", "private-token"]


def identity(email="old@example.com", credentials=None):
    return {
        "id": IDENTITY, "schema_id": "customer-v1", "state": "active",
        "traits": {"email": email, "username": "customer", "dateOfBirth": "1990-01-01"},
        "verifiable_addresses": [{"via": "email", "value": email, "verified": True, "status": "completed"}],
        "metadata_public": None, "metadata_admin": {"spring_user_id": 7},
        "credentials": credentials or {},
    }


class AccountRoutesTest(unittest.TestCase):
    start_server = AuthHttpErrorsTest.start_server

    def setUp(self):
        self.config = make_config(auth_bridge_secret="fixture-bridge-secret-not-issued-32")
        self.kratos = Mock()
        self.spring = Mock()
        self.kratos.get_identity.return_value = identity()
        self.kratos.identity_ids_by_identifier.return_value = []
        self.spring.registration_availability.return_value = {"emailAvailable": True, "usernameAvailable": True}
        patcher = patch("api.bridge_server.login_consent.bff_session")
        self.session = patcher.start()
        self.addCleanup(patcher.stop)
        self.session.return_value = {"active": True, "role": "USER", "identity_id": IDENTITY}
        smtp = patch("clients.mailer.smtplib.SMTP")
        self.smtp_class = smtp.start()
        self.addCleanup(smtp.stop)
        self.smtp = MagicMock()
        self.smtp_class.return_value.__enter__.return_value = self.smtp
        self.connection = self.start_server(make_bridge_handler(self.config, Mock(), self.kratos, self.spring))

    def post(self, path, body, auth=True):
        headers = {"Authorization": "Bearer " + self.config.auth_bridge_secret} if auth else {}
        self.connection.request("POST", path, body=json.dumps(body), headers=headers)
        response = self.connection.getresponse()
        raw = response.read()
        return response.status, (json.loads(raw) if raw and response.getheader("Content-Type") == "application/json"
                                 else raw)

    def sent(self):
        return [(message["To"], message["Subject"], message.get_content())
                for message in (c.args[0] for c in self.smtp.send_message.call_args_list)]

    # --- common ---

    def test_routes_require_bridge_auth_and_active_customer(self):
        bodies = {
            "/internal/account/status": {"cookie": "c"},
            "/internal/account/notify": {"cookie": "c", "template": "step_up_code", "code": "123456"},
            "/internal/account/email": {"cookie": "c", "email": "new@example.com"},
        }
        for path, body in bodies.items():
            self.assertEqual(self.post(path, body, auth=False)[0], 403)
        self.session.assert_not_called()
        for session in ({"active": False}, {"active": True, "role": "ADMIN", "identity_id": IDENTITY}):
            self.session.return_value = session
            for path, body in bodies.items():
                self.assertEqual(self.post(path, body), (403, {"error": "forbidden"}))
        self.smtp.send_message.assert_not_called()
        self.spring.change_email.assert_not_called()
        self.kratos.update_identity_email.assert_not_called()

    def test_strict_keys_and_types_are_rejected_before_session(self):
        invalid = {
            "/internal/account/status": [{"cookie": "c", "extra": 1}, {"cookie": 5}, {}],
            "/internal/account/notify": [
                {"cookie": "c", "template": "unknown"},
                {"cookie": "c", "template": "step_up_code", "code": "12345"},
                {"cookie": "c", "template": "step_up_code", "code": "123456", "new_email": "x@example.com"},
                {"cookie": "c", "template": "email_change_code", "code": "123456"},
                {"cookie": "c", "template": "email_change_code", "code": "123456", "new_email": "a b@example.com"},
                {"cookie": "c", "template": "email_change_code", "code": "123456", "new_email": "a@@example.com"},
                {"cookie": "c", "template": "security_event", "event": "unknown"},
                {"cookie": "c", "template": "security_event", "event": "password_changed", "remaining": 1},
                {"cookie": "c", "template": "security_event", "event": "mfa_recovery_used", "remaining": 65},
                {"cookie": "c", "template": "security_event", "event": "mfa_recovery_used", "remaining": True},
                {"cookie": "c", "template": "email_changed", "new_email": "x@example.com"},
            ],
            "/internal/account/email": [{"cookie": "c"}, {"cookie": "c", "email": "x@example.com", "role": "USER"},
                                        {"cookie": "c", "email": "no-at-sign"},
                                        {"cookie": "c", "email": "x\u0000@example.com"}],
        }
        for path, bodies in invalid.items():
            for body in bodies:
                self.assertEqual(self.post(path, body)[0], 400, (path, body))
        self.session.assert_not_called()
        self.smtp.send_message.assert_not_called()

    # --- status ---

    def test_status_projects_credentials_without_secrets(self):
        self.kratos.get_identity_with_credentials.return_value = identity(credentials={
            "password": {"identifiers": ["old@example.com"], "config": {"hashed_password": SECRETS[0]}},
            "oidc": {"config": {"providers": [{"provider": "google", "subject": SECRETS[1],
                                               "initial_access_token": SECRETS[4]}]}},
            "totp": {"identifiers": [IDENTITY], "config": {"totp_url": SECRETS[2]}},
            "lookup_secret": {"config": {"recovery_codes": [
                {"code": SECRETS[3], "used_at": None}, {"code": "efgh-5678", "used_at": "2026-01-01T00:00:00Z"},
                {"code": "ijkl-9012", "used_at": "0001-01-01T00:00:00Z"}]}},
        })
        status, payload = self.post("/internal/account/status", {"cookie": "c"})
        self.assertEqual(status, 200)
        self.assertEqual(payload["credentials"], {"password": True, "google": True, "totp": True,
                                                  "lookup_secret": True})
        self.assertEqual(payload["recovery_codes_remaining"], 2)
        self.assertEqual(payload["profile"], {"email": "old@example.com", "email_verified": True,
                                              "username": "customer", "date_of_birth": "1990-01-01"})
        text = json.dumps(payload)
        for secret in SECRETS + ["efgh-5678", "ijkl-9012", "hashed_password", "totp_url", "subject"]:
            self.assertNotIn(secret, text)

    def test_status_treats_identifier_only_password_and_spent_codes_as_absent(self):
        self.kratos.get_identity_with_credentials.return_value = {
            **identity(credentials={
                "password": {"identifiers": ["old@example.com"], "config": {}},
                "lookup_secret": {"config": {"recovery_codes": [{"code": "x", "used_at": "2026-01-01T00:00:00Z"}]}},
            }),
            "verifiable_addresses": [{"via": "email", "value": "other@example.com", "verified": True}],
        }
        status, payload = self.post("/internal/account/status", {"cookie": "c"})
        self.assertEqual(status, 200)
        self.assertEqual(payload["credentials"], {"password": False, "google": False, "totp": False,
                                                  "lookup_secret": False})
        self.assertEqual(payload["recovery_codes_remaining"], 0)
        self.assertIs(payload["profile"]["email_verified"], False)

    def test_status_after_google_unlink_reports_disconnected_with_password_kept(self):
        # Kratos leaves a blank placeholder provider and no identifiers after unlinking.
        blank = {"initial_id_token": "", "initial_access_token": "", "initial_refresh_token": "",
                 "subject": "", "provider": ""}
        self.kratos.get_identity_with_credentials.return_value = identity(credentials={
            "password": {"identifiers": ["old@example.com"], "config": {"hashed_password": SECRETS[0]}},
            "oidc": {"identifiers": [], "config": {"providers": [blank]}},
        })
        status, payload = self.post("/internal/account/status", {"cookie": "c"})
        self.assertEqual(status, 200)
        self.assertEqual(payload["credentials"], {"password": True, "google": False, "totp": False,
                                                  "lookup_secret": False})

    def test_status_after_unlink_of_other_provider_entries_still_rejects_partial_blanks(self):
        self.kratos.get_identity_with_credentials.return_value = identity(credentials={
            "oidc": {"config": {"providers": [{"provider": "google", "subject": ""}]}}})
        self.assertEqual(self.post("/internal/account/status", {"cookie": "c"})[0], 502)

    def test_status_malformed_upstream_is_bad_gateway(self):
        self.kratos.get_identity_with_credentials.return_value = identity(credentials={
            "lookup_secret": {"config": {"recovery_codes": "invalid"}}})
        self.assertEqual(self.post("/internal/account/status", {"cookie": "c"})[0], 502)

    # --- notify ---

    def test_notify_chooses_recipient_server_side(self):
        self.assertEqual(self.post("/internal/account/notify",
                                   {"cookie": "c", "template": "step_up_code", "code": "123456"})[0], 204)
        self.assertEqual(self.post("/internal/account/notify", {"cookie": "c", "template": "email_change_code",
                                   "code": "654321", "new_email": " new@example.com "})[0], 204)
        self.assertEqual(self.post("/internal/account/notify", {"cookie": "c", "template": "security_event",
                                   "event": "mfa_recovery_used", "remaining": 3})[0], 204)
        sent = self.sent()
        self.assertEqual([to for to, _, _ in sent], ["old@example.com", "new@example.com", "old@example.com"])
        self.assertEqual(sent[0][1], "Your ByteCore security code")
        self.assertIn("123456", sent[0][2])
        self.assertIn("10 minutes", sent[0][2])
        self.assertIn("654321", sent[1][2])
        self.assertIn("15 minutes", sent[1][2])
        self.assertIn("3 unused recovery codes", sent[2][2])
        self.assertIn("UTC", sent[2][2])
        self.assertNotIn("http", "".join(body for _, _, body in sent))
        self.smtp_class.assert_called_with("mailpit", 1025, timeout=10)
        self.smtp.starttls.assert_not_called()

    def test_smtp_failure_is_bad_gateway_without_address_or_code_in_logs(self):
        self.smtp.send_message.side_effect = OSError("refused for old@example.com 123456")
        with self.assertLogs(level="INFO") as logs:
            status, _ = self.post("/internal/account/notify",
                                  {"cookie": "c", "template": "step_up_code", "code": "123456"})
        self.assertEqual(status, 502)
        output = "\n".join(logs.output)
        self.assertNotIn("old@example.com", output)
        self.assertNotIn("123456", output)

    # --- email change ---

    def test_email_change_updates_spring_then_kratos_and_notifies_old_address(self):
        status, payload = self.post("/internal/account/email", {"cookie": "c", "email": "New.Name@Example.com"})
        self.assertEqual((status, payload), (200, {"email": "New.Name@Example.com"}))
        self.spring.registration_availability.assert_called_once_with("New.Name@Example.com")
        self.kratos.identity_ids_by_identifier.assert_called_once_with("New.Name@Example.com")
        self.spring.change_email.assert_called_once_with(IDENTITY, "New.Name@Example.com")
        self.kratos.update_identity_email.assert_called_once_with(IDENTITY, "New.Name@Example.com")
        [(to, subject, body)] = self.sent()
        self.assertEqual(to, "old@example.com")
        self.assertIn("N***@E***.com", body)
        self.assertNotIn("New.Name@Example.com", body)
        self.assertIn("recover your account immediately", body)

    def test_email_change_rejects_unchanged_email(self):
        self.assertEqual(self.post("/internal/account/email", {"cookie": "c", "email": "OLD@example.com"})[0], 400)
        self.spring.change_email.assert_not_called()

    def test_email_change_conflicts(self):
        self.spring.registration_availability.return_value = {"emailAvailable": False, "usernameAvailable": True}
        self.assertEqual(self.post("/internal/account/email", {"cookie": "c", "email": "x@example.com"}),
                         (409, {"error": "conflict"}))
        self.spring.registration_availability.return_value = {"emailAvailable": True, "usernameAvailable": True}
        self.kratos.identity_ids_by_identifier.return_value = ["22222222-2222-4222-8222-222222222222"]
        self.assertEqual(self.post("/internal/account/email", {"cookie": "c", "email": "x@example.com"}),
                         (409, {"error": "conflict"}))
        self.kratos.identity_ids_by_identifier.return_value = []
        conflict = requests.HTTPError(response=Mock(status_code=409))
        self.spring.change_email.side_effect = conflict
        self.assertEqual(self.post("/internal/account/email", {"cookie": "c", "email": "x@example.com"}),
                         (409, {"error": "conflict"}))
        self.kratos.update_identity_email.assert_not_called()
        self.smtp.send_message.assert_not_called()

    def test_kratos_failure_reverts_spring_and_fails(self):
        self.kratos.update_identity_email.side_effect = [KratosUpstreamError("x"), None]
        status, _ = self.post("/internal/account/email", {"cookie": "c", "email": "new@example.com"})
        self.assertEqual(status, 502)
        self.assertEqual(self.spring.change_email.call_args_list,
                         [call(IDENTITY, "new@example.com"), call(IDENTITY, "old@example.com")])
        self.assertEqual(self.kratos.update_identity_email.call_args_list,
                         [call(IDENTITY, "new@example.com"), call(IDENTITY, "old@example.com")])
        self.smtp.send_message.assert_not_called()

    def test_old_address_notification_failure_does_not_fail_change(self):
        self.smtp.send_message.side_effect = OSError("down")
        with self.assertLogs(level="ERROR") as logs:
            status, payload = self.post("/internal/account/email", {"cookie": "c", "email": "new@example.com"})
        self.assertEqual((status, payload), (200, {"email": "new@example.com"}))
        self.assertNotIn("@example.com", "\n".join(logs.output))


class KratosEmailUpdateTest(unittest.TestCase):
    def setUp(self):
        self.client = KratosClient("http://kratos:4434", "http://kratos:4433")

    @patch("clients.kratos.requests.patch")
    @patch("clients.kratos.requests.put")
    @patch("clients.kratos.requests.get")
    def test_put_preserves_other_fields_then_patches_new_address_verified(self, get, put, patch_):
        get.return_value = Mock(json=Mock(return_value=identity()))
        updated = identity("new@example.com")
        updated["verifiable_addresses"] = [{"via": "email", "value": "new@example.com", "verified": False,
                                            "status": "pending"}]
        put.return_value = Mock(json=Mock(return_value=updated))
        patch_.return_value = Mock(json=Mock(return_value=identity("new@example.com")))
        self.client.update_identity_email(IDENTITY, "new@example.com")
        sent = put.call_args.kwargs["json"]
        self.assertEqual(sent, {"schema_id": "customer-v1", "state": "active",
                                "traits": {"email": "new@example.com", "username": "customer",
                                           "dateOfBirth": "1990-01-01"},
                                "metadata_public": None, "metadata_admin": {"spring_user_id": 7}})
        self.assertEqual(patch_.call_args.kwargs["json"], [
            {"op": "replace", "path": "/verifiable_addresses/0/verified", "value": True},
            {"op": "replace", "path": "/verifiable_addresses/0/status", "value": "completed"}])

    @patch("clients.kratos.requests.patch")
    @patch("clients.kratos.requests.put")
    @patch("clients.kratos.requests.get")
    def test_already_applied_change_is_idempotent(self, get, put, patch_):
        get.return_value = Mock(json=Mock(return_value=identity("new@example.com")))
        self.client.update_identity_email(IDENTITY, "new@example.com")
        put.assert_not_called()
        patch_.assert_not_called()

    @patch("clients.kratos.requests.patch")
    @patch("clients.kratos.requests.put")
    @patch("clients.kratos.requests.get")
    def test_unverified_after_patch_fails(self, get, put, patch_):
        get.return_value = Mock(json=Mock(return_value=identity()))
        pending = identity("new@example.com")
        pending["verifiable_addresses"][0]["verified"] = False
        put.return_value = Mock(json=Mock(return_value=pending))
        patch_.return_value = Mock(json=Mock(return_value=pending))
        with self.assertRaises(KratosUpstreamError):
            self.client.update_identity_email(IDENTITY, "new@example.com")
