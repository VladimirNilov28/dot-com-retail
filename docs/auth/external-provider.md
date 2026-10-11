# Google sign-in through Kratos

Google is an optional **Kratos credential**, not a second authentication or user
provisioning system. The storefront still finishes the existing Hydra/BFF login
and resolves its canonical user and role through Spring.

## Status and prerequisites

Configuration is disabled when both `GOOGLE_CLIENT_ID` and
`GOOGLE_CLIENT_SECRET` are absent. Supplying only one, a blank value, or a
`replace-with…` placeholder stops Kratos startup. Secrets are read only from the
Kratos container environment and rendered into an owner-only runtime file; do
not put credentials in YAML, Jsonnet, browser bundles, commits, or command logs.

The owner has supplied Google credentials in ignored local environment files.
Google authorization reached the exact BFF callback, but the latest correlated
attempt on 11 October at **19:31:04 UTC** failed in Kratos's token exchange with
`invalid_client`, before claims mapping, registration or bridge provisioning.
The rendered credentials matched the loaded root environment. A separate
direct token-endpoint diagnostic also returned HTTP 401 `invalid_client`;
it used a deliberately unissued code and is **not login evidence**.
The owner must verify the matching active secret for the same Google web client.
**Real storefront Google login, consent/cancellation completion, new/returning
customers and email-conflict behavior remain BLOCKED**, not verified by offline
tests or the authorization redirect.

The deployment must pass these optional environment variables to Kratos:

| Variable | Purpose |
| --- | --- |
| `GOOGLE_CLIENT_ID` | Google OAuth web application's client ID |
| `GOOGLE_CLIENT_SECRET` | Its private client secret |
| `GOOGLE_CALLBACK_BASE_URL` | Storefront BFF origin; defaults to `http://127.0.0.1:3000` |

Compose now explicitly forwards these optional variables to Kratos; the BFF
callback and encrypted-cookie continuity are implemented. Put real credentials
in the ignored root `.env`, register the exact URI below, then rebuild/recreate
only Kratos. Do not enable a partial configuration.

## Exact redirect URI

Installed Kratos is **v26.2.0**, commit
`9d7085948039ffb8960160d4979f71527b5cf4d5`. Its
`selfservice.methods.oidc.config.base_redirect_uri` is a base URL, **not a
replacement callback path**. With this configuration, register the following
Google OAuth authorized redirect URI exactly:

```text
http://127.0.0.1:3000/self-service/methods/oidc/callback/google
```

For production, use the HTTPS storefront origin followed by that same path.
`localhost` and `127.0.0.1` are not interchangeable. The provider uses
`pkce: never` to retain the provider-specific callback path; `pkce: force`
would instead select `/self-service/methods/oidc/callback` without `/google`.
Google's authorization-code flow still uses the client secret, state, and
Kratos-managed continuity; it is not an implicit browser-token flow.

Without `base_redirect_uri`, Kratos uses its public base URL and builds:

```text
http://127.0.0.1:4433/self-service/methods/oidc/callback/google
```

That direct callback is **not suitable for the encrypted-jar storefront**:
Kratos would not receive the provider state/continuity cookies held by the BFF.
Likewise, configuring Google to call `/auth/social-callback` does not match the
redirect URI Kratos sends or uses during token exchange. The BFF must expose the
exact appended route; it may delegate internally to its social callback handler.

## BFF integration contract

1. Initiate through a server-side Kratos browser login/registration flow. Submit
   its CSRF token, `method: oidc`, and `provider: google`. Handle Kratos's
   browser-location-change response rather than accepting arbitrary redirect URLs.
2. Preserve **all** Kratos cookies set during provider initiation in the encrypted,
   server-side flow jar, including provider continuity/state cookies. Bind the
   jar to an expiring, one-use browser correlation record. Do not expose or move
   provider cookies into ordinary browser-accessible storage.
3. At the exact BFF callback path above, correlate the original flow, then forward
   its jar and the provider callback parameters only to the configured internal
   Kratos public endpoint at
   `/self-service/methods/oidc/callback/google`. Never accept an upstream host or
   callback URL from the request. Kratos validates provider state and exchanges
   the authorization code; the BFF does not authenticate Google claims itself.
4. Capture Kratos's returned cookies into the same encrypted jar and retain it
   for any required onboarding. Allow only configured storefront/bridge redirects.
   Cancellation, missing cookies, mismatched state, and expired correlation
   must fail without producing a storefront session.
5. Finish the existing Kratos-session → bridge → Hydra authorization-code handoff.
   A Google success redirect alone is not a storefront login.

## Safe new-customer onboarding

The mapper accepts only a nonempty provider subject and a **verified** Google
email. It maps the email and matching verified-address evidence, plus private
provider provenance. It does **not** map `spring_user_id`, roles, username,
date of birth, or arbitrary provider metadata.

`customer-v1` still requires username and date of birth. New customers must supply
their real choices; no synthetic birthday or email-derived username is generated.
The BFF must collect these fields and a fresh flow-bound ALTCHA proof in the
registration flow, submitting:

```json
{
  "method": "oidc",
  "provider": "google",
  "csrf_token": "<flow CSRF token>",
  "traits": {
    "username": "<customer choice>",
    "dateOfBirth": "<customer supplied YYYY-MM-DD>"
  },
  "transient_payload": { "altcha": "<fresh flow-bound proof>" }
}
```

Google's mapper supplies the verified email. A changed email cannot pass the
Google-specific pre-persist hook's provenance check. Kratos retains registration
traits and transient payload in its provider continuity container. If login
discovers an unknown provider subject and converts to registration, the UI must
resume the registration flow and collect the missing fields/proof; do not silently
finalize the original login flow.

The OIDC registration hooks reuse the existing private, authenticated
pre-persist endpoint (including CAPTCHA consumption and canonical reservation)
and bind endpoint. Only after these succeed does Kratos issue its session.
Spring finalization requires the verified customer identity and matching
reservation and creates the normal `USER` role. There is no email-only account
adoption, provider-driven role assignment, or generic provisioning fallback.

Existing provider-linked identities log in using the Kratos provider/subject
credential. Linking an existing password account must use an authenticated
Kratos settings flow with its existing-credential/privileged-session checks.
`account_linking_mode` is explicitly `confirm_with_existing_credential`; this
option is Ory Network-specific in v26.2.0, so it is **not** a substitute for
authenticated linking in self-hosted Kratos. A matching Google email is not
permission to adopt a Spring account.

Infrastructure and BFF/UI now implement proof-backed onboarding and missing-trait
continuations. Real new/returning-account verification and authenticated account
linking remain incomplete; no self-service linking management UI is claimed.
Do not advertise mock callback checks as successful external authentication.

## Focused offline verification

Install the external-provider test dependency in the development Python
environment, alongside the OAuth service's existing test dependencies:

```bash
python3 -m pip install -r infrastructure/oauth/service/tests/requirements-google.txt
PYTHONPATH=infrastructure/oauth/service/src python3 -m unittest discover \
  -s infrastructure/oauth/service/tests -p test_google_configuration.py -v
```

These tests exercise the actual jq renderer and Jsonnet mapper/hook, disabled and
incomplete configuration, callback origin constraints, required claims, immutable
email provenance, missing customer fields/CAPTCHA, and canonical Spring role
resolution. They do not call Google or prove a live OAuth exchange.

Local verification passed 10 external-provider tests and three existing password
configuration tests. The rebuilt v26.2.0 image also started in an isolated,
network-disabled container with an in-memory database and synthetic credentials:
its health endpoints were responsive, its login flow exposed the Google provider
node, and its rendered secret-bearing configuration was owned by UID 1000 with
mode `600`. The isolated container was removed; no owner identities, databases,
volumes, or credentials were changed.

### Callback incident hardening

The BFF retains generic public errors and logs only a bounded callback stage,
HTTP status and allowlisted provider reason (`token_exchange_invalid_client`,
`token_exchange_invalid_grant`, `token_exchange_redirect_uri_mismatch`, or a
generic rejection). Raw provider bodies and credential-bearing callback URLs
are not application diagnostics. Next development request logging excludes
`/auth/*` and the OIDC callback because its default URL logging includes codes.

Authentication-entry links use native anchors: disabling Next prefetch alone
does not stop RSC/client-router interception. Initiation rejects RSC/prefetch/
fetch requests before creating a transaction; the native provider POST already
disables repeated submissions. Expired `/auth/session` polls clear only the
authenticated cookie, leaving an ongoing preauthentication cookie/jar intact;
subsequent polls are anonymous rather than repeatedly expired. Durable server
revocation is unchanged, and provider outages do not erase usable cookies.

Focused real-browser checks passed one document initiation per Sign in/Retry,
rejection of RSC/prefetch without replacing a bound flow, and stale-cookie
removal followed by anonymous polling with preauth preserved. The login 422
`browser_location_change_required` remains an expected, validated Google
authorization redirect, not an outage. No owner browser state was cleared.

Contract sources, pinned to the installed version:

- [Provider callback construction and configuration](https://github.com/ory/kratos/blob/v26.2.0/selfservice/strategy/oidc/provider_config.go)
- [Base redirect URI fallback](https://github.com/ory/kratos/blob/v26.2.0/driver/config/config.go)
- [Registration claims mapping and continuity](https://github.com/ory/kratos/blob/v26.2.0/selfservice/strategy/oidc/strategy_registration.go)
- [Existing provider-subject lookup and account conflicts](https://github.com/ory/kratos/blob/v26.2.0/selfservice/strategy/oidc/strategy_login.go)
