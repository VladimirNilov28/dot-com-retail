import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock, patch

from test_login_consent import make_config
from services.bootstrap import load_and_validate_spec
from services.login_consent import BridgeError, handle_consent


POLICY = str(Path(__file__).resolve().parents[2] / "access-control.yml")
ROLE_SCOPES = {
    "USER": {
        "user:read", "user:write", "product:read", "category:read", "rating:write",
        "cart:read", "cart:write", "wishlist:read", "wishlist:write",
        "order:read", "order:write", "payment:read", "payment:write",
    },
    "SUPPORT": {"user:read", "user:write", "order:read", "payment:read"},
    "CATALOG_MANAGER": {"product:read", "product:write", "category:read", "category:write"},
    "ORDER_MANAGER": {"order:read", "order:manage-status", "payment:read", "payment:manage-status"},
    "WAREHOUSE": {"warehouse:read", "warehouse:write"},
}


class ConsentScopeTest(unittest.TestCase):
    def setUp(self):
        self.config = make_config(access_control_file=POLICY)
        self.spec = load_and_validate_spec(POLICY)
        self.application = {scope.name for scope in self.spec.scopes} - {"internal:provision-user"}
        self.hydra = Mock()
        self.hydra.accept_consent_request.return_value = {"redirect_to": "http://hydra/next"}
        self.request = {
            "client": {"client_id": "bytecore-web", "scope": " ".join(sorted(self.application | {"offline_access"}))},
            "context": {"role": "USER"},
            "requested_scope": sorted(self.application | {"offline_access", "internal:provision-user", "unknown:read"}),
            "requested_access_token_audience": ["retail"],
        }
        self.hydra.get_consent_request.return_value = self.request

    def grant(self):
        self.assertEqual(handle_consent(self.config, self.hydra, "challenge"), "http://hydra/next")
        args, kwargs = self.hydra.accept_consent_request.call_args
        self.assertEqual(args, ("challenge",))
        self.assertEqual(kwargs["grant_access_token_audience"], ["retail"])
        self.assertEqual(kwargs["access_token_claims"], self.request["context"])
        return kwargs["grant_scope"]

    def test_all_roles_receive_only_their_declared_application_scopes(self):
        expected = {**ROLE_SCOPES, "ADMIN": self.application}
        for role, allowed in expected.items():
            with self.subTest(role=role):
                self.request["context"] = {"role": role}
                self.assertEqual(set(self.grant()), allowed | {"offline_access"})

    def test_requested_subset_is_not_expanded_and_duplicates_are_removed(self):
        self.request["requested_scope"] = ["user:read", "user:read", "product:write"]
        self.assertEqual(self.grant(), ["user:read"])

    def test_public_bff_consent_dispatches_but_private_bound_consent_applies_the_actual_policy(self):
        self.request["client"] = {"client_id": "bytecore-storefront",
                                  "scope": "openid offline_access user:read"}
        self.request["requested_scope"] = ["openid", "offline_access", "user:read", "user:write"]
        self.request["request_url"] = "http://hydra:4444/oauth2/auth?state=bound-state"
        self.assertEqual(
            handle_consent(self.config, self.hydra, "challenge"),
            "http://127.0.0.1:3000/auth/consent?consent_challenge=challenge",
        )
        self.hydra.accept_consent_request.assert_not_called()
        self.assertEqual(handle_consent(
            self.config, self.hydra, "challenge", "bytecore-storefront", "bound-state",
        ), "http://hydra/next")
        self.assertEqual(set(self.hydra.accept_consent_request.call_args.kwargs["grant_scope"]),
                         {"openid", "offline_access", "user:read"})

    def test_hydra_client_allow_list_is_also_respected(self):
        self.request["client"]["scope"] = "user:read"
        self.assertEqual(self.grant(), ["user:read"])

    def test_spec_client_allow_list_is_also_respected(self):
        web = next(client for client in self.spec.clients if client.client_id == "bytecore-web")
        restricted = replace(web, scopes=["user:read"], additional_scopes=[])
        spec = replace(self.spec, clients=[restricted])
        with patch("services.login_consent.load_and_validate_spec", return_value=spec, create=True):
            self.assertEqual(self.grant(), ["user:read"])

    def test_offline_access_is_not_added_unless_requested(self):
        self.request["requested_scope"] = ["user:read"]
        self.assertEqual(self.grant(), ["user:read"])

    def test_unknown_or_missing_roles_never_accept_consent(self):
        for context in ({}, None, {"role": "UNKNOWN"}, {"role": ""}, {"role": ["ADMIN"]}):
            with self.subTest(context=context):
                self.request["context"] = context
                with self.assertRaises(BridgeError):
                    handle_consent(self.config, self.hydra, "challenge")
                self.hydra.accept_consent_request.assert_not_called()

    def test_unknown_missing_or_machine_clients_never_accept_human_consent(self):
        for client in ({}, None, {"client_id": "unknown"}, {"client_id": "oauth-service-internal"}):
            with self.subTest(client=client):
                self.request["client"] = client
                with self.assertRaises(BridgeError):
                    handle_consent(self.config, self.hydra, "challenge")
                self.hydra.accept_consent_request.assert_not_called()

    def test_admin_wildcard_cannot_grant_internal_scope_even_if_client_allows_it(self):
        self.request["context"] = {"role": "ADMIN"}
        self.request["client"]["scope"] += " internal:provision-user"
        web = next(client for client in self.spec.clients if client.client_id == "bytecore-web")
        spec = replace(self.spec, clients=[replace(web, scopes=None)])
        with patch("services.login_consent.load_and_validate_spec", return_value=spec, create=True):
            self.assertNotIn("internal:provision-user", self.grant())

    def test_remembered_consent_is_filtered_again_using_current_role(self):
        self.request["skip"] = True
        self.request["context"] = {"role": "SUPPORT"}
        self.assertEqual(set(self.grant()), ROLE_SCOPES["SUPPORT"] | {"offline_access"})

    def test_missing_hydra_client_scope_fails_closed(self):
        del self.request["client"]["scope"]
        with self.assertRaises(BridgeError):
            handle_consent(self.config, self.hydra, "challenge")
        self.hydra.accept_consent_request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
