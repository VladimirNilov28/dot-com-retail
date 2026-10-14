import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from services.bootstrap import (  # noqa: E402
    AccessControlSpec,
    AccessControlSpecError,
    ClientSpec,
    Scope,
    _build_desired_client_payload,
    _parse_clients,
    load_and_validate_spec,
    reconcile_hydra_clients,
)


SCOPE_NAMES = {"user:read", "user:write", "internal:provision-user"}


class ParseClientsTest(unittest.TestCase):
    def test_client_without_scopes_field_defaults_to_none(self):
        clients = _parse_clients(
            [{"client_id": "bytecore-web", "grant_types": ["authorization_code"]}],
            "access-control.yml",
            SCOPE_NAMES,
        )

        self.assertIsNone(clients[0].scopes)

    def test_client_with_explicit_scopes_field(self):
        clients = _parse_clients(
            [
                {
                    "client_id": "oauth-service-internal",
                    "grant_types": ["client_credentials"],
                    "scopes": ["internal:provision-user"],
                }
            ],
            "access-control.yml",
            SCOPE_NAMES,
        )

        self.assertEqual(clients[0].scopes, ["internal:provision-user"])

    def test_client_with_unknown_scope_raises(self):
        with self.assertRaises(AccessControlSpecError):
            _parse_clients(
                [
                    {
                        "client_id": "oauth-service-internal",
                        "grant_types": ["client_credentials"],
                        "scopes": ["not-a-real-scope"],
                    }
                ],
                "access-control.yml",
                SCOPE_NAMES,
            )

    def test_client_without_additional_scopes_field_defaults_to_empty(self):
        clients = _parse_clients(
            [{"client_id": "bytecore-web", "grant_types": ["authorization_code"]}],
            "access-control.yml",
            SCOPE_NAMES,
        )

        self.assertEqual(clients[0].additional_scopes, [])

    def test_client_with_reserved_additional_scope(self):
        clients = _parse_clients(
            [
                {
                    "client_id": "bytecore-web",
                    "grant_types": ["authorization_code", "refresh_token"],
                    "additional_scopes": ["offline_access"],
                }
            ],
            "access-control.yml",
            SCOPE_NAMES,
        )

        self.assertEqual(clients[0].additional_scopes, ["offline_access"])

    def test_client_with_unknown_reserved_scope_raises(self):
        with self.assertRaises(AccessControlSpecError):
            _parse_clients(
                [
                    {
                        "client_id": "bytecore-web",
                        "grant_types": ["authorization_code"],
                        "additional_scopes": ["not-a-real-reserved-scope"],
                    }
                ],
                "access-control.yml",
                SCOPE_NAMES,
            )


class BuildDesiredClientPayloadTest(unittest.TestCase):
    def test_client_without_explicit_scopes_gets_every_declared_scope(self):
        client = ClientSpec(
            client_id="bytecore-web",
            client_name="Bytecore Web",
            grant_types=["authorization_code"],
            response_types=["code"],
            token_endpoint_auth_method="none",
            redirect_uris=["http://localhost/callback"],
            scopes=None,
        )

        class FakeScope:
            def __init__(self, name):
                self.name = name

        class FakeSpec:
            scopes = [FakeScope(n) for n in SCOPE_NAMES]

        payload = _build_desired_client_payload(client, FakeSpec())

        self.assertEqual(set(payload["scope"].split()), SCOPE_NAMES)

    def test_client_with_explicit_scopes_gets_only_those(self):
        client = ClientSpec(
            client_id="oauth-service-internal",
            client_name="oauth-service",
            grant_types=["client_credentials"],
            response_types=[],
            token_endpoint_auth_method="client_secret_post",
            redirect_uris=[],
            scopes=["internal:provision-user"],
        )

        class FakeScope:
            def __init__(self, name):
                self.name = name

        class FakeSpec:
            scopes = [FakeScope(n) for n in SCOPE_NAMES]

        payload = _build_desired_client_payload(client, FakeSpec())

        self.assertEqual(payload["scope"], "internal:provision-user")

    def test_additional_scopes_are_merged_in_on_top_of_declared_scopes(self):
        client = ClientSpec(
            client_id="bytecore-web",
            client_name="Bytecore Web",
            grant_types=["authorization_code", "refresh_token"],
            response_types=["code"],
            token_endpoint_auth_method="none",
            redirect_uris=["http://localhost/callback"],
            scopes=None,
            additional_scopes=["offline_access"],
        )

        class FakeScope:
            def __init__(self, name):
                self.name = name

        class FakeSpec:
            scopes = [FakeScope(n) for n in SCOPE_NAMES]

        payload = _build_desired_client_payload(client, FakeSpec())

        self.assertEqual(set(payload["scope"].split()), SCOPE_NAMES | {"offline_access"})


class ReconcileHydraClientsTest(unittest.TestCase):
    def test_injects_client_secret_only_for_internal_service_client_on_create(self):
        spec = AccessControlSpec(
            version=1,
            scopes=[Scope(name="internal:provision-user", description="")],
            roles=[],
            clients=[
                ClientSpec(
                    client_id="oauth-service-internal",
                    client_name="oauth-service",
                    grant_types=["client_credentials"],
                    response_types=[],
                    token_endpoint_auth_method="client_secret_post",
                    redirect_uris=[],
                    scopes=["internal:provision-user"],
                )
            ],
        )

        hydra_client = Mock()
        hydra_client.get_client.return_value = None
        config = Mock(oauth_service_client_id="oauth-service-internal", oauth_service_client_secret="s3cr3t")

        reconcile_hydra_clients(hydra_client, spec, config)

        payload = hydra_client.create_client.call_args[0][0]
        self.assertEqual(payload["client_secret"], "s3cr3t")

    def test_does_not_inject_client_secret_for_other_clients(self):
        spec = AccessControlSpec(
            version=1,
            scopes=[Scope(name="user:read", description="")],
            roles=[],
            clients=[
                ClientSpec(
                    client_id="bytecore-web",
                    client_name="Bytecore Web",
                    grant_types=["authorization_code"],
                    response_types=["code"],
                    token_endpoint_auth_method="none",
                    redirect_uris=["http://localhost/callback"],
                    scopes=None,
                )
            ],
        )

        hydra_client = Mock()
        hydra_client.get_client.return_value = None
        config = Mock(oauth_service_client_id="oauth-service-internal", oauth_service_client_secret="s3cr3t")

        reconcile_hydra_clients(hydra_client, spec, config)

        payload = hydra_client.create_client.call_args[0][0]
        self.assertNotIn("client_secret", payload)


class ApplicationClientBoundaryTest(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[2] / "access-control.yml"
        self.spec = load_and_validate_spec(str(path))

    def test_web_client_has_all_existing_application_scopes_but_no_machine_scope(self):
        client = next(client for client in self.spec.clients if client.client_id == "bytecore-web")
        payload = _build_desired_client_payload(client, self.spec)
        expected = {scope.name for scope in self.spec.scopes} - {"internal:provision-user"}
        self.assertEqual(set(payload["scope"].split()), expected | {"offline_access"})

    def test_machine_client_remains_limited_to_provisioning(self):
        client = next(client for client in self.spec.clients if client.client_id == "oauth-service-internal")
        payload = _build_desired_client_payload(client, self.spec)
        self.assertEqual(payload["scope"], "internal:provision-user")
        self.assertEqual(payload["grant_types"], ["client_credentials"])


if __name__ == "__main__":
    unittest.main()
