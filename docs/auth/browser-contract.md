# Storefront browser authentication (#40, #66, #67)

This is the implementation contract for the coordinated authentication package,
not a claim of completed verification or owner visual approval. Delivery reports
record the implemented revision and actual checks. Social login, profile editing,
password recovery UI and customer TOTP management remain separate work.

## Authorities and origins

Kratos owns passwords, browser sessions, email verification and factors. Hydra
owns OAuth/OIDC authorization and tokens. Spring owns canonical users, roles,
scopes and ownership. Next.js is a BFF, not an identity or JWT issuer.

Local browser origins use **127.0.0.1** consistently: storefront `:3000`,
Kratos `:4433`, Hydra issuer `:4444`, and bridge `:4446`. Internal container
addresses are separate. Existing `bytecore-web`/localhost:4200 dev-token tooling
is preserved; the browser uses a distinct confidential `bytecore-storefront`
client, exact `/auth/callback`, `client_secret_basic`, and only `openid`,
`offline_access`, and `user:read`.

Production requires HTTPS and same-site HTTPS storefront/Hydra origins for a
SameSite=Lax form_post callback. Use fixed configured public origins, trusted
private upstreams and TLS; never disable certificate validation. Local HTTP is
an explicit loopback development policy, not a production fallback.

## Browser boundary

The browser receives opaque HttpOnly host-only session identifiers, not OAuth
tokens or Kratos session credentials. Production cookies are Secure,
SameSite=Lax, Path=/ and use `__Host-` names. Local HTTP uses separately named
cookies. Authentication rotates the identifier; logout invalidates it.

Durable PostgreSQL storage holds hashed identifier lookups and encrypted OAuth
tokens, PKCE verifiers, nonces and provider cookie jars. Encryption uses a
maintained authenticated-encryption library and a versioned server key ring.
Runtime database permissions exclude schema changes; migrations and bounded
expiry/revocation maintenance are explicit deployment steps. Process-local
memory is not a production session or replay store.

Absence of a cookie does not make anonymous browsing depend on auth services.
An invalid/expired cookie differs from an auth-service outage. Private account
projections and all auth responses are no-store/noindex; public Hive operations
remain credential-free and retain their public data caches.

Next.js drives real **Kratos browser** flows with a server-held cookie jar.
Provider hidden/CSRF nodes are rendered correctly; cookie-authenticated BFF
submissions additionally validate Origin and a browser-context-bound CSRF token.
Flow IDs must belong to that context. Only configured provider endpoints and
validated paths/redirects are followed; no arbitrary `ui.action` URL fetching.

The private bridge handoff validates real highest-available Kratos assurance,
canonical identity linkage and requested client. Public callers cannot use that
handoff. Browser roles, identity IDs or session claims are not proof.

OAuth uses maintained openid-client facilities for S256 PKCE, state, OIDC nonce,
issuer/audience/signatures, exchange, refresh and revocation. Transactions are
browser-bound, short-lived and atomically single-use. Form_post keeps codes out
of callback URL queries. This protocol callback is an explicit exception to
ordinary form CSRF checks, protected by its bound state/PKCE/nonce checks.
Return destinations are validated relative storefront routes, never arbitrary
absolute URLs, internal API/auth paths or redirect loops.

## Registration and canonical linkage

The new versioned customer schema requires email, password, username and date
of birth. Existing default-schema identities remain unchanged. Roles/scopes
cannot be submitted as traits. Kratos remains the password-policy authority.

### Verification code and email-link contract

Kratos **v26.2.0** is configured with verification **`use: code`**, not the
separate token-link strategy. Registration's `show_verification_ui` continuation
supplies the verification flow ID; the BFF fetches and binds that exact flow
to the originating browser and retains its provider cookie jar.

The upstream default email contains both a six-digit code and a provider GET
link carrying the same code and flow. That GET does **not** verify the identity:
it prefills the provider UI and regenerates the flow's CSRF token/cookie.
Navigating directly from email bypassed the BFF jar, leaving it with the old
cookie. The next BFF flow read was rejected by Kratos's CSRF binding check;
the storefront also intentionally omitted provider-prefilled sensitive values.
Fresh manually entered codes worked before the correction; a general fresh-code
failure was not reproduced. Do not infer that every rejected email was expired.

The local Kratos image now includes custom HTML/plaintext verification-code
templates. Both point to the canonical storefront `/verify?flow=...#code=...`.
The fragment is read only in the browser, validated as exactly one six-digit
string, removed from the address bar, and used to fill the native code input.
It is not sent in an HTTP query, logged by the BFF, or automatically consumed.
The user chooses **Verify email**, which uses the existing CSRF-protected native
POST and bound server-side provider jar. The code remains a string, including
leading zeroes. No identity is marked verified outside Kratos.

Links require the originating browser's active storefront binding. Another
browser receives guidance to return to that browser or start a new verification
form and request a code. This does not consume the proof. Without JavaScript,
the email instructs the user to enter the displayed code manually; native code
submission and resend still work. Previously issued default provider links are
historical and are not rewritten: use a new email/new verification form if
one already invalidated its old BFF binding.

**Send a new code** uses Kratos's allowlisted email submit control on the same
flow. The BFF sends **email or code, never both**; a stale code in the input
cannot turn a resend into a proof submission. Resend replaces the previous
proof, so use the latest email for that particular form. Provider expiry remains
**15 minutes**; it was not extended. Incorrect/superseded proof rejection,
completed-flow state, expired flow and service/session failures remain distinct.
Safe flow-read diagnostics distinguish `provider_csrf_binding`,
`flow_unusable` and `provider_unavailable` without raw provider bodies or proofs.

Real local-stack checks covered genuine latest code and link, verified-customer
login, wrong code, resend/old-code rejection, actual stored consumption and
HTTP 400 replay rejection, foreign-browser recovery, and no-JavaScript code
submission. Expiry was tested by shortening only one disposable flow's provider
proof lifetime; it was rejected with new-flow recovery. A temporary invalid jar
on one disposable browser session produced service-unavailable rather than an
incorrect-code message; the original jar was restored and the same proof then
completed native verification. No owner session or provider service was disabled.

To load template/image changes, rebuild/recreate **only Kratos** using the local
Compose command below. The templates currently match this repository's explicit
local `127.0.0.1:3000` UI configuration. A production deployment must update both
the configured storefront origin and the templates' storefront link origin
together; do not ship the local host or inherit development overrides.

### Password guidance and safe validation

The **Kratos v26.2.0** policy and real responses were inspected on 2026-10-10.
The base configuration explicitly keeps the minimum at **8 UTF-8 bytes**,
identifier-similarity checking enabled and breached-password protection enabled
(HIBP threshold zero). The deployed bcrypt hasher limits passwords to **72 UTF-8
bytes**. There is no mandatory lowercase/uppercase/digit/symbol mix. Go's
byte-length checks differ from the upstream wording "characters";
emoji and accented characters can occupy multiple bytes. Actual provider
responses confirmed minimum, maximum, similarity and breached-password
rejections (`4000032`, `4000033`, `4000031`, `4000034` respectively).

The breach message comes from Kratos's HIBP validation (`4000034`), not a
comparison with other users' passwords. Distinct users may use the same
policy-compliant password. Neither the BFF nor Spring hashes, stores or compares
credentials; Kratos owns credential storage and authentication.

**Local development only:** `infrastructure/compose.yml`, the documented local
stack, explicitly sets
`SELFSERVICE_METHODS_PASSWORD_CONFIG_HAVEIBEENPWNED_ENABLED: "false"` on Kratos.
This is the supported v26.2.0 provider override and disables the actual HIBP
check, not just its error display. It does not override the minimum, bcrypt
maximum, similarity, CAPTCHA, verification or authentication checks. Apply a
configuration/image change to an existing local stack with:

```bash
docker compose -f infrastructure/compose.yml --env-file .env up -d --no-deps --build kratos
```

No database reset or volume deletion is needed. **Production must not inherit
this local Compose override.** The base `infrastructure/oauth/kratos/kratos.yml`
and provider defaults retain `haveibeenpwned_enabled: true`; omit the
development environment override (or explicitly set it to `true`) in production.
Do not determine this setting from frontend `NODE_ENV`, browser data or headers.
The retained upstream default ignores HIBP network errors.

Before changing Kratos versions, policy or hashers, re-inspect the
effective configuration and update `frontend/src/lib/auth/password.ts`, the
rendered guidance and its tests together. Do not copy generic composition rules
from a password-strength library into acceptance requirements.

Registration renders one compact advisory line before submission:
“Recommended: Use lowercase and uppercase letters, numbers and symbols. Minimum
8 UTF-8 bytes.” Composition is advice, not a new acceptance requirement.
The line stays visible after rejection and without JavaScript. Live feedback
adds a concise error only for locally checkable byte-length violations.
The Next.js BFF reuses the same byte bounds before forwarding registration
to Kratos, so bypassing browser validation cannot bypass the BFF policy.
Direct provider registration remains protected by Kratos's own minimum and
bcrypt limit; the BFF is not the only enforcement point.
A lazy-loaded, browser-local `@zxcvbn-ts` estimator supplies a compact labelled
strength meter only as guidance, never an acceptance verdict. Passwords remain
in the native form field; strength evaluation does not log/persist them or call
an external service. Existing password-manager autocomplete and paste remain
enabled. Show/hide is a keyboard-accessible button and is omitted without
JavaScript; the field stays masked.

Provider validation follows Kratos → BFF flow validation → safe form projection.
Registration hook messages follow bridge → Kratos's preserved UI messages →
the same projection. Only recognized message IDs, bounded numeric length
limits and exact allowlisted hook messages reach the form. Arbitrary provider
text, exception messages, payloads and URLs never reach the browser. Password
errors retain actionable reasons; system errors are form-level temporary-service
failures, not password failures. Login credential/account-not-found errors share
the same generic wording. Unexpected provider/bridge failures emit bounded
server-side reason codes without response bodies or request credentials.

Failed native submissions return a focused error summary with links to affected
fields without duplicating the inline error text. BFF length rejection uses only
an allowlisted `minimum`/`maximum` reason in the native redirect, never a password;
it renders only after the normal browser-bound flow validation. Invalid CAPTCHA, expired browser-bound
flows and unavailable services have separate recovery guidance. CAPTCHA
challenge HTTP 401/403/410 offers a new registration flow; HTTP 5xx offers a
delayed check reload. A provider password rejection still requires the normal
fresh-widget retry; CAPTCHA proof/replay semantics are unchanged.

Production-build browser verification used the real existing local providers
and PostgreSQL. A temporary loopback proxy forwarded only the canonical
storefront origin to an isolated production build, preserving the owner's dev
server and exact OAuth callback. Before applying the development override, real
direct-provider requests confirmed minimum enforcement and HIBP-only rejection.
After applying it, direct requests still rejected an invalid CAPTCHA proof and
an under-minimum password. Two distinct disposable users registered with the same
formerly HIBP-only-rejected password using genuine ALTCHA and the existing email
verification flow, then logged in, reloaded and logged out successfully.
BFF minimum/maximum bypass tests, preserved provider similarity rejection,
invalid CAPTCHA, local text-labelled strength, paste/keyboard visibility,
mobile layout, server-rendered errors and native login without
JavaScript. Hydra's existing no-JS continuation button completes form_post.
Registration still requires JavaScript for the official ALTCHA widget; the
server-rendered warning states that limitation rather than bypassing it.
The designated existing account still logs in and out; its test session exercised
actual Hydra refresh-token rotation, completed logout revocation and rejection
of both the revoked refresh grant and provider session. Canonical `127.0.0.1`
development HMR received WebSocket frames. Production policy retention was
verified in the base configuration and regression tests, not by deploying a
production stack. No email infrastructure or delivery investigation was performed.

Expiry was additionally exercised by expiring only one newly created test
browser's durable flow binding: challenge retrieval returned `401 expired` and
the form offered a registration restart. Service recovery used a controlled
browser HTTP 503 on challenge retrieval and the real unavailable-page template,
not an outage imposed on owner services. Desktop/mobile captures and their
provenance are recorded in the frontend baseline manifest; visual owner approval
is still pending.

### CAPTCHA and canonical linkage

Self-hosted ALTCHA PoW-v2 uses the official widget/verifier, signed expiring
flow-bound challenges and durable atomic replay protection. No mock CAPTCHA,
client-success flag or testing bypass is supported. Cookie-authenticated
challenge issuance is bounded and associated with a real registration flow.

An authenticated interruptible **after/password** webhook validates CAPTCHA
before Kratos persists an identity. A before-registration hook is unsuitable:
it runs when the flow starts, before submitted transient data exists.
Webhook payloads exclude passwords, cookies and full UI objects.

After verification of the proof, Spring reserves username/email and required
traits under a unique registration reference. Canonical writers respect those
claims transactionally. The hook writes its reservation reference into private
admin metadata; post-persistence binding records the actual Kratos UUID.
Verified login can reconcile an interrupted bind from that private proof.

Registration does **not** create a browser session or an OAuth token. Real
courier verification precedes password login and OAuth. Finalization validates
the authoritative identity, verified email and exact reservation, creates only
a USER and commits reverse UUID linkage atomically. Metadata repair and retries
are idempotent. Never adopt an existing canonical user by email or reuse the
role-changing internal provision upsert.

A separate nullable UNIQUE canonical UUID supports the new native path.
`deletion_identity_id` retains its deletion-only meaning. Existing trusted
metadata links and deletion fallback are preserved; historical backfill and
full cross-writer lifecycle work in #51 are not implicitly complete.

An unverified persisted account retains its name reservation and cannot obtain
OAuth. Abandoned pre-persistence reservations may be released only after expiry
and an authoritative check establishes no matching private identity proof.
Ambiguous/provider-failure checks retain claims and surface reconciliation
failure; they never delete or adopt owner identities.

Legacy Spring POST `/auth/register` returns an explicit 410 without creating
users/identities. Native/legacy-schema registration cannot bypass the hook.
Existing native login and dev-token tooling are not retired.

## Lifecycle and limitations

Protected BFF use/refresh requires a real active sufficient-assurance provider
session. Serialize refresh across processes with durable locking, bounded
timeouts and atomic rotated-token storage. Invalid or ambiguous refresh requires
reauthentication, not use of an expired token. SSR can refresh server-held tokens
without mutating cookies; cookie changes occur at route-handler boundaries.

Logout is CSRF-protected POST. Invalidate BFF state immediately, clear cookies,
end the paired Kratos session and revoke the Hydra refresh grant. An upstream
failure cannot leave BFF access alive or claim provider logout succeeded;
durable pending revocation supports bounded maintenance/retry.

Spring remains stateless. Already-issued JWTs can remain valid until their
configured expiry; no global immediate revocation is promised. Role/assurance
changes require current checks and fresh authorization. Guest cookies retain
Path=/graphql and are not repurposed as BFF authentication.

Login, existing-factor challenges, verification and logout use server forms.
The ALTCHA widget requires JavaScript: no-JS registration explains that
requirement and retains usable sign-in/store navigation, never a CAPTCHA bypass.
No cart, checkout, wishlist, social login, profile editor or account dashboard
is introduced by this package.

## Implemented protocol and recovery

Hydra's shared login/consent URLs still target the bridge. The bridge reads the
actual Hydra client and dispatches **only** `bytecore-storefront` to the fixed
storefront `/auth/challenge` and `/auth/consent` routes. Other existing clients
retain their Kratos-cookie/native bridge behavior. Browser requests cannot
submit provider credentials to the BFF dispatch; private handoffs require
the private server-held Authorization bearer secret and the real
challenge/client/state. Standard Authorization redaction is essential: Kratos
v26.2.0 logs arbitrary custom webhook header values even with
`leak_sensitive_values: false`. Never substitute a custom secret header.

Hydra v26.2.0's form_post document automatically submits with JavaScript but has
no configurable native continuation. The pinned Nginx public proxy adds only a
`noscript` **Continue to ByteCore** submit button to `/oauth2/auth`. It preserves
the original action, hidden fields and JavaScript, disables access logs and
forwards only Hydra's public service. This is not a replacement OAuth exchange
or an admin proxy. Both successful and denied actual responses were checked.

Flow IDs, OAuth state and provider challenge identifiers are intentional public
protocol coordinates, not access/refresh/session credentials. Callback codes
travel in POST bodies, not storefront URLs. The callback accepts absent or
`null` Origin only with the browser-bound single-use state, PKCE and nonce;
an explicitly foreign Origin is rejected before consuming the transaction.
Other native POSTs require the exact configured storefront Origin and BFF CSRF.
Auth/private HTML uses `Referrer-Policy: same-origin`: blanket `no-referrer`
would turn ordinary Chromium form Origins into `null` and break that boundary.

Every form action/restart preserves the validated return destination and flow
kind. Bound server context takes precedence over untrusted recovery queries.
Invalid IDs, expiration and provider unavailability have distinct recovery.
Verification-email notices are not validation errors. Credential fields are
never echoed. Native controls retain provider nodes/CSRF and password-manager
semantics; HeroUI actions, existing field tokens and explicit accessible
label/error wiring preserve the storefront convention.

`/account` is only authentication status and logout, not a dashboard. Header
account status is an uncached safe projection from `/auth/session`; the actual
caller-owned `me` query uses the typed **server-only** authenticated transport
through Hive. Browser Authorization and arbitrary GraphQL documents remain
rejected. Public catalog operations never inherit BFF cookies or tokens.

## Configuration and deployment

See root and frontend `.env.example`; supply real independent secrets privately.
None of these variables is `NEXT_PUBLIC_*`. Frontend requires
`STOREFRONT_ORIGIN`, `AUTH_DATABASE_URL`, `AUTH_OAUTH_ISSUER`,
`AUTH_KRATOS_URL`, `AUTH_BRIDGE_URL`, `AUTH_CLIENT_ID`, `AUTH_CLIENT_SECRET`,
`AUTH_BRIDGE_SECRET` and `AUTH_SEAL_KEYS` for authentication.
The client/bridge secrets must match root `BFF_CLIENT_SECRET` and
`AUTH_BRIDGE_SECRET`; `BFF_REDIRECT_URI` is exactly the fixed storefront origin
plus `/auth/callback`. CAPTCHA uses its own secret and database role.

### Local startup and `start unavailable`

Copying the examples is not sufficient to enable authentication. Before starting
the frontend, replace missing or `replace-with...` development values in ignored
local environment files only. Reuse valid root `BFF_CLIENT_SECRET` and
`AUTH_BRIDGE_SECRET` in the frontend; the password in `AUTH_DATABASE_URL` must be
the actual PostgreSQL runtime-role password, not the migration-role password or
an example value. Independently check root `CAPTCHA_DATABASE_URL`: its password
must match the real `CAPTCHA_DB_PASSWORD` for the CAPTCHA role. Changing the role
password setting does not rewrite a copied DSN; recreate only `oauth-service`
after correcting its DSN so the running bridge loads the change. A valid
`CAPTCHA_SECRET` alone does not establish CAPTCHA storage readiness.
Generate cryptographically random values only for missing or
placeholder secrets. Replace a placeholder seal key with a random 32-byte
base64url key under its existing ID; preserve every valid key and the active ID.
Never print resolved Compose configuration or credentials while diagnosing this.

An anonymous `GET /auth/session` intentionally returns `200` without validating
authentication configuration, opening PostgreSQL or contacting providers. It is
not an authentication readiness check. `GET /auth/start` validates configuration
first, then persists encrypted browser/OAuth transaction state, then performs
OAuth discovery. Placeholder client/bridge secrets or a seal key therefore fail
before database access or discovery, with the generic `unavailable` error.
A database connection failure or provider outage can produce the same public
error; do not diagnose the stage from latency or status alone.

For existing volumes, inspect the database, all three roles, table ownership and
runtime grants before provisioning. If they already exist, do not rerun the
create-role script, reset passwords, recreate the database or delete volumes.
Apply only missing setup after operator review. Reconcile an existing Hydra
client secret only as a deliberate coordinated change: restarting the bridge
does not rotate it. Verify the registered callback is exactly
`http://127.0.0.1:3000/auth/callback`, then restart only services whose private
configuration changed and the frontend to load its updated environment.

Use `http://127.0.0.1:3000` in the browser, not a mixture of localhost and
127.0.0.1. Next.js development requests also need the narrow `127.0.0.1` hostname
entry in `allowedDevOrigins`; this HMR setting does not configure OAuth origins
or relax authentication checks. Verify actual login with an existing designated
test account, session persistence, provider logout and both `/` and `/cart`
return destinations. Never reset an owner's password to complete verification.

The origin validator rejects credentials, paths, queries and fragments.
Production requires same-site HTTPS storefront/issuer, HTTPS provider/bridge
connections and `sslmode=verify-full` for the BFF database; insecure Node TLS
verification is rejected. Use a verified database CA where necessary. A trusted
TLS edge must route fixed origins, preserve the real scheme, block public
`/internal/*`, keep Hydra/Kratos admin and dev-token endpoints private, enforce
request/body and per-IP authentication/CAPTCHA rate limits, and exclude private
responses from caches. The loopback Compose proxy is not a complete production
TLS/rate-limiting deployment. Local self-signed TLS remains #88.

Roll out intentionally, with backups and a maintenance window:

1. Populate private root/frontend configuration and provision the dedicated auth
   store. New PostgreSQL volumes run `04-create-storefront-auth.sh` automatically.
   **Existing volumes do not rerun init scripts.** Recreate the PostgreSQL
   container with the new environment/mounts while retaining its data volume,
   then explicitly run the script below only after confirming the new roles and
   `storefront_auth` database are absent. SQL failures stop the script; collisions
   require operator review, not role replacement or a volume reset.
2. Deploy Spring with Flyway V16 and the create-only registration endpoints.
   Deploy bridge/provider configuration and rebuild the Kratos image so it
   contains the customer schema, webhook templates and secret-rendering entrypoint.
3. Reconcile the distinct BFF Hydra client, deploy its public proxy, then deploy
   the configured frontend. Align exact allowed storefront origins in Spring/
   Hive, including guest-request policy. Historical identities are not backfilled,
   relinked or verified by deployment.

```bash
docker compose -f infrastructure/compose.yml exec -T postgres \
  bash /docker-entrypoint-initdb.d/04-create-storefront-auth.sh
```

The dedicated database migration is
`infrastructure/oauth/auth-store/001_initial.sql`. Its owner/migration role is
separate from the BFF role (session/transaction/flow DML only) and CAPTCHA role
(challenge DML only). Neither runtime role can create schema objects or access
the other's credential-bearing tables. Future migrations are explicit versioned
SQL deployments, not anonymous-request initialization. Protect database backups
and encryption keys separately; encrypted rows are not a reason to publish dumps.

Bootstrap inserts client secrets only when creating clients. Routine
reconciliation does not rotate an existing secret. Rotation is a coordinated
Hydra-admin/client-secret and frontend deployment, never an accidental restart.
For encryption rotation, deploy an overlapping key ring everywhere, switch its
`active` ID, and retain old keys until all live rows **and pending revocation
jobs/backups requiring recovery** no longer depend on them. Reads rewrite rows
under the active key. Do not remove an old key merely because 24 hours elapsed.

## Lifetimes and maintenance

| State | Implemented lifetime/bound |
| --- | --- |
| Preauthentication | 30 minutes absolute/idle |
| OAuth transaction | 10 minutes; atomically single-use |
| Authenticated BFF | 24 hours absolute, 1 hour idle, capped by actual Kratos expiry |
| Provider flows | Actual Kratos flow expiry, not a frontend extension |
| CAPTCHA | 5 minutes; at most 10 challenges per flow/minute |
| CAPTCHA work | Official PBKDF2/SHA-256 PoW-v2, 5,000 iterations, counter 500–999 |
| Provider calls | Bounded timeouts; OAuth uses 8 seconds |
| BFF maintenance | Up to 500 expiry marks, 50 provider revocations, 500 deletions per cleanup category/run |
| Registration maintenance | Up to 500 CAPTCHA rows, 50 abandoned reservations/run |

Schedule both jobs at least every minute, with non-overlapping job execution,
bounded retries and monitoring of failures/pending-revocation backlog:

```bash
cd frontend && bun run auth:maintenance
# From the repository root, using the configured Compose environment:
docker compose -f infrastructure/compose.yml exec -T oauth-service \
  python /app/src/maintenance.py
```

The BFF command uses the same private environment as the server. It revokes
expired sessions and retries durable provider/grant revocation, deletes expired
preauth rows and successfully retired sessions older than one hour, with FK
cleanup of transactions/flows. A failed refresh that cannot be safely committed
permanently requires reauthentication. Repeated revoked-cookie reads do not
resurrect completed revocation jobs. Reauthentication retires the old SID/grant
without revoking a reused live Kratos session.

Registration cleanup removes CAPTCHA receipts only one hour after expiry.
It releases only expired, unbound reservations older than a one-hour grace
after an authoritative private-proof check. Persisted/unverified identities
retain claims. Identity discovery is bounded to 100 pages of 250 records;
exhaustion, ambiguous results or outages retain claims and fail reconciliation.
Neither job deletes owner identities. A nonzero job exit or pending count needs
operator attention; never discard unresolved revocations to make a report green.

## Verification and remaining scope

The [local delivery report](implementation-report.md) records real isolated Kratos/Hydra/bridge/Spring/Hive,
genuine Mailpit verification/resend, native CAPTCHA rejection/retry/outage,
MFA, callback rejection, cross-process refresh and provider-revocation evidence.
New captures are **Model-reviewed — awaiting owner approval**.

#40's browser/onboarding contract is implemented; social login and profile
editing are not. #27's supported self-hosted signup prerequisite is implemented,
not a claim about every future CAPTCHA use. #51's create-only native UUID linkage
is implemented, not historical adoption/backfill or its entire lifecycle scope.
Production configuration/secret provisioning and explicit rollout remain
operator responsibilities; the owner's running stack was not upgraded here.
