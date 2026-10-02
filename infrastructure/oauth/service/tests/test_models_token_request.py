import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from models import InvalidRequestError, TokenRequest  # noqa: E402


class TokenRequestTest(unittest.TestCase):
    def test_existing_password_mode_is_preserved(self):
        request = TokenRequest.from_json_bytes(b'{"email":" user@example.com ","password":"fixture"}')
        self.assertEqual(request.email, "user@example.com")
        self.assertEqual(request.password, "fixture")
        self.assertIsNone(getattr(request, "kratos_session_token", None))

    def test_native_session_mode_needs_no_password(self):
        request = TokenRequest.from_json_bytes(b'{"kratos_session_token":"completed-aal2-session"}')
        self.assertEqual(request.kratos_session_token, "completed-aal2-session")
        self.assertIsNone(request.email)
        self.assertIsNone(request.password)

    def test_mixed_or_incomplete_credentials_are_rejected(self):
        cases = (
            {},
            {"email": "user@example.com"},
            {"password": "fixture"},
            {"email": "user@example.com", "password": ""},
            {"email": 42, "password": "fixture"},
            {"email": "user@example.com", "password": 42},
            {"kratos_session_token": ""},
            {"kratos_session_token": " "},
            {"kratos_session_token": 42},
            {"kratos_session_token": None},
            {"kratos_session_token": "fixture", "email": "user@example.com"},
            {"kratos_session_token": "fixture", "password": "fixture"},
            {"kratos_session_token": "fixture", "email": "user@example.com", "password": "fixture"},
        )
        for payload in cases:
            with self.subTest(fields=list(payload)):
                with self.assertRaises(InvalidRequestError):
                    TokenRequest.from_json_bytes(json.dumps(payload).encode())

    def test_non_object_and_malformed_json_are_rejected(self):
        for body in (b"[]", b"null", b'"fixture"', b"{"):
            with self.subTest(body=body):
                with self.assertRaises(InvalidRequestError):
                    TokenRequest.from_json_bytes(body)


if __name__ == "__main__":
    unittest.main()
