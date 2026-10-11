import time
import urllib.parse
from typing import Optional
from uuid import UUID

import requests

ACCOUNT_CREDENTIAL_TYPES = ("oidc", "password", "totp", "lookup_secret")

class KratosNotReadyError(RuntimeError):
    pass


class KratosAuthenticationError(RuntimeError):
    """Kratos rejected credentials or did not validate an active session."""


class KratosSecondFactorRequiredError(KratosAuthenticationError):
    """Kratos requires step-up before this session may authorize a login."""


class KratosUpstreamError(RuntimeError):
    pass


class KratosClient:
    def __init__(self, admin_url: str, public_url: str):
        self._admin_url = admin_url
        self._public_url = public_url

    def wait_until_ready(self, attempts: int, delay_seconds: float) -> None:
        last_error: Optional[Exception] = None
        for _ in range(attempts):
            try:
                response = requests.get(f"{self._admin_url}/health/ready", timeout=5)
                if response.ok:
                    return
                last_error = RuntimeError(f"unhealthy status {response.status_code}")
            except requests.RequestException as exc:
                last_error = exc
            time.sleep(delay_seconds)

        raise KratosNotReadyError(
            f"Kratos at {self._admin_url} did not become ready after {attempts} attempts: {last_error}"
        )

    # --- Dev admin identity bootstrap ---

    def find_identity_by_email(self, email: str) -> Optional[dict]:
        response = requests.get(
            f"{self._admin_url}/admin/identities",
            params={"credentials_identifier": email},
            timeout=10,
        )
        response.raise_for_status()
        identities = response.json()
        return identities[0] if identities else None

    def create_identity(
        self,
        schema_id: str,
        email: str,
        password: str,
        metadata_admin: Optional[dict] = None,
    ) -> dict:
        payload = {
            "schema_id": schema_id,
            "traits": {"email": email},
            "credentials": {
                "password": {
                    "config": {
                        "password": password,
                    },
                },
            },
        }
        if metadata_admin is not None:
            payload["metadata_admin"] = metadata_admin

        response = requests.post(f"{self._admin_url}/admin/identities", json=payload, timeout=10)
        response.raise_for_status()
        return response.json()

    def get_identity(self, identity_id: str) -> dict:
        """Fetches the full admin-side identity, including metadata_admin — never
        exposed via the public API (e.g. /sessions/whoami)."""
        response = requests.get(f"{self._admin_url}/admin/identities/{identity_id}", timeout=10)
        response.raise_for_status()
        return response.json()

    def get_identity_with_credentials(self, identity_id: str) -> dict:
        """Admin identity including credential configuration. Callers must
        project it; hashes, codes, subjects and TOTP URLs never leave the bridge."""
        identity_id = str(UUID(identity_id))
        response = requests.get(
            f"{self._admin_url}/admin/identities/{identity_id}",
            params=[("include_credential", kind) for kind in ACCOUNT_CREDENTIAL_TYPES], timeout=10,
        )
        response.raise_for_status()
        identity = response.json()
        if not isinstance(identity, dict) or identity.get("id") != identity_id:
            raise KratosUpstreamError("Invalid identity response")
        return identity

    def identity_ids_by_identifier(self, identifier: str) -> list:
        response = requests.get(
            f"{self._admin_url}/admin/identities",
            params={"credentials_identifier": identifier}, timeout=10,
        )
        response.raise_for_status()
        identities = response.json()
        if not isinstance(identities, list) or any(
                not isinstance(identity, dict) or not isinstance(identity.get("id"), str) for identity in identities):
            raise KratosUpstreamError("Invalid identity lookup response")
        return [identity["id"] for identity in identities]

    def update_identity_email(self, identity_id: str, email: str) -> dict:
        """Replaces traits.email (other traits, state and metadata preserved).
        Kratos v26.2.0 recomputes the password identifier, verifiable and
        recovery addresses from the schema on admin PUT but ignores submitted
        verifiable_addresses, so the new address is then marked verified with
        a JSON Patch. Credentials (including OIDC) are not submitted."""
        identity_id = str(UUID(identity_id))
        identity = self.get_identity(identity_id)
        if not isinstance(identity, dict) or identity.get("id") != identity_id \
                or not isinstance(identity.get("traits"), dict):
            raise KratosUpstreamError("Invalid identity response")
        if identity["traits"].get("email") != email:
            response = requests.put(f"{self._admin_url}/admin/identities/{identity_id}", json={
                "schema_id": identity["schema_id"], "state": identity["state"],
                "traits": {**identity["traits"], "email": email},
                "metadata_public": identity.get("metadata_public"),
                "metadata_admin": identity.get("metadata_admin"),
            }, timeout=10)
            response.raise_for_status()
            identity = response.json()
        index = self._verifiable_email_index(identity, identity_id, email)
        if identity["verifiable_addresses"][index].get("verified") is not True:
            response = requests.patch(f"{self._admin_url}/admin/identities/{identity_id}", json=[
                {"op": "replace", "path": f"/verifiable_addresses/{index}/verified", "value": True},
                {"op": "replace", "path": f"/verifiable_addresses/{index}/status", "value": "completed"},
            ], timeout=10)
            response.raise_for_status()
            identity = response.json()
            index = self._verifiable_email_index(identity, identity_id, email)
            if identity["verifiable_addresses"][index].get("verified") is not True:
                raise KratosUpstreamError("Kratos did not verify the new address")
        return identity

    @staticmethod
    def _verifiable_email_index(identity, identity_id: str, email: str) -> int:
        if not isinstance(identity, dict) or identity.get("id") != identity_id \
                or (identity.get("traits") or {}).get("email") != email \
                or not isinstance(identity.get("verifiable_addresses"), list):
            raise KratosUpstreamError("Invalid identity update response")
        matches = [index for index, address in enumerate(identity["verifiable_addresses"])
                   if isinstance(address, dict) and address.get("via") == "email"
                   and str(address.get("value", "")).casefold() == email.casefold()]
        if len(matches) != 1:
            raise KratosUpstreamError("Invalid identity update response")
        return matches[0]

    def set_identity_metadata_admin(self, identity_id: str, metadata_admin: dict) -> dict:
        patch = [{"op": "add", "path": "/metadata_admin", "value": metadata_admin}]
        response = requests.patch(
            f"{self._admin_url}/admin/identities/{identity_id}", json=patch, timeout=10
        )
        response.raise_for_status()
        return response.json()

    # --- Login bridge (browser-driven) ---

    def whoami(self, cookie_header: Optional[str], session_token: Optional[str] = None) -> Optional[dict]:
        """Uses Kratos's highest_available whoami policy for either credential
        type. A provisional AAL1 session is not a completed MFA login."""
        if cookie_header and session_token:
            raise KratosAuthenticationError("Use only one Kratos session credential")
        if not cookie_header and not session_token:
            return None

        headers = {"Accept": "application/json"}
        if session_token:
            headers["X-Session-Token"] = session_token
        else:
            headers["Cookie"] = cookie_header
        try:
            response = requests.get(
                f"{self._public_url}/sessions/whoami", headers=headers, timeout=10,
            )
            if response.status_code == 401:
                return None
            if response.status_code == 403:
                body = response.json()
                error = body.get("error") if isinstance(body, dict) else None
                if isinstance(error, dict) and error.get("id") == "session_aal2_required":
                    raise KratosSecondFactorRequiredError("Complete Kratos second-factor authentication")
            if response.status_code != 200:
                raise KratosUpstreamError(f"Kratos session check failed (HTTP {response.status_code})")
            session = response.json()
        except (requests.RequestException, ValueError) as exc:
            raise KratosUpstreamError("Kratos session check unavailable") from exc

        if (
            not isinstance(session, dict)
            or session.get("active") is not True
            or session.get("authenticator_assurance_level") not in ("aal1", "aal2")
            or not isinstance(session.get("identity"), dict)
            or not session["identity"].get("id")
        ):
            raise KratosAuthenticationError("Kratos did not return a valid active session")
        return session

    def browser_login_url(self, browser_url: str, return_to: str, aal: Optional[str] = None) -> str:
        parameters = {"return_to": return_to}
        if aal:
            parameters["aal"] = aal
        query = urllib.parse.urlencode(parameters)
        return f"{browser_url}/self-service/login/browser?{query}"

    # --- Headless password login (dev token issuance) ---

    def authenticate_with_password(self, email: str, password: str) -> dict:
        """Drives Kratos's own native (non-browser) self-service login flow.
        Kratos performs the actual password check; this only relays the flow.
        Returns the native-login response, which may still represent only
        AAL1. Callers must revalidate the session token via strict whoami
        before authorizing OAuth. Raises KratosAuthenticationError on
        invalid credentials, as reported by Kratos."""
        init_response = requests.get(
            f"{self._public_url}/self-service/login/api",
            headers={"Accept": "application/json"},
            timeout=10,
        )
        init_response.raise_for_status()
        action_url = init_response.json()["ui"]["action"]
        # Kratos builds `action` from its own configured public base_url,
        # which is browser-facing (127.0.0.1) and unreachable from inside
        # this container — reissue it against the docker-network-reachable
        # public_url this client was actually built with, path+query only.
        action_path_and_query = action_url[len(self._origin(action_url)):]

        submit_response = requests.post(
            f"{self._public_url}{action_path_and_query}",
            json={"method": "password", "identifier": email, "password": password},
            headers={"Accept": "application/json"},
            timeout=10,
        )
        if submit_response.status_code >= 400:
            raise KratosAuthenticationError("Kratos rejected the submitted email/password")

        return submit_response.json()

    # --- Logout ---

    def logout(self, session_token: str) -> None:
        """Revokes a Kratos session obtained via a native (non-browser) login
        (see authenticate_with_password), using Kratos's own native logout
        endpoint (DELETE /self-service/logout/api — Kratos's
        PerformNativeLogout operation; not POST). This ends that specific
        Kratos session — it does not touch Hydra; revoking the paired Hydra
        refresh token is a separate step (see HydraClient.revoke_token)."""
        response = requests.delete(
            f"{self._public_url}/self-service/logout/api",
            json={"session_token": session_token},
            headers={"Accept": "application/json"},
            timeout=10,
        )
        response.raise_for_status()

    def revoke_browser_session(self, session_id: str) -> None:
        from uuid import UUID
        session_id = str(UUID(session_id))
        response = requests.delete(f"{self._admin_url}/admin/sessions/{session_id}", timeout=10)
        if response.status_code != 404:
            response.raise_for_status()

    @staticmethod
    def _origin(url: str) -> str:
        parsed = urllib.parse.urlparse(url)
        return f"{parsed.scheme}://{parsed.netloc}"
