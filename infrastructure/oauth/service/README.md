# oauth-service

Small Python service that bridges Ory Hydra and Ory Kratos, plus a dev-only
token endpoint. No framework, stdlib `http.server` only.

## What it does

- **Bootstrap** (`services/bootstrap.py`): on startup, reconciles Hydra OAuth2
  clients and seeds a dev admin Kratos identity from
  `infrastructure/oauth/access-control.yml`.
- **Login/consent bridge** (`api/bridge_server.py`, port `4446`): implements
  Hydra's `login`/`consent` challenge URLs (`/login`, `/consent`) for the
  browser-driven authorization-code flow, plus `/healthz`.
  Consent intersects requested scopes with the YAML role mapping, the YAML
  client maximum and Hydra's current client allow-list. ADMIN `*` excludes
  `internal:provision-user`; unknown/missing roles or human clients fail closed.
  `offline_access` is granted only when requested and client-allowed. Remembered
  consent is filtered again; the dev token endpoint uses this same handler.
  This does not replace Spring's role/scope and ownership checks. Previously
  issued JWTs/refresh grants are not retroactively narrowed: revoke old grants
  and reauthenticate; a role change is evaluated at the next login/consent.
- **Internal dev token endpoint** (`api/internal_server.py`, port `4447`):
  `POST /internal/token` — takes either `{"email": ..., "password": ...}` or
  `{"kratos_session_token": ...}` (never both), drives
  the same Kratos + Hydra flow headlessly, and returns the real Hydra-issued
  JWT. Kratos still verifies the password and Hydra still signs the token —
  this service never does either itself. Strict Kratos whoami verification
  precedes every Hydra acceptance; enrolled users need AAL2.

## Layout

```
src/
  main.py               entrypoint — builds clients, runs bootstrap, starts both servers
  config.py              env-driven Config
  models.py               request/response dataclasses
  api/                     HTTP handlers (routing only)
  services/                 orchestration (bootstrap, login/consent, dev token)
  clients/                    HydraClient / KratosClient (all Hydra/Kratos HTTP calls)
```

## Local dev token

`/internal/token` is loopback-only (`compose.yml` publishes it as
`127.0.0.1:4447:4447` — not reachable from LAN or other containers):

```bash
curl -s -X POST http://127.0.0.1:4447/internal/token \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@bytecore.ee","password":"admin-dev-password"}'
```

Or with HTTPie:

```bash
http POST :4447/internal/token email=admin@bytecore.ee password=admin-dev-password
```

Returns `{"access_token": "...", "token_type": "bearer", ...}`.

## Privileged bootstrap provenance and reconciliation

Bootstrap checks Kratos **before** provisioning a canonical ADMIN. An existing
email match, even with `spring_user_id` already attached, is not ownership proof.
Only an identity created by this bootstrap with private
`metadata_admin.bootstrap = {version: 1, username, email}` and `spring_user_id`
can restart idempotently. Its schema/email and linked canonical ID/username/email/
ADMIN role must match exactly. Restart only reads the canonical user; it never
relinks, heals role drift, adopts credentials, or resets a password.

Fresh canonical creation uses the machine-authenticated
`POST /internal/users/bootstrap-admin` endpoint with `username`, `email`, and
`dateOfBirth`. The backend contract is **create-only ADMIN**, rejecting an
existing username or email without mutation, including concurrent collisions.
The legacy upsert `/internal/users` is never used by bootstrap. An older backend
without this distinct route fails closed rather than silently ignoring a
create-only option. Deploy the paired backend endpoint before fresh bootstrap.

Unmarked historical identities, stale/foreign/conflicting links, canonical
preclaims, or partial failures stop startup with a secret-free reconciliation
error. Do not automatically stamp old identities as trusted, reset their
credentials, change their role, delete shared accounts, or reset shared volumes.
A trusted operator must investigate provenance and outstanding grants separately.
If historical ownership cannot be independently established, use a new dedicated,
unclaimed bootstrap username/email and the create-only route; retain the old
state for investigation. Creation failures can leave an unlinked canonical user,
which likewise requires explicit operator reconciliation, not automatic adoption.

## TOTP runbook

### Architecture and prerequisites

Kratos `v26.2.0` owns RFC 6238 secrets, QR/setup data, enrollment, challenges,
lookup/backup codes and assurance. Spring owns canonical user IDs and roles.
Hydra owns OAuth scopes and JWT issuance. No authenticator vendor, Spring TOTP
storage, custom token issuer, frontend UI or CAPTCHA is introduced.

`highest_available` on Kratos whoami and settings preserves password-only
login for unenrolled users, while enrolled users must complete AAL2. Both the
browser bridge (including Hydra `skip`) and native token bridge enforce it.
Settings changes require a privileged session no older than **15 minutes**.
The provisioning endpoints additionally require the configured machine JWT
subject, matching `client_id`, and `internal:provision-user` scope. Configure
`OAUTH_SERVICE_CLIENT_ID` consistently for Spring and oauth-service if overriding
the default `oauth-service-internal`. The web client keeps its application scopes
and `offline_access`, but never receives the machine scope.

From the repository root:

```bash
docker compose -f infrastructure/compose.yml --env-file .env up -d --build
# Separate terminal; do not also run make dev:
cd backend && ./gradlew bootRun
```

`make dev` is the single-terminal alternative: it starts infrastructure **and**
Spring. Do not reset shared volumes. Readiness:

```bash
curl --fail http://127.0.0.1:4434/health/ready
curl --fail http://127.0.0.1:4445/health/ready
curl --fail http://127.0.0.1:4446/healthz
curl --fail http://127.0.0.1:4002/readiness
```

Hydra admin `:4445`, Kratos admin `:4434`, Mailpit UI `:8025` and SMTP `:1025`
are loopback-only; trusted containers still use their normal service addresses.
Keep host/container clocks synchronized; no custom skew window is implemented.

### Automated real flows and independent local smoke

Install dependencies in a repository-local virtual environment, not the production image:

```bash
VENV=infrastructure/oauth/service/.venv-test
python3 -m venv "$VENV"
"$VENV/bin/python" -m pip install -r infrastructure/oauth/service/tests/requirements.txt
"$VENV/bin/python" -m unittest discover -s infrastructure/oauth/service/tests -v
"$VENV/bin/python" infrastructure/oauth/service/tests/real/totp_flow_test.py -v
"$VENV/bin/python" infrastructure/oauth/service/tests/real/scope_grants_test.py -v
"$VENV/bin/python" -m infrastructure.oauth.service.tests.real.totp_smoke
cd backend && ./gradlew build
```

The real suite and independent smoke use PyOTP **only as a client authenticator**.
Kratos performs every verification. They create unique normal USER accounts via
Spring registration, never modify databases, verify Hydra's signature/issuer/
expiry using JWKS, and call protected `me` through Hive. Coverage includes native
enrollment, invalid/malformed codes, provisional-session blocking, remembered
browser login step-up, cross-user settings rejection, single-use recovery codes,
regeneration/re-reveal, secure disable, stable role/scopes, and secret-free logs.
Tokens, passwords, codes and QR/setup data remain in memory and are not printed.
Test sessions and refresh grants are revoked; disposable domain accounts remain
for inspection. Discovery intentionally excludes the opt-in `tests/real` files.
Config tests use isolated environments with all required URL fixtures; they
never depend on the developer's `.env` or another test's environment.
The scope-grants opt-in test invokes the **local source** consent handler against
live Hydra without restarting the deployed bridge. It verifies a disposable USER's
signed JWT grants, actual remembered consent, refresh grants and absence of an
unrequested refresh grant. Spring registration supplies the canonical role;
the protected machine-only canonical lookup and privileged-role accounts are
not exercised by that real test. All six role mappings and machine separation
are covered by mocked unit tests.

### Manual native flow (no frontend required)

Use a private Python REPL with the virtualenv interpreter (`"$VENV/bin/python" -i`).
The snippets below keep credentials in memory rather than command arguments or
files. Do not print complete responses, paste secrets into shell history, enable
HTTP debug logging, or put real credentials/QR data into an issue.

Register a unique USER and obtain a normal Hydra token:

```python
import getpass, requests
http = requests.Session()
http.headers["Accept"] = "application/json"
email = input("Unique test email: ")
username = input("Unique test username: ")
password = getpass.getpass("Test password: ")
r = http.post("http://127.0.0.1:8080/auth/register", json={
    "username": username, "email": email, "password": password,
    "dateOfBirth": "2000-01-01"}, timeout=15)
r.raise_for_status()
user = r.json()
assert user["role"] == "USER"
r = http.post("http://127.0.0.1:4447/internal/token",
    json={"email": email, "password": password}, timeout=15)
r.raise_for_status()
baseline = r.json()
session_token = baseline["kratos_session_token"]
headers = {"X-Session-Token": session_token}
```

Start authenticated enrollment and inspect **only the native enrollment view**:

```python
r = http.get("http://127.0.0.1:4433/self-service/settings/api", headers=headers, timeout=15)
r.raise_for_status()
flow = r.json()
nodes = {n["attributes"]["id"]: n["attributes"] for n in flow["ui"]["nodes"]
    if "id" in n["attributes"]}
qr = nodes["totp_qr"]["src"]
secret = nodes["totp_secret_key"]["text"]["context"]["secret"]
```

`qr` is Kratos's native image representation; render it locally, never upload it
to a QR conversion website. Alternatively, display `secret` privately and enter
it as a time-based, 6-digit, 30-second account in any standard authenticator app.
This release exposes QR + setup secret, not a separate promised otpauth field.
Showing setup data here is intentional; it must not appear in normal sessions,
JWTs, bridge errors or logs.

Verify a wrong/malformed code is rejected without completing enrollment, then
submit a valid code from the authenticator to the same returned `ui.action`:

```python
r = http.post(flow["ui"]["action"], headers=headers,
    json={"method": "totp", "totp_code": "malformed"}, timeout=15)
assert r.status_code == 400
r = http.post(flow["ui"]["action"], headers=headers,
    json={"method": "totp", "totp_code": getpass.getpass("Current TOTP: ")}, timeout=15)
r.raise_for_status()
```

Logout/revoke the pre-enrollment refresh grant and native session:

```python
r = http.post("http://127.0.0.1:4446/logout", json={
    "refresh_token": baseline["refresh_token"],
    "kratos_session_token": session_token}, timeout=15)
assert r.status_code == 204
```

An enrolled user's password-only `POST :4447/internal/token` must return **403**
with `error: second_factor_required` and no OAuth tokens. Complete the native
Kratos flow instead:

```python
r = http.get("http://127.0.0.1:4433/self-service/login/api", timeout=15)
r.raise_for_status()
login_flow = r.json()
r = http.post(login_flow["ui"]["action"], json={
    "method": "password", "identifier": email, "password": password}, timeout=15)
r.raise_for_status()
provisional = r.json()
session_token = provisional["session_token"]
headers = {"X-Session-Token": session_token}
r = http.get("http://127.0.0.1:4433/sessions/whoami", headers=headers, timeout=15)
assert r.status_code == 403
assert r.json()["error"]["id"] == "session_aal2_required"
r = http.get("http://127.0.0.1:4433/self-service/login/api",
    params={"aal": "aal2"}, headers=headers, timeout=15)
r.raise_for_status()
challenge = r.json()
r = http.post(challenge["ui"]["action"], headers=headers,
    json={"method": "totp", "totp_code": "malformed"}, timeout=15)
assert r.status_code == 400
r = http.post(challenge["ui"]["action"], headers=headers,
    json={"method": "totp", "totp_code": getpass.getpass("Current TOTP: ")}, timeout=15)
r.raise_for_status()
session_token = r.json()["session_token"]
headers = {"X-Session-Token": session_token}
r = http.post("http://127.0.0.1:4447/internal/token",
    json={"kratos_session_token": session_token}, timeout=15)
r.raise_for_status()
issued = r.json()
```

Do not mix session-token and email/password modes. Browser-cookie flows instead
use `/self-service/login/browser` and Kratos CSRF nodes; the bridge redirects an
insufficient session to `aal=aal2` while retaining the original Hydra challenge.
No existing frontend login page is assumed.

Verify the issued JWT and protected API without printing the token:

```python
import jwt
key = jwt.PyJWKClient("http://127.0.0.1:4444/.well-known/jwks.json").get_signing_key_from_jwt(issued["access_token"])
claims = jwt.decode(issued["access_token"], key.key, algorithms=["RS256"],
    issuer="http://127.0.0.1:4444", options={"verify_aud": False, "require": ["sub", "iss", "exp"]})
assert claims["sub"] == str(user["id"])
assert claims["role"] == "USER"
assert set(issued["scope"].split()) == set(baseline["scope"].split())
assert "internal:provision-user" not in issued["scope"].split()
r = http.post("http://127.0.0.1:4002/graphql",
    headers={"Authorization": "Bearer " + issued["access_token"]},
    json={"query": "query { me { id username email } }"}, timeout=15)
r.raise_for_status()
assert "errors" not in r.json()
assert str(r.json()["data"]["me"]["id"]) == str(user["id"])
```

### Recovery codes and full disable

Use fresh privileged settings with the completed session:

```python
r = http.get("http://127.0.0.1:4433/self-service/settings/api", headers=headers, timeout=15)
r.raise_for_status()
settings = r.json()
r = http.post(settings["ui"]["action"], headers=headers,
    json={"method": "lookup_secret", "lookup_secret_regenerate": True}, timeout=15)
r.raise_for_status()
generated = r.json()
node = next(n for n in generated["ui"]["nodes"] if n["attributes"].get("id") == "lookup_secret_codes")
codes = [entry["context"]["secret"] for entry in node["attributes"]["text"]["context"]["secrets"]]
assert len(codes) == 12
r = http.post(generated["ui"]["action"], headers=headers,
    json={"method": "lookup_secret", "lookup_secret_confirm": True}, timeout=15)
r.raise_for_status()
```

Save the initial codes privately, not in logs. On a **fresh password/AAL1 session**,
initialize `GET /self-service/login/api?aal=aal2` as above and submit
`{"method": "lookup_secret", "lookup_secret": codes[0]}` to its `ui.action`.
It must produce AAL2 once; the same code on another fresh session must fail with
400. Unconfirmed and invalid codes must fail. Regenerate + confirm replaces the
previous set. Kratos permits privileged re-reveal using
`{"method": "lookup_secret", "lookup_secret_reveal": true}`; ordinary settings,
sessions and JWTs must not reveal codes.

For full disable, perform **both** operations with a recent AAL2 session,
initializing a fresh settings flow before each:

```python
r = http.get("http://127.0.0.1:4433/self-service/settings/api", headers=headers, timeout=15)
r.raise_for_status()
settings = r.json()
r = http.post(settings["ui"]["action"], headers=headers,
    json={"method": "totp", "totp_unlink": True}, timeout=15)
r.raise_for_status()
r = http.get("http://127.0.0.1:4433/self-service/settings/api", headers=headers, timeout=15)
r.raise_for_status()
settings = r.json()
r = http.post(settings["ui"]["action"], headers=headers,
    json={"method": "lookup_secret", "lookup_secret_disable": True}, timeout=15)
r.raise_for_status()
```

Removing TOTP alone still leaves lookup codes as a second factor. Unauthenticated,
other-user, AAL1-enrolled and stale (>15m) sessions cannot change protected
credentials; follow Kratos's reauthentication/step-up response, never bypass it.
After removing both methods, fresh email/password dev-token login works normally.
End test sessions/grants with `/logout` or native
`DELETE :4433/self-service/logout/api` with `{"session_token": ...}`.

### Native limitations and manual checklist

TOTP uses Kratos's clock/window validation; this version has **no per-code
consumption ledger**, so do not claim all same-window TOTP reuse is blocked.
Lookup codes are serially single-use. Email recovery (Mailpit UI `:8025`) is a
different mechanism and does not authorize bypassing enrolled-user AAL2.

Enrollment does **not** retroactively upgrade/revoke already-issued Hydra refresh
grants. Revoke the pre-enrollment grant explicitly as shown. Existing JWTs remain
valid until their normal 30-minute expiry. Refresh/logout behavior is otherwise
unchanged; a cross-system enrollment-revocation hook is out of scope.

- [ ] Normal USER password login succeeds with correct canonical subject/role/scopes.
- [ ] Owner can obtain native QR/setup data and enroll a standard authenticator.
- [ ] Wrong/malformed enrollment and login codes fail.
- [ ] Password-only/provisional sessions cannot receive final Hydra tokens.
- [ ] Valid TOTP completes native and remembered browser OAuth login.
- [ ] JWT signature, issuer, expiry and protected Hive `me` access are valid.
- [ ] Another user/unauthenticated/AAL1/stale session cannot read or change protected settings.
- [ ] Recovery-code generate/confirm, single use, reuse failure, regeneration and privileged re-reveal behave as above.
- [ ] Normal sessions/JWTs/errors/logs do not contain secrets, codes or bearer/native tokens.
- [ ] Securely unlink both TOTP and lookup codes; fresh password login works again.
- [ ] Explicitly revoke old refresh grants and test sessions; do not reset shared databases.
- [ ] No vendor lock-in, Spring TOTP storage, custom JWT issuer, frontend changes or CAPTCHA.
