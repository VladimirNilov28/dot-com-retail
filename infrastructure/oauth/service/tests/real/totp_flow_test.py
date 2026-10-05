"""Opt-in real-server specification: run this file directly, not mocked discovery.

Requires the local Spring/Hive/Hydra/Kratos/oauth-service stack, PyOTP, and
PyJWT[crypto]. Creates unique USER accounts through /auth/register; revokes
test sessions and refresh grants through public APIs. Domain accounts remain
available for inspection; no shared identities or database state are edited.
"""

import email.utils
import hashlib
import base64
import re
import secrets
import subprocess
import time
import unittest
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlencode, urlparse

import jwt
import pyotp
import requests


KRATOS = "http://127.0.0.1:4433"
HYDRA = "http://127.0.0.1:4444"
TOKEN = "http://127.0.0.1:4447/internal/token"
BRIDGE = "http://127.0.0.1:4446"
SPRING = "http://127.0.0.1:8080"
HIVE = "http://127.0.0.1:4002/graphql"


class RealTotpFlowTest(unittest.TestCase):
    def setUp(self):
        self.started_at = datetime.now(timezone.utc).isoformat()
        self.session_tokens = set()
        self.refresh_tokens = set()
        self.sensitive_values = set()
        self.addCleanup(self.revoke_test_credentials)

    def http(self, method, url, session_token=None, browser=None, **kwargs):
        headers = {"Accept": "application/json"}
        if session_token:
            headers["X-Session-Token"] = session_token
        headers.update(kwargs.pop("headers", {}))
        return (browser or requests).request(
            method, url, headers=headers, timeout=15, allow_redirects=False, **kwargs,
        )

    def require_status(self, response, expected):
        self.assertEqual(response.status_code, expected, "Unexpected HTTP status; response body suppressed")

    def action(self, flow):
        parsed = urlparse(flow["ui"]["action"])
        self.assertEqual(f"{parsed.scheme}://{parsed.netloc}", KRATOS)
        return flow["ui"]["action"]

    def nodes(self, flow):
        return {
            node["attributes"]["id"]: node["attributes"]
            for node in flow["ui"]["nodes"]
            if "id" in node.get("attributes", {})
        }

    def lookup_codes(self, flow):
        messages = self.nodes(flow)["lookup_secret_codes"]["text"]["context"]["secrets"]
        return [message["context"]["secret"] for message in messages]

    def register(self):
        suffix = secrets.token_hex(6)
        email = f"totp-{suffix}@example.com"
        password = secrets.token_urlsafe(32)
        self.sensitive_values.add(password)
        response = self.http("POST", SPRING + "/auth/register", json={
            "username": f"totp-{suffix}",
            "email": email,
            "password": password,
            "dateOfBirth": "2000-01-01",
        })
        self.require_status(response, 200)
        user = response.json()
        self.assertEqual(user["role"], "USER")
        return user, password

    def track_login(self, response):
        self.require_status(response, 200)
        login = response.json()
        token = login["session_token"]
        self.session_tokens.add(token)
        self.sensitive_values.add(token)
        return token

    def password_login(self, email, password):
        response = self.http("GET", KRATOS + "/self-service/login/api")
        self.require_status(response, 200)
        response = self.http("POST", self.action(response.json()), json={
            "method": "password", "identifier": email, "password": password,
        })
        return self.track_login(response)

    def settings(self, token):
        response = self.http("GET", KRATOS + "/self-service/settings/api", token)
        self.require_status(response, 200)
        return response.json()

    def update_settings(self, flow, token, payload):
        return self.http("POST", self.action(flow), token, json=payload)

    def second_factor(self, token, method, code):
        response = self.http("GET", KRATOS + "/self-service/login/api", token, params={"aal": "aal2"})
        self.require_status(response, 200)
        field = "totp_code" if method == "totp" else "lookup_secret"
        return self.http("POST", self.action(response.json()), token, json={"method": method, field: code})

    def stable_code(self, secret):
        response = self.http("GET", KRATOS + "/health/alive")
        self.require_status(response, 200)
        server_time = email.utils.parsedate_to_datetime(response.headers["Date"]).timestamp()
        self.assertLess(abs(time.time() - server_time), 5, "Synchronize host/container clocks")
        remaining = 30 - server_time % 30
        if remaining < 5:
            time.sleep(remaining + 1)
            return self.stable_code(secret)
        code = pyotp.TOTP(secret).at(server_time)
        self.sensitive_values.add(code)
        return code

    def wrong_code(self, secret):
        now = time.time()
        accepted = {pyotp.TOTP(secret).at(now + offset) for offset in (-30, 0, 30)}
        code = next(f"{value:06d}" for value in range(10) if f"{value:06d}" not in accepted)
        self.sensitive_values.add(code)
        return code

    def enroll(self, token):
        flow = self.settings(token)
        nodes = self.nodes(flow)
        self.assertIn("totp_qr", nodes, "Expected Kratos-native QR node")
        self.assertIn("totp_secret_key", nodes, "Expected Kratos-native setup secret")
        secret = nodes["totp_secret_key"]["text"]["context"]["secret"]
        self.sensitive_values.add(secret)
        self.sensitive_values.add(nodes["totp_qr"]["src"])
        for invalid in (self.wrong_code(secret), "malformed"):
            response = self.update_settings(flow, token, {"method": "totp", "totp_code": invalid})
            self.require_status(response, 400)
        response = self.update_settings(flow, token, {"method": "totp", "totp_code": self.stable_code(secret)})
        self.require_status(response, 200)
        new_flow = self.settings(token)
        self.assertNotIn("totp_secret_key", self.nodes(new_flow))
        self.assertNotIn("totp_qr", self.nodes(new_flow))
        return secret

    def oauth_token(self, payload):
        response = self.http("POST", TOKEN, json=payload)
        self.require_status(response, 200)
        token = response.json()
        self.sensitive_values.add(token["access_token"])
        if "refresh_token" in token:
            self.refresh_tokens.add(token["refresh_token"])
            self.sensitive_values.add(token["refresh_token"])
        if "kratos_session_token" in token:
            self.session_tokens.add(token["kratos_session_token"])
            self.sensitive_values.add(token["kratos_session_token"])
        return token

    def verify_jwt_and_api(self, token, user):
        metadata = self.http("GET", HYDRA + "/.well-known/openid-configuration")
        self.require_status(metadata, 200)
        jwks_url = metadata.json()["jwks_uri"]
        self.assertEqual(urlparse(jwks_url).netloc, "127.0.0.1:4444")
        key = jwt.PyJWKClient(jwks_url).get_signing_key_from_jwt(token["access_token"])
        claims = jwt.decode(
            token["access_token"], key.key, algorithms=["RS256"],
            issuer=HYDRA, options={"verify_aud": False, "require": ["sub", "iss", "exp"]},
        )
        self.assertEqual(claims["sub"], str(user["id"]))
        self.assertEqual(claims["role"], "USER")
        self.assertNotIn("internal:provision-user", token["scope"].split())
        for value in self.sensitive_values - {token["access_token"]}:
            self.assertFalse(value in str(claims), "JWT contains authentication material; claims suppressed")
        response = self.http(
            "POST", HIVE, headers={"Authorization": f"Bearer {token['access_token']}"},
            json={"query": "query { me { id username email } }"},
        )
        self.require_status(response, 200)
        result = response.json()
        self.assertFalse("errors" in result, "Protected GraphQL request failed; response body suppressed")
        self.assertEqual(str(result["data"]["me"]["id"]), str(user["id"]))
        self.assertEqual(result["data"]["me"]["email"], user["email"])
        return claims

    def assert_step_up_required(self, token):
        response = self.http("GET", KRATOS + "/sessions/whoami", token)
        self.require_status(response, 403)
        self.assertEqual(response.json()["error"]["id"], "session_aal2_required")
        response = self.http("POST", TOKEN, json={"kratos_session_token": token})
        self.require_status(response, 403)
        body = response.json()
        self.assertEqual(body["error"], "second_factor_required")
        self.assertNotIn("access_token", body)
        self.assertNotIn("refresh_token", body)
        for value in self.sensitive_values:
            self.assertFalse(value in response.text, "Bridge error contains authentication material; body suppressed")

    def revoke_test_credentials(self):
        for token in self.refresh_tokens:
            response = self.http("POST", BRIDGE + "/logout", json={"refresh_token": token})
            self.require_status(response, 204)
        for token in self.session_tokens:
            response = self.http("DELETE", KRATOS + "/self-service/logout/api", json={"session_token": token})
            self.assertIn(response.status_code, (204, 401), "Could not revoke test Kratos session")

    def test_native_enrollment_challenge_recovery_disable_and_jwt(self):
        user, password = self.register()
        baseline = self.oauth_token({"email": user["email"], "password": password})
        baseline_claims = self.verify_jwt_and_api(baseline, user)
        old_token = baseline["kratos_session_token"]
        token = self.password_login(user["email"], password)
        secret = self.enroll(token)
        self.assert_step_up_required(old_token)

        # Enrollment does not revoke Hydra grants automatically; revoke ours explicitly.
        refresh_token = baseline["refresh_token"]
        self.require_status(self.http("POST", BRIDGE + "/logout", json={"refresh_token": refresh_token}), 204)
        self.refresh_tokens.remove(refresh_token)

        provisional = self.password_login(user["email"], password)
        self.assert_step_up_required(provisional)
        password_only = self.http("POST", TOKEN, json={"email": user["email"], "password": password})
        self.require_status(password_only, 403)
        self.assertNotIn("access_token", password_only.json())
        for invalid in ("malformed", self.wrong_code(secret)):
            response = self.second_factor(provisional, "totp", invalid)
            self.require_status(response, 400)
            self.assert_step_up_required(provisional)
        completed = self.track_login(self.second_factor(provisional, "totp", self.stable_code(secret)))
        session = self.http("GET", KRATOS + "/sessions/whoami", completed)
        self.require_status(session, 200)
        self.assertEqual(session.json()["authenticator_assurance_level"], "aal2")
        self.assertFalse(secret in session.text, "Session contains setup secret; body suppressed")
        issued = self.oauth_token({"kratos_session_token": completed})
        claims = self.verify_jwt_and_api(issued, user)
        self.assertEqual(claims["sub"], baseline_claims["sub"])
        self.assertEqual(claims["role"], baseline_claims["role"])
        self.assertEqual(set(issued["scope"].split()), set(baseline["scope"].split()))

        flow = self.settings(completed)
        response = self.update_settings(flow, completed, {"method": "lookup_secret", "lookup_secret_regenerate": True})
        self.require_status(response, 200)
        flow = response.json()
        codes = self.lookup_codes(flow)
        self.assertEqual(len(codes), 12)
        self.assertEqual(len(set(codes)), 12)
        self.sensitive_values.update(codes)
        unconfirmed = self.password_login(user["email"], password)
        self.require_status(self.second_factor(unconfirmed, "lookup_secret", codes[0]), 400)
        self.require_status(
            self.update_settings(flow, completed, {"method": "lookup_secret", "lookup_secret_confirm": True}), 200,
        )
        ordinary = self.http("GET", KRATOS + "/sessions/whoami", completed)
        self.require_status(ordinary, 200)
        for code in codes:
            self.assertFalse(code in ordinary.text, "Session contains recovery code; body suppressed")
        self.assertNotIn("lookup_secret_codes", self.nodes(self.settings(completed)))
        lookup_session = self.password_login(user["email"], password)
        self.track_login(self.second_factor(lookup_session, "lookup_secret", codes[0]))
        reused = self.password_login(user["email"], password)
        self.require_status(self.second_factor(reused, "lookup_secret", codes[0]), 400)
        self.assert_step_up_required(reused)
        self.require_status(self.second_factor(reused, "lookup_secret", "invalid-code"), 400)

        flow = self.settings(completed)
        revealed = self.update_settings(flow, completed, {"method": "lookup_secret", "lookup_secret_reveal": True})
        self.require_status(revealed, 200)
        self.assertIn("lookup_secret_codes", self.nodes(revealed.json()))
        response = self.update_settings(flow, completed, {"method": "lookup_secret", "lookup_secret_regenerate": True})
        self.require_status(response, 200)
        replacement = response.json()
        self.sensitive_values.update(self.lookup_codes(replacement))
        self.require_status(
            self.update_settings(replacement, completed, {"method": "lookup_secret", "lookup_secret_confirm": True}), 200,
        )
        replaced = self.password_login(user["email"], password)
        self.require_status(self.second_factor(replaced, "lookup_secret", codes[1]), 400)

        low_settings = self.http("GET", KRATOS + "/self-service/settings/api", replaced)
        self.require_status(low_settings, 403)
        flow = self.settings(completed)
        self.require_status(self.update_settings(flow, completed, {"method": "totp", "totp_unlink": True}), 200)
        still_protected = self.password_login(user["email"], password)
        self.assert_step_up_required(still_protected)
        flow = self.settings(completed)
        self.require_status(
            self.update_settings(flow, completed, {"method": "lookup_secret", "lookup_secret_disable": True}), 200,
        )
        normal = self.oauth_token({"email": user["email"], "password": password})
        self.verify_jwt_and_api(normal, user)
        self.assert_safe_logs()

    def test_settings_are_bound_to_authenticated_identity(self):
        owner, owner_password = self.register()
        other, other_password = self.register()
        owner_token = self.password_login(owner["email"], owner_password)
        other_token = self.password_login(other["email"], other_password)
        flow = self.settings(owner_token)
        response = self.http("GET", KRATOS + "/self-service/settings/api")
        self.require_status(response, 401)
        for token in (None, other_token):
            with self.subTest(authenticated=token is not None):
                response = self.http(
                    "GET", KRATOS + "/self-service/settings/flows", token, params={"id": flow["id"]},
                )
                self.assertIn(response.status_code, (401, 403))
                response = self.update_settings(flow, token, {"method": "totp", "totp_unlink": True})
                self.assertIn(response.status_code, (401, 403))

    def test_browser_cookie_requires_second_factor_after_enrollment(self):
        user, password = self.register()
        browser = requests.Session()
        self.addCleanup(browser.close)
        self.addCleanup(self.browser_logout, browser)
        response = self.http("GET", KRATOS + "/self-service/login/browser", browser=browser)
        self.require_status(response, 200)
        flow = response.json()
        csrf = next(
            node["attributes"]["value"]
            for node in flow["ui"]["nodes"]
            if node["attributes"].get("name") == "csrf_token"
        )
        response = self.http("POST", self.action(flow), browser=browser, json={
            "method": "password", "identifier": user["email"], "password": password, "csrf_token": csrf,
        })
        self.require_status(response, 200)
        baseline = self.browser_oauth_token(browser)
        self.verify_jwt_and_api(baseline, user)
        native_token = self.password_login(user["email"], password)
        secret = self.enroll(native_token)
        response = self.http("GET", KRATOS + "/sessions/whoami", browser=browser)
        self.require_status(response, 403)
        self.assertEqual(response.json()["error"]["id"], "session_aal2_required")
        login_url, verifier = self.start_browser_oauth(browser)
        challenge = parse_qs(urlparse(login_url).query)["login_challenge"][0]
        remembered = self.http(
            "GET", "http://127.0.0.1:4445/admin/oauth2/auth/requests/login",
            params={"login_challenge": challenge},
        )
        self.require_status(remembered, 200)
        self.assertTrue(remembered.json()["skip"], "Expected remembered Hydra login")
        response = self.http("GET", login_url, browser=browser)
        self.require_status(response, 302)
        step_up = response.headers["Location"]
        self.assertEqual(parse_qs(urlparse(step_up).query)["aal"], ["aal2"])
        response = self.http("GET", step_up, browser=browser)
        self.require_status(response, 200)
        flow = response.json()
        csrf = next(
            node["attributes"]["value"]
            for node in flow["ui"]["nodes"]
            if node["attributes"].get("name") == "csrf_token"
        )
        response = self.http("POST", self.action(flow), browser=browser, json={
            "method": "totp", "totp_code": self.stable_code(secret), "csrf_token": csrf,
        })
        self.require_status(response, 200)
        completed = self.finish_browser_oauth(browser, login_url, verifier)
        self.verify_jwt_and_api(completed, user)
        self.assertEqual(set(completed["scope"].split()), set(baseline["scope"].split()))
        self.assert_safe_logs()

    def start_browser_oauth(self, browser):
        verifier = secrets.token_urlsafe(64)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        response = self.http("GET", "http://127.0.0.1:4445/admin/clients/bytecore-web")
        self.require_status(response, 200)
        response = self.http("GET", HYDRA + "/oauth2/auth", browser=browser, params={
            "client_id": "bytecore-web", "response_type": "code",
            "redirect_uri": "http://localhost:4200/auth/callback", "scope": response.json()["scope"],
            "state": secrets.token_urlsafe(16), "code_challenge": challenge, "code_challenge_method": "S256",
        })
        self.require_status(response, 302)
        return response.headers["Location"], verifier

    def browser_oauth_token(self, browser):
        login_url, verifier = self.start_browser_oauth(browser)
        return self.finish_browser_oauth(browser, login_url, verifier)

    def finish_browser_oauth(self, browser, login_url, verifier):
        url = login_url
        for _ in range(6):
            parsed = urlparse(url)
            if parsed.netloc == "localhost:4200":
                code = parse_qs(parsed.query)["code"][0]
                break
            self.assertIn(parsed.netloc, ("127.0.0.1:4444", "127.0.0.1:4446"))
            response = self.http("GET", url, browser=browser)
            self.assertIn(response.status_code, (302, 303), "Expected OAuth redirect")
            url = response.headers["Location"]
        else:
            self.fail("OAuth redirect chain did not complete")
        response = self.http("POST", HYDRA + "/oauth2/token", data={
            "grant_type": "authorization_code", "client_id": "bytecore-web",
            "redirect_uri": "http://localhost:4200/auth/callback", "code_verifier": verifier, "code": code,
        })
        self.require_status(response, 200)
        token = response.json()
        self.sensitive_values.add(token["access_token"])
        self.refresh_tokens.add(token["refresh_token"])
        self.sensitive_values.add(token["refresh_token"])
        return token

    def assert_safe_logs(self):
        for container in ("kratos", "oauth-service", "hydra"):
            result = subprocess.run(
                ["docker", "logs", "--since", self.started_at, container],
                capture_output=True, text=True, check=True, timeout=15,
            )
            logs = result.stdout + result.stderr
            leaked = any(
                re.search(r"(?<![0-9])" + re.escape(value) + r"(?![0-9])", logs)
                for value in self.sensitive_values
            )
            self.assertFalse(leaked, f"{container} logged authentication material; output suppressed")

    def browser_logout(self, browser):
        response = self.http("GET", KRATOS + "/self-service/logout/browser", browser=browser)
        if response.status_code == 401:
            return
        self.require_status(response, 200)
        parsed = urlparse(response.json()["logout_url"])
        self.assertEqual(f"{parsed.scheme}://{parsed.netloc}", KRATOS)
        response = self.http("GET", response.json()["logout_url"], browser=browser)
        self.assertIn(response.status_code, (200, 204, 302, 303))


if __name__ == "__main__":
    unittest.main()
