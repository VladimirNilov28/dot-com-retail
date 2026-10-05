import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from models import InvalidRequestError, LogoutRequest  # noqa: E402


class LogoutRequestTest(unittest.TestCase):
    def test_parses_refresh_token_only(self):
        request = LogoutRequest.from_json_bytes(b'{"refresh_token": "rt-value"}')

        self.assertEqual(request.refresh_token, "rt-value")
        self.assertIsNone(request.kratos_session_token)

    def test_parses_refresh_token_and_kratos_session_token(self):
        request = LogoutRequest.from_json_bytes(
            b'{"refresh_token": "rt-value", "kratos_session_token": "st-value"}'
        )

        self.assertEqual(request.refresh_token, "rt-value")
        self.assertEqual(request.kratos_session_token, "st-value")

    def test_missing_refresh_token_raises(self):
        with self.assertRaises(InvalidRequestError):
            LogoutRequest.from_json_bytes(b"{}")

    def test_blank_refresh_token_raises(self):
        with self.assertRaises(InvalidRequestError):
            LogoutRequest.from_json_bytes(b'{"refresh_token": "   "}')

    def test_invalid_json_raises(self):
        with self.assertRaises(InvalidRequestError):
            LogoutRequest.from_json_bytes(b"not json")

    def test_non_object_json_raises(self):
        with self.assertRaises(InvalidRequestError):
            LogoutRequest.from_json_bytes(b"[1, 2, 3]")

    def test_non_string_kratos_session_token_raises(self):
        with self.assertRaises(InvalidRequestError):
            LogoutRequest.from_json_bytes(b'{"refresh_token": "rt-value", "kratos_session_token": 123}')


if __name__ == "__main__":
    unittest.main()
