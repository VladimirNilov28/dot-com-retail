"""Independent local smoke sequence using the real-flow test client helpers."""

from .totp_flow_test import KRATOS, TOKEN, RealTotpFlowTest


def main():
    client = RealTotpFlowTest()
    client.setUp()
    try:
        user, password = client.register()
        normal = client.oauth_token({"email": user["email"], "password": password})
        client.verify_jwt_and_api(normal, user)
        print("PASS normal USER login, signed Hydra JWT, and protected GraphQL")

        session = normal["kratos_session_token"]
        secret = client.enroll(session)
        print("PASS native Kratos QR/secret enrollment; malformed and wrong codes rejected")

        client.require_status(client.http("POST", "http://127.0.0.1:4446/logout", json={
            "refresh_token": normal["refresh_token"], "kratos_session_token": session,
        }), 204)
        client.refresh_tokens.remove(normal["refresh_token"])
        client.session_tokens.remove(session)

        provisional = client.password_login(user["email"], password)
        client.assert_step_up_required(provisional)
        response = client.http("POST", TOKEN, json={"email": user["email"], "password": password})
        client.require_status(response, 403)
        print("PASS password-only and provisional sessions cannot obtain OAuth tokens")

        client.require_status(client.second_factor(provisional, "totp", client.wrong_code(secret)), 400)
        completed = client.track_login(client.second_factor(provisional, "totp", client.stable_code(secret)))
        issued = client.oauth_token({"kratos_session_token": completed})
        client.verify_jwt_and_api(issued, user)
        client.assertEqual(set(issued["scope"].split()), set(normal["scope"].split()))
        print("PASS valid TOTP, unchanged subject/USER role/scopes, and protected API access")

        flow = client.settings(completed)
        generated = client.update_settings(
            flow, completed, {"method": "lookup_secret", "lookup_secret_regenerate": True},
        )
        client.require_status(generated, 200)
        flow = generated.json()
        codes = client.lookup_codes(flow)
        client.sensitive_values.update(codes)
        client.require_status(client.update_settings(
            flow, completed, {"method": "lookup_secret", "lookup_secret_confirm": True},
        ), 200)
        lookup_session = client.password_login(user["email"], password)
        client.track_login(client.second_factor(lookup_session, "lookup_secret", codes[0]))
        reused = client.password_login(user["email"], password)
        client.require_status(client.second_factor(reused, "lookup_secret", codes[0]), 400)
        print("PASS native single-use recovery code; reuse rejected")

        client.require_status(client.update_settings(
            client.settings(completed), completed, {"method": "totp", "totp_unlink": True},
        ), 200)
        client.require_status(client.update_settings(
            client.settings(completed), completed, {"method": "lookup_secret", "lookup_secret_disable": True},
        ), 200)
        restored = client.oauth_token({"email": user["email"], "password": password})
        client.verify_jwt_and_api(restored, user)
        whoami = client.http("GET", KRATOS + "/sessions/whoami", restored["kratos_session_token"])
        client.require_status(whoami, 200)
        client.assertEqual(whoami.json()["authenticator_assurance_level"], "aal1")
        client.assert_safe_logs()
        print("PASS privileged full 2FA disable, restored password login, and no secret/code log leakage")
    finally:
        if not client.doCleanups():
            raise RuntimeError("Smoke cleanup failed; inspect test-created sessions/grants")


if __name__ == "__main__":
    main()
