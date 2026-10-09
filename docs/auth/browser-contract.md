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
