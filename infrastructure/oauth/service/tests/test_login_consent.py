import os
import sys
import unittest
from unittest.mock import Mock, patch
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from config import Config  # noqa: E402
from auth_fixtures import active_session  # noqa: E402
from clients.kratos import KratosClient  # noqa: E402
from services.login_consent import (  # noqa: E402
    BridgeError,
    UnprovisionedIdentityError,
    handle_login,
    resolve_canonical_user,
)


def make_config(**overrides) -> Config:
    defaults = dict(
        hydra_admin_url="http://hydra:4445",
        hydra_public_url="http://hydra:4444",
        kratos_admin_url="http://kratos:4434",
        kratos_public_url="http://kratos:4433",
        kratos_browser_url="http://127.0.0.1:4433",
        access_control_file="/app/access-control.yml",
        spring_internal_base_url="http://backend:8080",
        admin_username="admin",
        admin_email="admin@bytecore.ee",
        admin_password="admin-dev-password",
        bridge_host="0.0.0.0",
        bridge_port=4446,
        internal_token_host="0.0.0.0",
        internal_token_port=4447,
        dev_client_id="bytecore-web",
        dev_redirect_uri="http://localhost:4200/auth/callback",
        oauth_service_client_id="oauth-service-internal",
        oauth_service_client_secret="oauth-service-dev-secret",
        retry_attempts=10,
        retry_delay_seconds=2.0,
    )
    defaults.update(overrides)
    return Config(**defaults)


class ResolveCanonicalUserTest(unittest.TestCase):
    def test_resolves_linked_identity_via_spring(self):
        kratos_client = Mock()
        kratos_client.get_identity.return_value = {
            "id": "kratos-identity-uuid",
            "metadata_admin": {"spring_user_id": 42},
        }
        spring_auth_client = Mock()
        spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}

        result = resolve_canonical_user(kratos_client, spring_auth_client, "kratos-identity-uuid")

        spring_auth_client.resolve_user.assert_called_once_with(42)
        self.assertEqual(result, {"id": 42, "role": "USER"})

    def test_raises_explicitly_for_unlinked_identity(self):
        kratos_client = Mock()
        kratos_client.get_identity.return_value = {"id": "kratos-identity-uuid", "metadata_admin": {}}
        spring_auth_client = Mock()

        with self.assertRaises(UnprovisionedIdentityError):
            resolve_canonical_user(kratos_client, spring_auth_client, "kratos-identity-uuid")

        spring_auth_client.resolve_user.assert_not_called()


class HandleLoginTest(unittest.TestCase):
    def setUp(self):
        self.config = make_config()
        self.hydra_client = Mock()
        self.kratos_client = Mock()
        self.spring_auth_client = Mock()
        self.spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}
        self.hydra_client.accept_login_request.return_value = {"redirect_to": "http://hydra/next"}

    def test_accepts_login_with_spring_user_id_as_subject(self):
        self.hydra_client.get_login_request.return_value = {"skip": False}
        self.kratos_client.whoami.return_value = active_session()
        self.kratos_client.get_identity.return_value = {
            "id": "kratos-identity-uuid",
            "metadata_admin": {"spring_user_id": 42},
        }
        self.spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}
        self.hydra_client.accept_login_request.return_value = {"redirect_to": "http://hydra/next"}

        redirect = handle_login(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "login-challenge", "cookie"
        )

        self.hydra_client.accept_login_request.assert_called_once_with(
            "login-challenge", "42", context={"role": "USER"}
        )
        self.assertEqual(redirect, "http://hydra/next")

    def test_bff_login_dispatches_to_fixed_storefront_without_using_browser_provider_credentials(self):
        self.hydra_client.get_login_request.return_value = {"client": {"client_id": "bytecore-storefront"}}
        target = handle_login(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client,
            "bounded-challenge", "untrusted-browser-cookie",
        )
        self.assertEqual(target, "http://127.0.0.1:3000/auth/challenge?login_challenge=bounded-challenge")
        self.kratos_client.whoami.assert_not_called()
        self.hydra_client.accept_login_request.assert_not_called()

    def test_fails_explicitly_for_unprovisioned_identity(self):
        self.hydra_client.get_login_request.return_value = {"skip": False}
        self.kratos_client.whoami.return_value = active_session()
        self.kratos_client.get_identity.return_value = {"id": "kratos-identity-uuid", "metadata_admin": {}}

        with self.assertRaises(UnprovisionedIdentityError):
            handle_login(
                self.config,
                self.hydra_client,
                self.kratos_client,
                self.spring_auth_client,
                "login-challenge",
                "cookie",
            )

        self.hydra_client.accept_login_request.assert_not_called()

    def test_redirects_to_kratos_when_no_session(self):
        self.hydra_client.get_login_request.return_value = {"skip": False}
        self.kratos_client.whoami.return_value = None
        self.kratos_client.browser_login_url.return_value = "http://kratos/login"

        redirect = handle_login(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "login-challenge", None
        )

        self.assertEqual(redirect, "http://kratos/login")
        self.hydra_client.accept_login_request.assert_not_called()

    def test_resolves_role_via_spring_on_remembered_skip_path(self):
        self.hydra_client.get_login_request.return_value = {"skip": True, "subject": "42"}
        self.kratos_client.whoami.return_value = active_session("aal2")
        self.kratos_client.get_identity.return_value = {
            "id": "kratos-identity-uuid",
            "metadata_admin": {"spring_user_id": 42},
        }
        self.spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}
        self.hydra_client.accept_login_request.return_value = {"redirect_to": "http://hydra/next"}

        handle_login(
            self.config, self.hydra_client, self.kratos_client, self.spring_auth_client, "login-challenge", "cookie"
        )

        self.kratos_client.whoami.assert_called_once_with("cookie")
        self.spring_auth_client.resolve_user.assert_called_once_with(42)
        self.hydra_client.accept_login_request.assert_called_once_with(
            "login-challenge", "42", context={"role": "USER"}
        )

    def test_remembered_login_without_kratos_session_requires_login(self):
        self.hydra_client.get_login_request.return_value = {"skip": True, "subject": "42"}
        self.kratos_client.whoami.return_value = None
        self.kratos_client.browser_login_url.return_value = "http://kratos/login"

        redirect = handle_login(
            self.config, self.hydra_client, self.kratos_client,
            self.spring_auth_client, "login-challenge", None,
        )

        self.assertEqual(redirect, "http://kratos/login")
        self.hydra_client.accept_login_request.assert_not_called()
        self.spring_auth_client.resolve_user.assert_not_called()

    def test_remembered_subject_must_match_current_kratos_identity(self):
        self.hydra_client.get_login_request.return_value = {"skip": True, "subject": "99"}
        self.kratos_client.whoami.return_value = active_session("aal2")
        self.kratos_client.get_identity.return_value = {
            "metadata_admin": {"spring_user_id": 42},
        }
        self.spring_auth_client.resolve_user.return_value = {"id": 42, "role": "USER"}

        with self.assertRaises(BridgeError):
            handle_login(
                self.config, self.hydra_client, self.kratos_client,
                self.spring_auth_client, "login-challenge", "cookie",
            )

        self.hydra_client.accept_login_request.assert_not_called()


class BrowserAssuranceTest(unittest.TestCase):
    def setUp(self):
        self.config = make_config()
        self.hydra = Mock()
        self.kratos = KratosClient(self.config.kratos_admin_url, self.config.kratos_public_url)
        self.spring = Mock()
        self.spring.resolve_user.return_value = {"id": 42, "role": "USER"}
        self.hydra.accept_login_request.return_value = {"redirect_to": "http://hydra/next"}

    @patch("clients.kratos.requests.get")
    def test_insufficient_assurance_redirects_to_aal2_even_for_remembered_login(self, get):
        get.return_value = Mock(
            status_code=403,
            json=Mock(return_value={"error": {"id": "session_aal2_required"}}),
        )
        for skip in (False, True):
            with self.subTest(skip=skip):
                self.hydra.get_login_request.return_value = {"skip": skip, "subject": "42"}
                redirect = handle_login(
                    self.config, self.hydra, self.kratos, self.spring,
                    "original-challenge", "ory_kratos_session=fixture",
                )
                parsed = urlparse(redirect)
                query = parse_qs(parsed.query)
                self.assertEqual(parsed.path, "/self-service/login/browser")
                self.assertEqual(query.get("aal"), ["aal2"])
                return_query = parse_qs(urlparse(query["return_to"][0]).query)
                self.assertEqual(return_query["login_challenge"], ["original-challenge"])
                self.hydra.accept_login_request.assert_not_called()
                self.spring.resolve_user.assert_not_called()

    @patch("clients.kratos.requests.get")
    def test_invalid_or_revoked_cookie_requires_first_factor_including_skip(self, get):
        get.return_value = Mock(status_code=401, json=Mock(return_value={"error": {"id": "session_inactive"}}))
        for skip in (False, True):
            with self.subTest(skip=skip):
                self.hydra.get_login_request.return_value = {"skip": skip, "subject": "42"}
                redirect = handle_login(
                    self.config, self.hydra, self.kratos, self.spring, "challenge", "revoked-cookie",
                )
                query = parse_qs(urlparse(redirect).query)
                self.assertNotIn("aal", query)
                self.assertEqual(urlparse(redirect).path, "/self-service/login/browser")
                self.hydra.accept_login_request.assert_not_called()

    @patch("clients.kratos.requests.get")
    def test_upstream_failure_is_not_treated_as_anonymous_login(self, get):
        get.return_value = Mock(status_code=503, json=Mock(return_value={"error": {"id": "unavailable"}}))
        self.hydra.get_login_request.return_value = {"skip": False}
        with self.assertRaises(RuntimeError):
            handle_login(self.config, self.hydra, self.kratos, self.spring, "challenge", "cookie")
        self.hydra.accept_login_request.assert_not_called()

    @patch("clients.kratos.requests.get")
    def test_aal2_keeps_canonical_subject_and_role(self, get):
        self.hydra.get_login_request.return_value = {"skip": False}
        get.side_effect = [
            Mock(status_code=200, json=Mock(return_value=active_session("aal2"))),
            Mock(status_code=200, json=Mock(return_value={"metadata_admin": {"spring_user_id": 42}})),
        ]
        redirect = handle_login(self.config, self.hydra, self.kratos, self.spring, "challenge", "cookie")
        self.assertEqual(redirect, "http://hydra/next")
        self.hydra.accept_login_request.assert_called_once_with(
            "challenge", "42", context={"role": "USER"},
        )


if __name__ == "__main__":
    unittest.main()
