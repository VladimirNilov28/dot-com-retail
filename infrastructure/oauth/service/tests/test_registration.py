import os
import sys
import unittest
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import Mock, patch
from uuid import uuid4

import requests
from altcha import Payload, create_challenge, solve_challenge

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
from services.registration import RegistrationRejected, RegistrationService  # noqa: E402


class ReceiptConnection:
    def __init__(self, row):
        self.row = row

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def execute(self, sql, args):
        if sql.startswith("UPDATE captcha_challenges SET used_trigger"):
            self.row["used_trigger"], self.row["payload_digest"] = args[:2]
        if sql.startswith("UPDATE captcha_challenges SET reservation"):
            self.row["reservation"] = args[0].obj
        return SimpleNamespace(fetchone=lambda: self.row)


class RegistrationSecurityTest(unittest.TestCase):
    def setUp(self):
        self.secret = "unit-fixture-secret-" * 3
        self.flow = uuid4()
        self.trigger = uuid4()
        self.spring = Mock()
        self.reservation = {"id": str(uuid4())}
        self.spring.reserve_registration.return_value = self.reservation
        self.service = RegistrationService(SimpleNamespace(captcha_secret=self.secret), self.spring)
        challenge = create_challenge(
            "PBKDF2/SHA-256", 1, counter=1,
            expires_at=datetime.now(timezone.utc) + timedelta(minutes=5),
            data={"flow_id": str(self.flow), "purpose": "registration"}, hmac_secret=self.secret,
        )
        proof = Payload(challenge, solve_challenge(challenge)).to_base64()
        self.row = {
            "flow_id": self.flow, "challenge": challenge.to_dict(),
            "expires_at": datetime.now(timezone.utc) + timedelta(minutes=5),
            "used_trigger": None, "payload_digest": None, "reservation": None,
        }
        self.body = {
            "schema_id": "customer-v1", "flow_type": "browser", "flow_id": str(self.flow),
            "traits": {"username": "unitcustomer", "email": "unit@example.com", "dateOfBirth": "1990-06-01"},
            "altcha": proof, "expires_at": self.row["expires_at"].isoformat(),
        }
        self.connection = patch.object(self.service, "_connection", return_value=ReceiptConnection(self.row))
        self.connection.start()
        self.addCleanup(self.connection.stop)

    def submit(self, body=None, trigger=None):
        return self.service.pre_persist(body or self.body, str(trigger or self.trigger))

    def test_official_proof_and_identical_authenticated_retry_reserve_once(self):
        self.assertEqual(self.submit(), self.submit())
        self.spring.reserve_registration.assert_called_once()
        self.assertEqual(
            self.submit()["identity"]["metadata_admin"]["bytecore_registration"]["reservation_id"],
            self.reservation["id"],
        )

    def test_interrupted_upstream_delivery_can_retry_but_another_trigger_cannot(self):
        self.spring.reserve_registration.side_effect = [requests.ConnectionError("fixture outage"), self.reservation]
        with self.assertRaises(requests.ConnectionError):
            self.submit()
        with self.assertRaises(RegistrationRejected):
            self.submit(trigger=uuid4())
        self.submit()
        self.assertEqual(self.spring.reserve_registration.call_count, 2)

    def test_foreign_flow_expiry_and_malformed_proof_fail_closed(self):
        for field, value in [
            ("flow_id", str(uuid4())), ("schema_id", "default"), ("flow_type", "api"), ("altcha", "invalid"),
        ]:
            with self.subTest(field=field), self.assertRaises((RegistrationRejected, ValueError)):
                self.submit({**self.body, field: value})
        self.row["expires_at"] = datetime.now(timezone.utc) - timedelta(seconds=1)
        with self.assertRaises(RegistrationRejected):
            self.submit()
        self.spring.reserve_registration.assert_not_called()

    def test_consumed_proof_cannot_be_reused_with_changed_traits_or_trigger(self):
        self.submit()
        changed = {**self.body, "traits": {**self.body["traits"], "username": "another"}}
        with self.assertRaises(RegistrationRejected):
            self.submit(changed)
        with self.assertRaises(RegistrationRejected):
            self.submit(trigger=uuid4())
        self.spring.reserve_registration.assert_called_once()

    def test_additional_role_trait_is_not_a_registration_claim(self):
        with self.assertRaises(RegistrationRejected):
            self.submit({**self.body, "traits": {**self.body["traits"], "role": "ADMIN"}})
        self.spring.reserve_registration.assert_not_called()
