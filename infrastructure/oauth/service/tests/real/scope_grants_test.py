"""Opt-in local-source consent check against live Hydra/Kratos/Spring.

Creates a disposable USER through Spring registration. The test invokes the
modified consent handler directly without restarting the deployed bridge. It
uses the role returned by Spring registration, not a mocked canonical user;
the protected machine-only canonical lookup is not exercised here.
"""

import secrets
import sys
import unittest
from pathlib import Path
from urllib.parse import urlparse

import jwt
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))

from clients.hydra import HydraClient, parse_query_param  # noqa: E402
from clients.kratos import KratosClient  # noqa: E402
from services.dev_token import _generate_pkce_pair  # noqa: E402
from services.login_consent import handle_consent  # noqa: E402


class RealScopeGrantTest(unittest.TestCase):
    def setUp(self):
        self.hydra = HydraClient("http://127.0.0.1:4445", "http://127.0.0.1:4444")
        self.kratos = KratosClient("http://127.0.0.1:4434", "http://127.0.0.1:4433")
        self.sessions = set()
        self.refresh = set()
        self.consent_skips = []
        self.addCleanup(self.revoke)
        suffix = secrets.token_hex(6)
        self.password = secrets.token_urlsafe(32)
        response = requests.post("http://127.0.0.1:8080/auth/register", json={
            "username": f"scope-{suffix}", "email": f"scope-{suffix}@example.com",
            "password": self.password, "dateOfBirth": "2000-01-01",
        }, timeout=15)
        self.assertEqual(response.status_code, 200, "Registration failed; body suppressed")
        self.user = response.json()
        self.assertEqual(self.user["role"], "USER")
        login = self.kratos.authenticate_with_password(self.user["email"], self.password)
        self.sessions.add(login["session_token"])
        session = self.kratos.whoami(None, session_token=login["session_token"])
        identity = self.kratos.get_identity(session["identity"]["id"])
        self.assertEqual(identity["metadata_admin"]["spring_user_id"], self.user["id"])
        self.browser = self.hydra.new_authorization_session()
        self.addCleanup(self.browser.close)
        self.client = self.hydra.get_client("bytecore-web")

    def revoke(self):
        for token in self.refresh:
            self.hydra.revoke_token("bytecore-web", token)
        for token in self.sessions:
            self.kratos.logout(token)

    def token(self, scope):
        verifier, challenge = _generate_pkce_pair()
        url = self.hydra.start_authorization(
            self.browser, client_id="bytecore-web", redirect_uri="http://localhost:4200/auth/callback",
            scope=scope, code_challenge=challenge, state=secrets.token_urlsafe(16),
        )
        login = self.hydra.accept_login_request(
            parse_query_param(url, "login_challenge"), str(self.user["id"]),
            context={"role": self.user["role"]},
        )
        url = self.hydra.follow_redirect(self.browser, login["redirect_to"])
        challenge = parse_query_param(url, "consent_challenge")
        self.consent_skips.append(self.hydra.get_consent_request(challenge).get("skip"))

        class Config:
            access_control_file = str(Path(__file__).resolve().parents[3] / "access-control.yml")

        url = handle_consent(Config(), self.hydra, challenge)
        url = self.hydra.follow_redirect(self.browser, url)
        self.assertEqual(urlparse(url).netloc, "localhost:4200")
        token = self.hydra.exchange_code_for_token(
            client_id="bytecore-web", code=parse_query_param(url, "code"),
            redirect_uri="http://localhost:4200/auth/callback", code_verifier=verifier,
        )
        if token.get("refresh_token"):
            self.refresh.add(token["refresh_token"])
        return token

    def verify(self, token, expected):
        key = jwt.PyJWKClient("http://127.0.0.1:4444/.well-known/jwks.json").get_signing_key_from_jwt(token["access_token"])
        claims = jwt.decode(token["access_token"], key.key, algorithms=["RS256"],
                            issuer="http://127.0.0.1:4444", options={"verify_aud": False})
        self.assertEqual(claims["sub"], str(self.user["id"]))
        self.assertEqual(claims["role"], "USER")
        self.assertEqual(set(token["scope"].split()), expected)
        self.assertEqual(set(claims["scp"]), expected)

    def test_user_grants_are_minimized_in_fresh_remembered_and_refresh_flows(self):
        expected = {
            "user:read", "user:write", "product:read", "category:read", "cart:read",
            "cart:write", "wishlist:read", "wishlist:write", "order:read", "order:write",
            "payment:read", "payment:write", "rating:write", "offline_access",
        }
        broad = self.token(self.client["scope"])
        self.verify(broad, expected)
        remembered = self.token("user:read offline_access")
        self.verify(remembered, {"user:read", "offline_access"})
        self.assertEqual(self.consent_skips, [False, True])
        response = requests.post("http://127.0.0.1:4444/oauth2/token", data={
            "grant_type": "refresh_token", "client_id": "bytecore-web",
            "refresh_token": remembered["refresh_token"],
        }, timeout=15)
        self.assertEqual(response.status_code, 200, "Refresh failed; body suppressed")
        refreshed = response.json()
        self.refresh.add(refreshed["refresh_token"])
        self.verify(refreshed, {"user:read", "offline_access"})
        no_offline = self.token("user:read")
        self.verify(no_offline, {"user:read"})
        self.assertFalse(no_offline.get("refresh_token"), "Unrequested offline_access must not issue a refresh grant")


if __name__ == "__main__":
    unittest.main()
