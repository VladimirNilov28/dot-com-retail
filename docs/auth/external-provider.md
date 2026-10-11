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
Google authorization reached the exact BFF callback. An earlier correlated
attempt on 11 October at **19:31:04 UTC** failed in Kratos's token exchange with
`invalid_client`, before claims mapping, registration or bridge provisioning.
The rendered credentials matched the loaded root environment. A separate
direct token-endpoint diagnostic also returned HTTP 401 `invalid_client`;
it used a deliberately unissued code and is **not login evidence**.
After the owner supplied the matching secret, fresh callbacks at **19:45:18
and 19:45:32 UTC** passed token exchange and reached registration schema
validation. Kratos returned HTTP 400 with its native registration flow because
the verified Google email did not supply the required username/date of birth.
The BFF previously treated every non-redirect callback as a failed login,
discarding this continuation and returning the owner to sign-in.
It now validates and rereads the provider-issued registration flow using the
same jar, binds it to the existing browser and shows the required fields and
fresh CAPTCHA. A schema-validation form is not a completed authentication.
Real storefront Google login, consent/cancellation completion, new/returning
customers and email-conflict behavior remain **pending real owner verification**,
not verified by offline tests or the authorization redirect.
The latest #70 implementation passed 93 focused BFF regressions, eight private
bridge/status regressions, TypeScript checking and an isolated production build.
Lint reported no errors and one unchanged homepage warning. A disposable real
customer completed native registration/email verification/password login,
reload persistence and logout; authenticated settings linking reached Google.
Consent was not completed, so this is **initiation evidence, not Google login
or completed credential-link evidence**. No owner password or account was reset.

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

The completion page says **Complete your registration** and displays the
server-trusted Google email read-only, without a submitted email control.
The BFF rejects posted email, password, role and other unapproved fields rather
than silently accepting them. Missing username/date of birth on initial arrival
are not presented as a failed submission. After an actual submission, concise
field errors and entered values survive through the encrypted browser context;
stale initial required-field messages are not applied to corrected fields.
CAPTCHA retry replaces only the widget, retaining entered fields and the bound
provider flow. An expired flow still requires an explicit fresh initiation.
Only supported trusted values are prefilled; no username or birthday is invented.

Google's mapper supplies the verified email. A changed email cannot pass the
Google-specific pre-persist hook's verified-address check. The mapper still
requires Google's verified email and stable subject and records private
provenance; Kratos applies matching verified-address evidence before invoking
the hook. The hook requires exactly one completed, verified email address
matching the registration traits. It must not read `metadata_admin` from its
context: v26.2.0's public `Identity.MarshalJSON` intentionally omits that field
even in the pre-persist webhook template. Kratos retains registration
traits and transient payload in its provider continuity container. If login
discovers an unknown provider subject and converts to registration, the UI must
resume the registration flow and collect the missing fields/proof; do not silently
finalize the original login flow.

After this missing-trait form is submitted, Kratos's native OIDC registration
strategy initiates another Google authorization with fresh state/continuity,
carrying the entered profile and CAPTCHA proof. This second Google hop is
expected, not another ByteCore login or duplicate customer creation. Kratos
v26.2.0's browser OIDC registration cannot complete the stored first callback
without it (the `id_token` shortcut exists only for native SDK clients). The BFF
sends `upstream_parameters.login_hint` with the trusted verified email so the
second hop normally reuses the same Google session without account re-selection.
The final action is labelled **Complete registration**. Returning connected
users are resolved by provider subject and never see registration completion.

Before showing the completion form, and again before the second Google hop, the
BFF asks the bridge's private `/internal/registration/availability` route
(Spring `POST /internal/users/registration-availability`, machine-caller only,
read-only, no locks or reservations). It checks the **trusted bound verified
email** — never a browser-supplied value — and, only on submit, the chosen
username against canonical users and live reservations:

- Email already owned by another account → the Google continuation is discarded
  and the user is sent to existing-account sign-in (`social=conflict`), then
  Connect Google from Account security. No registration, merge or link occurs.
- Username taken → a field error ("This username is already taken. Choose
  another.") with entered values preserved, and no provider mutation.

The pre-persist reservation remains the authoritative race-safe check; the
availability gate only moves known conflicts ahead of user effort.

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

Successful native registration callbacks can return HTTP 200 identity JSON
rather than a redirect. The BFF proceeds to `/auth/resume`, where the existing
bridge verifies the live Kratos session, canonical provisioning, verified
identity and required assurance before Hydra/BFF session creation. AAL2
continuations remain real second-factor flows, never successful AAL1 logins.
Validated `returnTo` is retained server-side through this handoff.

### Existing-account linking and disconnecting

The customer **Account security** page includes a compact Google section with
connection status and provider-issued Connect/Disconnect controls. Kratos
v26.2.0's self-hosted OIDC settings strategy supports `method: oidc`,
`link: google` and `unlink: google`; it is independent of the Network-only
`account_linking_mode` option.

Both initiation and callback require the same authenticated customer identity,
canonical Spring user, role and provider session. Mutations require authentication
less than 15 minutes old and AAL2 when an authenticator/backup factor is enabled.
Google account selection uses the provider's `select_account` prompt.
The encrypted jar, outer state and expiring browser/settings-flow binding remain
mandatory. Duplicate initiation is rejected while authorization is pending;
consumed callbacks cannot be replayed. No new Hydra grant or canonical user is
created by linking, and traits/account data are not submitted for modification.

Kratos's credential uniqueness prevents transferring a provider/subject already
connected elsewhere. The UI offers Disconnect only when Kratos issues an unlink
control, and the BFF rejects forged actions absent from the current flow.
Kratos also enforces its active-first-factor count during mutation, so an
authenticator or backup code alone cannot make the last Google credential
removable. Public identities omit credential configuration: the private
`/internal/google-connection` endpoint resolves the authenticated canonical
customer and returns only connection status plus the existing safe session
coordinates. Credential configuration, provider subjects and tokens never reach
the UI. This check identifies Google-only connections even without an unlink node.

Cancellation/rejection returns to security settings without a success claim;
expired or insufficient-assurance sessions require fresh authentication.
Matching-email registration conflicts offer existing-account sign-in followed
by an explicit Connect Google action from settings. Provider duplicate-credential
login continuations are discarded in favor of a fresh flow; the user is clearly
asked to restart linking from settings. Matching email never silently merges,
adopts or links accounts.

### Evidence and remaining owner-assisted checks

Automated regressions cover native 400 onboarding/200 completion, immutable
email and profile-value retention, conflict recovery, settings mutation payloads,
same-account callback coordinates, AAL2/privileged-age enforcement, cancellation,
foreign identities, one-use callback replay and last-method unlink rejection.
Real self-hosted linking initiation and ordinary customer session regression
passed. Desktop/mobile production captures of only the Google settings section
were inspected; 200% text zoom had no horizontal overflow. MFA secrets were not
captured. The earlier whole-page #69 captures are historical after this section
was added, not current Google UI evidence.

Still requiring real owner Google consent: new Google customer completion and
automatic login; actual password-account linking and subsequent Google login to
the same canonical user; returning login/reload/logout; matching-email and
already-connected-elsewhere rejection; unlink with another method and Google-only
last-method rejection; enrolled-account Google login with a real second factor.
No successful live Google login is claimed. Keep #70 open/In Progress until
these outcomes are observed; offline fixtures are not provider verification.
The implementation is local commit `b8fc4bb`; it has not been pushed.

Focused BFF checks (from `frontend/`):

```bash
bun --conditions=react-server test src/lib/auth/{social,settings,flows,view,http,model}.test.mjs
bun run typecheck
bun run lint
```

Private endpoint/client checks (with the OAuth service test dependencies):

```bash
PYTHONPATH=infrastructure/oauth/service/src:infrastructure/oauth/service/tests \
  python3 -m unittest test_google_connection test_auth_http_errors -v
```

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

An owner attempt on 11 October at **20:26:34 UTC** confirmed a separate
registration-hook defect: Google exchange/profile mapping succeeded, but
Jsonnet failed with `Field does not exist: metadata_admin` before the bridge
pre-persist request. The old regression fixture passed the raw mapper output
instead of Kratos's public webhook identity, masking this failure. The corrected
hook uses the provider-applied verified-address evidence described above, with
subject/verified-email checks still enforced by the mapper and CAPTCHA/canonical
reservation unchanged. Eleven focused renderer/mapper/hook tests pass, including
the real public projection and missing/unverified/mismatched/duplicate-address
rejection. Only Kratos was rebuilt/recreated; its deployed template matches the
source and Kratos/bridge health are responsive. Post-fix real Google completion
still requires the owner's fresh browser attempt.

Follow-up callbacks at **20:31:01 and 20:32:04 UTC** passed that address check
but exposed a second template defect: `import not available
registration-hook.jsonnet`. Kratos's restricted webhook Jsonnet VM does not
permit imports; the earlier local evaluator did. The Google hook is now
self-contained. An imports-disabled regression reproduced the actual failure
before the fix, and twelve focused tests now pass, including payload parity
with the native registration hook for valid/missing CAPTCHA payloads. The
small duplicated payload mapping is intentional because this runtime cannot
import the native template; the parity test prevents drift. Only Kratos was
rebuilt/recreated again. This fixes the hook failure, not the documented second
authorization hop; fresh real storefront session completion remains unverified.

Owner attempts at **20:35–20:40 UTC** reached the completion form, submitted
it, passed the second Google hop, then saw "This email or username cannot be
registered". Sanitized bridge/Kratos logs and record inspection (booleans and
timestamps only) showed this was a **genuine canonical conflict**, not a lost
continuation, wrong form action or duplicate provisioning: one Google email
already belongs to an existing password identity (not Google-linked), and the
other attempt chose a username held by an existing account's reservation. The
pre-persist reservation correctly returned 409; no reservation, OIDC credential
or Spring user was created, so there are no partial records to recover. The
defect was detecting the conflict only after profile entry and the second hop
and surfacing it generically. The availability gate above fixes this.

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

Native callback HTTP 400 registration forms are a supported continuation, not
an outage: Kratos validates Google state/claims before producing them. The BFF
accepts only browser flows with the exact configured registration action/ID,
rereads that persisted flow using the current encrypted provider jar and binds
the ID before routing to `/register`. It does not synthesize traits, accept
arbitrary flow IDs, suppress CAPTCHA or infer session success from a form.

Contract sources, pinned to the installed version:

- [Provider callback construction and configuration](https://github.com/ory/kratos/blob/v26.2.0/selfservice/strategy/oidc/provider_config.go)
- [Base redirect URI fallback](https://github.com/ory/kratos/blob/v26.2.0/driver/config/config.go)
- [Registration claims mapping and continuity](https://github.com/ory/kratos/blob/v26.2.0/selfservice/strategy/oidc/strategy_registration.go)
- [Existing provider-subject lookup and account conflicts](https://github.com/ory/kratos/blob/v26.2.0/selfservice/strategy/oidc/strategy_login.go)
- [Self-hosted linking, privileged sessions and last-first-factor unlink protection](https://github.com/ory/kratos/blob/v26.2.0/selfservice/strategy/oidc/strategy_settings.go)
- [Native registration JSON completion](https://github.com/ory/kratos/blob/v26.2.0/selfservice/flow/registration/hook.go)
