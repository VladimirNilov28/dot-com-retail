import time
import urllib.parse
from typing import Optional

import requests


class HydraNotReadyError(RuntimeError):
    pass


class HydraClient:
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

        raise HydraNotReadyError(
            f"Hydra at {self._admin_url} did not become ready after {attempts} attempts: {last_error}"
        )

    # --- OAuth2 client reconciliation ---

    def get_client(self, client_id: str) -> Optional[dict]:
        response = requests.get(f"{self._admin_url}/admin/clients/{client_id}", timeout=10)
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return response.json()

    def create_client(self, payload: dict) -> dict:
        response = requests.post(f"{self._admin_url}/admin/clients", json=payload, timeout=10)
        response.raise_for_status()
        return response.json()

    def update_client(self, client_id: str, payload: dict) -> dict:
        response = requests.put(f"{self._admin_url}/admin/clients/{client_id}", json=payload, timeout=10)
        response.raise_for_status()
        return response.json()

    # --- Login/consent challenge (bridge) ---

    def get_login_request(self, login_challenge: str) -> dict:
        response = requests.get(
            f"{self._admin_url}/admin/oauth2/auth/requests/login",
            params={"login_challenge": login_challenge},
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    def accept_login_request(
        self, login_challenge: str, subject: str, context: Optional[dict] = None
    ) -> dict:
        payload = {"subject": subject, "remember": True, "remember_for": 3600}
        if context is not None:
            payload["context"] = context

        response = requests.put(
            f"{self._admin_url}/admin/oauth2/auth/requests/login/accept",
            params={"login_challenge": login_challenge},
            json=payload,
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    def get_consent_request(self, consent_challenge: str) -> dict:
        response = requests.get(
            f"{self._admin_url}/admin/oauth2/auth/requests/consent",
            params={"consent_challenge": consent_challenge},
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    def accept_consent_request(
        self,
        consent_challenge: str,
        grant_scope: list,
        grant_access_token_audience: list,
        access_token_claims: Optional[dict] = None,
    ) -> dict:
        payload = {
            "grant_scope": grant_scope,
            "grant_access_token_audience": grant_access_token_audience,
            "remember": True,
            "remember_for": 3600,
        }
        if access_token_claims is not None:
            payload["session"] = {"access_token": access_token_claims}

        response = requests.put(
            f"{self._admin_url}/admin/oauth2/auth/requests/consent/accept",
            params={"consent_challenge": consent_challenge},
            json=payload,
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    # --- Headless authorization-code + PKCE (dev token issuance) ---

    @staticmethod
    def new_authorization_session() -> requests.Session:
        """Hydra ties the /oauth2/auth flow to a CSRF cookie set on its first
        response; every subsequent hop in the same flow must present it back.
        A browser does this for free — the headless flow needs an explicit
        cookie jar shared across start_authorization/follow_redirect."""
        return requests.Session()

    def start_authorization(
        self,
        session: requests.Session,
        client_id: str,
        redirect_uri: str,
        scope: str,
        code_challenge: str,
        state: str,
    ) -> str:
        """Starts an authorization-code request against Hydra's public endpoint,
        without following redirects. Returns the Location header — the same
        login-challenge redirect Hydra would otherwise send a browser to."""
        response = session.get(
            f"{self._public_url}/oauth2/auth",
            params={
                "client_id": client_id,
                "response_type": "code",
                "redirect_uri": redirect_uri,
                "scope": scope,
                "state": state,
                "code_challenge": code_challenge,
                "code_challenge_method": "S256",
            },
            allow_redirects=False,
            timeout=10,
        )
        return self._require_location(response)

    def follow_redirect(self, session: requests.Session, url: str) -> str:
        """Follows a single redirect hop without allowing further redirects.
        Used to walk the login -> consent -> client-redirect chain server-side,
        in place of a browser. `url` always comes from Hydra's own admin API
        (login/consent accept `redirect_to`), so it always targets Hydra
        itself next — but built from Hydra's configured, browser-facing
        issuer (127.0.0.1), unreachable from inside this container. Reissue
        it against this client's own docker-network-reachable public_url."""
        rewritten = self._rewrite_origin(url, self._public_url)
        response = session.get(rewritten, allow_redirects=False, timeout=10)
        return self._require_location(response)

    def exchange_code_for_token(
        self, client_id: str, code: str, redirect_uri: str, code_verifier: str
    ) -> dict:
        response = requests.post(
            f"{self._public_url}/oauth2/token",
            data={
                "grant_type": "authorization_code",
                "client_id": client_id,
                "code": code,
                "redirect_uri": redirect_uri,
                "code_verifier": code_verifier,
            },
            timeout=10,
        )
        response.raise_for_status()
        return response.json()

    @staticmethod
    def _rewrite_origin(url: str, new_origin: str) -> str:
        parsed = urllib.parse.urlparse(url)
        path_and_query = url[len(f"{parsed.scheme}://{parsed.netloc}"):]
        return f"{new_origin}{path_and_query}"

    @staticmethod
    def _require_location(response: requests.Response) -> str:
        response.raise_for_status()
        location = response.headers.get("Location")
        if not location:
            raise RuntimeError(f"expected a redirect from {response.url}, got {response.status_code}")
        return location


def parse_query_param(url: str, name: str) -> str:
    query = urllib.parse.urlparse(url).query
    values = urllib.parse.parse_qs(query).get(name)
    if not values:
        raise RuntimeError(f"expected query parameter {name!r} in redirect: {url}")
    return values[0]
