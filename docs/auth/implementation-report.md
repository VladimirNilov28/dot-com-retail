# Authentication package delivery: #40, #66, #67

Local implementation and verification completed **2026-10-09** on `dev`.
#67 is the actual separate registration issue. No duplicate, push, remote
branch or issue closure was created. New visuals are **Model-reviewed —
awaiting owner approval**, not an extension of existing scoped approvals.

## Scope and contract

The [browser/security contract](browser-contract.md) records authorities,
configuration, migration/rollout, rotation, limits and maintenance.
Kratos owns identity/password/session/verification/factors; Hydra issues OAuth;
Spring enforces JWT/role/scope/ownership and canonical users; Next is a BFF.
Maintained Ory SDK, openid-client, jose, cookie-jar and PostgreSQL integrations
keep credentials encrypted server-side behind opaque HttpOnly cookies.

The separate confidential client requests only `openid offline_access user:read`.
Code flow uses PKCE/state/nonce and form_post; callback/flow ownership is durable
and single-use. Native POSTs require exact Origin and BFF/provider CSRF.
The existing shared bridge dispatches the actual BFF client to fixed routes
while retaining legacy native/dev clients. The public Nginx proxy supplies only
Hydra's missing no-JS submit button, not custom OAuth protocol handling.

Registration uses real self-hosted ALTCHA, email/password/username/date-of-birth,
durable canonical reservations, genuine email verification/resend and
create-only USER linkage to the actual UUID. No email adoption, role-changing
upsert, manual verification or public/native bypass. REST signup is 410.
Registration does not automatically provide a BFF session or OAuth token.

Login handles actual credential/provider errors, enrolled TOTP/recovery factors,
expiration/restart and service failure/Retry. Account/header reflect actual
caller identity with status/logout only. No cart, dashboard, checkout, wishlist,
payment, review, social/profile or factor-management UI was added.

## Executed verification

| Check | Result |
| --- | --- |
| Frontend lint | Pass; one unchanged homepage `children` warning |
| Typecheck / generated GraphQL | Pass |
| Production webpack builds | Pass; isolated auth, controlled real Hive and read-only live Hive |
| Complete relevant frontend suite | 189 pass: 47 auth, 84 typed GraphQL, 9 fixtures, 49 presentation/model |
| Bridge unit suite | 116 pass |
| Existing real TOTP / scope suites | 3 / 1 pass |
| Affected backend plus Spotless | 66 pass; genuine PostgreSQL concurrency/Flyway/native linkage/deletion |
| Both bounded maintenance commands | Pass; final pending revocations 0 |
| Final provider logs/browser assets | No configured secrets/JWTs; actual log regression covers tested passwords/SIDs/tokens |

The final combined frontend command was
`bun --conditions=react-server test src/lib scripts/catalog-fixture-data.test.mjs scripts/product-fixture-data.test.mjs`.
Individual real/provider/UI runbooks and their required isolated environment
are in the frontend and OAuth service READMEs. Generated private environments
and task identities were removed after verification, not retained for reuse.

Real isolated Ory/bridge/Spring/client-facing Hive verified genuine signup,
courier verification/resend, unverified OAuth denial, duplicate/weak-password/
invalid-code errors and exact ordinary USER identity. CAPTCHA missing/malformed/
expired/foreign/reused proof, authenticated delivery retry, database outage and
actual native/legacy bypass rejection passed.

OAuth checks passed successful/denied callbacks, absent/invalid/expired/foreign/
replayed state, real nonce/PKCE mismatch, code replay and rejected return routes.
Refresh was serialized across three independent processes and concurrent
requests. Reauthentication rotates SID/retires old grant while preserving a
reused provider session. Actual Kratos revocation, idle/absolute expiry,
ambiguous refresh and logout/provider outage permanently reject old BFF access;
durable retry completes provider revocation. Spring's residual issued JWT
validity remains bounded by token expiry, not global instant revocation.

Authenticated caller-owned `me` through Hive, two-browser identity/CSRF isolation,
privileged/cross-user `user(id: ID!)` denial and machine-endpoint rejection
passed. DGS's actual `PERMISSION_DENIED` was checked, not a query-validation
failure. No cart mutations were used to test authentication.

Full catalog/search, product/actual variant, populated home and 11-scenario
storefront/menu/focus/history regressions passed without dropping scenarios.
Read-only live home/root-only composition also passed. Browser checks included
1440px/390px, keyboard/focus, actual 200% zoom (390 -> 195 CSS pixels), no overflow
and meaningful no-JS login/factor/verification/OAuth/account/logout. Unexpected
hydration/runtime errors: zero. Signup's no-JS security-check limitation is
explicit, not bypassed. Final emulated browser PoW checks took 3397ms/2181ms at
1440/390 with actual 200% zoom; these are not physical-phone benchmarks.

The confidentiality audit discovered that Kratos logs arbitrary custom webhook
headers despite sensitive logging being disabled. This was fixed with standard
Authorization redaction, the affected **fixture-only** secret rotated, providers/
BFF rebuilt and complete real/browser/legacy suites rerun. A persistent log
regression now checks stdout and stderr without displaying secrets. Provider
fault suites must run sequentially; overlapping deliberate pauses were rejected
as invalid verification and rerun independently.

## Evidence, cleanup and local revisions

[The baseline manifest](../frontend/baseline/README.md) records 16 inspected auth
images and one refreshed settled anonymous account popover. Rejected animation,
wrong-width, diagnostic and clipped zoom artifacts are not retained. Visual
review found/refined MFA hierarchy and native date/grid overflow before
retention; final transport hardening was nonvisual and browser-reverified.

Only recorded task fixture containers/volumes/images, owned servers, private
runtime/configuration/build copies and temporary captures were removed.
Normal owner frontend3000 and Hive4002 remain responsive, with **22 categories /
42 products** unchanged and every task port released. The current owner dev
listener is PID3883508; the earlier dev worker PID is not falsely claimed
unchanged. No owner environment file, identity/data, volume or manual
restart/reseed was performed.

Local implementation commits: `caf07ed` provider/canonical integration,
`cfc927b` durable BFF/lifecycle and `a2c8c18` forms/account/browser regressions.
The following documentation/evidence commit and exact full hashes are recorded
in the open-issue delivery comments. These revisions are **not pushed**.

#40 social login/profile editing, #51 historical adoption/backfill/full lifecycle
and production secret/configuration/TLS-edge rollout are not claimed complete.
#27's self-hosted signup prerequisite is implemented only within this package.
Next recommended related package: **#68 password recovery/reset, #69 customer
2FA enrollment/settings, #70 third-party OAuth/canonical onboarding**; not started.
