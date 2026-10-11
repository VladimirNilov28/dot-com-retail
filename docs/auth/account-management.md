# Customer account management

Customer account settings live under `/account`. They are built on native Kratos
v26.2.0 capabilities, reached through the Next.js BFF. The Python bridge handles the
few operations Kratos cannot perform from a browser session. No credential, code or
provider subject is stored outside Kratos, and Spring remains the canonical customer
record.

| Page | Purpose |
|---|---|
| `/account` | Overview, security checkup and sign-out |
| `/account/profile` | Username and date of birth (read-only), email address and verified email change |
| `/account/security` | Create or change a password, authenticator app (TOTP) and recovery codes |
| `/account/connections` | Google connect and disconnect |
| `/account/sessions` | Active sessions, revoke one session, sign out all other sessions |

Staff identities see only a short note. Staff MFA and bootstrap are tracked in #80/#40.

## Request paths

- **`POST /auth/security`**
  - Submits only allowlisted actions that appear in the current, browser-bound
    Kratos settings flow: password, TOTP, recovery codes and Google link/unlink.
  - The Kratos CSRF token comes from the flow on the server. A posted CSRF value is
    never trusted.
- **`POST /auth/account`** handles the BFF-owned actions:
  - `step_up_send` and `step_up_verify`
  - `email_request`, `email_verify` and `email_cancel`
  - `session_revoke` and `sessions_revoke_others`
- **Every form post** requires the BFF CSRF token and strict field sets.
- **Bridge routes** (`/internal/account/{status,notify,email}`):
  - They require the bridge secret and resolve the caller from the Kratos session
    cookie. They act only for `USER` identities.
  - `status` returns booleans, the unused recovery-code count and profile fields.
    Credential configuration never leaves the bridge.

## Sensitive-operation verification (step-up)

Changing a password, the email address, TOTP, recovery codes, Google links or
sessions requires both:

1. **A fresh session.** Kratos authentication within the last 15 minutes
   (`privileged_session_max_age`). An older session is sent to
   `/auth/security-reauth?section=…`. That route performs a provider refresh login
   at the session's current assurance level, so an enrolled account is asked for
   its authenticator code.
2. **Verification, chosen by factor:**
   - **Authenticator configured:** the Kratos session must contain a TOTP
     authentication method completed within the last 10 minutes. Enrolling TOTP
     counts, because it proves possession of the authenticator. An email code is
     never offered and never accepted in place of a configured authenticator.
   - **No authenticator:** a 6-digit code is emailed to the account address. Its
     rules:
     - The salted digest is kept only in the encrypted, server-side browser state,
       bound to the provider session.
     - It expires after 10 minutes.
     - It is single-use and allows at most 5 guesses.
     - A verified code unlocks changes for 10 minutes, in that session only.

All checks are performed server-side on every mutation; hidden UI is not a security
boundary.

## Passwords

- **Google-only accounts** see "Create password" and are never asked for a
  nonexistent current password.
- **Accounts with a password** see "Change password".
- **Checks before Kratos:** the BFF validates the confirmation field and the shared
  8–72 byte policy. Kratos then applies its own policy and breach checks.
- **After a password change:** Kratos's `revoke_active_sessions` hook signs out other
  sessions, and a security alert is emailed.

## Email change

1. After step-up, the user enters a new address.
2. The bridge rejects addresses already used by another identity or registration
   with a generic conflict response, without revealing which account holds it.
3. A code is sent to the **new** address. The change is applied only after that code
   is confirmed.
4. The bridge updates Spring first, then the Kratos identity, and marks the new
   address verified. If the Kratos update fails, Spring is reverted.
5. The previous address receives a notification that shows the new address masked.
6. A linked Google identity is not modified, because Google keeps its own address.

## Authenticator and recovery codes

- **Recovery codes are not a login method.** They are Kratos `lookup_secret`
  credentials, offered only on the second-factor step after a correct password
  ("Lost your authenticator? Use a recovery code"). They are never accepted for
  primary login or password reset.
- **Lifecycle:**
  - Codes can be created only while TOTP is enrolled.
  - New codes are shown once and must be explicitly activated.
  - Regenerating replaces the whole set, so old codes stop working. Kratos accepts
    regeneration without revealing the stored codes, and the BFF never requests a
    reveal.
  - Removing TOTP also turns off the remaining codes.
- **MFA recovery:** password + recovery code → restricted recovery mode:
  - other sessions are signed out (fail-closed);
  - the user is emailed with the remaining code count;
  - only authenticator and recovery-code changes are allowed, after an email
    step-up, so the user can remove the lost authenticator and enrol a new one;
  - password, email and Google changes are hidden in the UI and rejected by the
    server until the next normal sign-in.
- **No bypass.** A user who has lost the password, the authenticator and all
  recovery codes cannot recover the account. Email password recovery does not
  remove TOTP, and no support bypass exists.

## Connected accounts and last-method protection

Google linking and unlinking use Kratos OIDC settings (`link`/`unlink`). See
[`external-provider.md`](external-provider.md) for the full flow. The BFF refuses to
disconnect Google when the account has no password ("Create a password first").
Kratos also enforces its own minimum number of first factors. A matching email never
merges accounts.

## Sessions

The page lists the user's other active Kratos sessions with sign-in times. A user
can:

- revoke one session;
- after confirming, sign out all other sessions.

The current session cannot be revoked from this list; the user signs out instead.
Revoked browsers fail their next provider check and are cleared.

Device and location details are not shown. Kratos records the BFF server as the
client, so those fields would be misleading.

## Rate limits

Counters use fixed windows in `storefront_auth.attempt_limits`, created by migration
`003_attempt_limits.sql`. Keys are HMAC-hashed, so no raw email or identifier is
stored.

| Scope | Limit |
|---|---|
| Second-factor login attempts (TOTP or recovery code), per browser | 10 / 15 min |
| Password recovery email, per address | 3 / 15 min |
| Password recovery email, per browser | 6 / 15 min |
| Step-up email codes sent, per identity | 3 / 15 min |
| Step-up code checks, per identity | 10 / hour |
| Email-change codes sent, per identity | 3 / hour |
| Email-change code checks, per identity | 10 / hour |

Kratos's own login and recovery protections still apply.

## Notifications

The bridge emails the account address, in plain text without codes or links, for
these events:

- a password set or changed;
- TOTP enabled or removed;
- recovery codes regenerated or turned off, and a recovery code used;
- Google connected or disconnected;
- other sessions signed out;
- the email address changed (sent to the previous address).

A notification failure never reverses an applied change.

## Known limitations

- Kratos v26.2.0 stores lookup secrets in plaintext in its database (#89). The
  application never displays existing codes and never asks for a reveal.
- Username and date-of-birth editing is deferred (#71).
- The second-factor attempt limit is per browser. A per-identity limit needs Kratos
  hook support and is tracked in #90.
- Session device and location details are not available, as explained above.

## Kratos state after unlinking Google

Unlinking the last Google connection leaves an `oidc` credential with no identifiers and one blank placeholder provider entry (empty `provider` and `subject`). The bridge status projection treats that placeholder as "Google not connected". Partially blank or otherwise malformed provider entries are still rejected, and the bridge logs the static reason (never provider data) for each rejected private request.
