"""Task-only customer setup through the real BFF, CAPTCHA, courier and OAuth."""

from html.parser import HTMLParser
import re
import secrets
import time
from urllib.parse import parse_qs, urlparse

from altcha import Challenge, Payload, solve_challenge
import requests


STOREFRONT = "http://127.0.0.1:3300"
HYDRA = "http://127.0.0.1:24444"
KRATOS = "http://127.0.0.1:24433"
KRATOS_ADMIN = "http://127.0.0.1:24434"
HYDRA_ADMIN = "http://127.0.0.1:24445"
BRIDGE = "http://127.0.0.1:24446"
TOKEN = "http://127.0.0.1:24447/internal/token"
HIVE = "http://127.0.0.1:24002/graphql"


class _HiddenFields(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.fields = {}
        self.action = None
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form":
            self.action = attrs.get("action")
        if tag == "input" and attrs.get("type") == "hidden" and attrs.get("name") in {
            "flow", "_csrf", "csrf_token", "method", "code", "state", "scope", "iss",
        }:
            name = attrs["name"]
            assert name not in self.fields, "Unexpected duplicate security control; values suppressed"
            self.fields[name] = attrs.get("value", "")


def _status(response, expected):
    assert response.status_code == expected, "Unexpected fixture HTTP status; provider body suppressed"


def _mail_code(email):
    for _ in range(40):
        response = requests.get("http://127.0.0.1:28025/api/v1/messages", timeout=15)
        _status(response, 200)
        message = next((item for item in response.json()["messages"] if any(
            recipient["Address"] == email for recipient in item["To"]
        )), None)
        if message:
            response = requests.get(
                f"http://127.0.0.1:28025/api/v1/message/{message['ID']}", timeout=15,
            )
            _status(response, 200)
            code = re.search(r"\b[0-9]{6}\b", response.json()["Text"])
            assert code, "A genuine courier verification code is required"
            return code.group()
        time.sleep(0.5)
    raise AssertionError("Genuine verification mail was not delivered")


def register_customer(prefix):
    suffix = secrets.token_hex(6)
    email = f"{prefix}-{suffix}@bytecore.example"
    password = f"Fixture!{secrets.token_urlsafe(32)}9"
    with requests.Session() as browser:
        def request(method, url, **kwargs):
            parsed = urlparse(url)
            assert f"{parsed.scheme}://{parsed.netloc}" in {STOREFRONT, HYDRA, BRIDGE}, "Use only isolated browser origins"
            headers = {"Accept": "application/json"}
            if method == "POST":
                headers["Origin"] = HYDRA if parsed.path == "/auth/callback" else STOREFRONT
            return browser.request(
                method, url, headers=headers, timeout=20, allow_redirects=False, **kwargs,
            )

        def destination(response):
            _status(response, 303)
            return response.headers["Location"]

        def form(url):
            response = request("GET", url)
            _status(response, 200)
            controls = _HiddenFields(response.text)
            assert {"flow", "_csrf", "csrf_token", "method"} <= controls.fields.keys()
            assert controls.fields["flow"] == parse_qs(urlparse(url).query)["flow"][0]
            return controls.fields

        registration = destination(request(
            "GET", STOREFRONT + "/auth/flow?kind=registration&returnTo=%2Faccount",
        ))
        fields = form(registration)
        response = request("GET", STOREFRONT + "/auth/captcha?flow=" + fields["flow"])
        _status(response, 200)
        challenge = Challenge.from_dict(response.json())
        solution = solve_challenge(challenge)
        assert solution is not None, "The official proof-of-work solver must complete"
        fields.update({
            "traits.email": email, "traits.username": f"{prefix}{suffix}",
            "traits.dateOfBirth": "2000-01-01", "password": password,
            "altcha": Payload(challenge, solution).to_base64(),
        })
        verification = destination(request(
            "POST", STOREFRONT + "/auth/submit?kind=registration", data=fields,
        ))
        assert urlparse(verification).path == "/verify", "Registration must require genuine verification"
        fields = form(verification)
        fields["code"] = _mail_code(email)
        url = destination(request("POST", STOREFRONT + "/auth/submit?kind=verification", data=fields))
        for _ in range(16):
            parsed = urlparse(url)
            if parsed.path == "/account" and f"{parsed.scheme}://{parsed.netloc}" == STOREFRONT:
                break
            if parsed.path == "/login" and f"{parsed.scheme}://{parsed.netloc}" == STOREFRONT:
                fields = form(url)
                fields.update({"identifier": email, "password": password})
                url = destination(request("POST", STOREFRONT + "/auth/submit?kind=login", data=fields))
                continue
            response = request("GET", url)
            if response.status_code in (302, 303):
                url = response.headers["Location"]
                continue
            _status(response, 200)
            controls = _HiddenFields(response.text)
            assert controls.action == STOREFRONT + "/auth/callback"
            assert {"code", "state"} <= controls.fields.keys()
            url = destination(request("POST", controls.action, data=controls.fields))
        else:
            raise AssertionError("Real customer OAuth did not complete")
        response = request("GET", STOREFRONT + "/auth/session")
        _status(response, 200)
        session = response.json()
        assert session["kind"] == "authenticated", "Customer setup requires a real authenticated BFF session"
        grant = None
        try:
            response = requests.post(TOKEN, json={"email": email, "password": password}, timeout=20)
            _status(response, 200)
            grant = response.json()
            response = requests.post(
                HIVE, headers={"Authorization": f"Bearer {grant['access_token']}"},
                json={"query": "query { me { id username email role } }"}, timeout=20,
            )
            _status(response, 200)
            result = response.json()
            assert not result.get("errors"), "Canonical customer read failed; provider body suppressed"
            user = result["data"]["me"]
            assert str(user["id"]) == str(session["user"]["id"]) and user["role"] == "USER"
            return user, password
        finally:
            if grant:
                _status(requests.post(
                    BRIDGE + "/logout", json={"refresh_token": grant["refresh_token"],
                                             "kratos_session_token": grant["kratos_session_token"]},
                    timeout=20,
                ), 204)
            destination(request("POST", STOREFRONT + "/auth/logout", data={"_csrf": session["csrf"]}))
