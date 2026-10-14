# Encryption at rest

Personal, order and contact data is encrypted by the Spring backend before it reaches
PostgreSQL. This uses AES-256-GCM with a random 96-bit IV per value. Keys are supplied
through the environment and never stored in Git or the database.

## What is encrypted

| Data | Storage | Mechanism |
|---|---|---|
| Account email | `users.email`, `user_registration_reservations.email` | JPA `EncryptedStringConverter` + keyed blind index `email_index` (V27) |
| Account date of birth | `users.date_of_birth`, `user_registration_reservations.date_of_birth` | JPA `EncryptedLocalDateConverter` (ISO date, sealed; V27) |
| Saved addresses: name, street, city, country, postal code, phone | `user_address` columns | JPA `EncryptedStringConverter` |
| Order checkout snapshot: contact email/phone, shipping/billing addresses, lines, totals | `orders.checkout_snapshot` → `{"sealed":"enc:…","contactEmailIndex":"…"}` | `SealedSnapshotConverter` |
| Order email outbox: recipient, body | `order_notifications` | `FieldEncryption.seal/open` |
| Contact form: name, email, message; outbox recipient/reply-to/body | `contact_messages`, `contact_notifications` | `FieldEncryption.seal/open` |
| BFF sessions, OAuth tokens and CAPTCHA state | BFF database (`storefront_auth`) | sealed by the BFF ([`../auth/browser-contract.md`](../auth/browser-contract.md)) |
| Kratos passwords | Kratos database | one-way hashing (bcrypt/argon2), not encryption |
| Kratos traits (email, username, DOB), TOTP secrets, session tokens, recovery/verification data; Hydra data | Kratos/Hydra databases | **not encrypted by the provider** (see [Provider-owned data](#provider-owned-data-kratos-hydra)) — storage-layer encryption only |

The following are not encrypted:

- `users.username` is a public handle, shown on reviews, and must stay unique and
  searchable by substring. It is not treated as secret.
The following are not encrypted:

- Order line facts (`order_items`) contain only product references, quantities and
  prices. They hold no personal data.
- Monetary totals and statuses are not personal data. They stay queryable for staff
  filters and integrity checks.
- Card data is never stored. Stripe hosted fields send it directly to Stripe.
- Payment tables hold no personal data and stay plaintext so they can be reconciled
  against Stripe and the payment service:
  - retail: `orders` (status, totals, `payment_intent_id`), `order_refunds`,
    `payment_outbox`, `payment_result_receipts`, `user_payment_methods` (provider
    payment-method id, brand, last four digits, expiry);
  - payment service: `payments`, `refunds`, `provider_ledger`, `stripe_operations`,
    `payment_outbox` (ids, amounts, currency, statuses, provider ids, failure codes).

  Their outbox events (`PaymentRequestedEvent`, `RefundRequestedEvent`) carry ids and
  amounts only. Provider identifiers such as PaymentIntent ids are deliberately left
  readable because reconciliation and webhook matching need them. They also get
  storage-layer encryption in the review stack.

### Email blind indexes

Ciphertext is randomised, so equality lookups use keyed HMAC-SHA256 indexes. Both
are computed over the lower-cased, trimmed email and reveal equality only.

| Index | Column | Key derivation (from the first ring key) | Format |
|---|---|---|---|
| Account email | `users.email_index`, `user_registration_reservations.email_index` | HMAC label `bytecore-user-email-index-v1` | `v1:<64 hex>` |
| Guest contact email | `orders.checkout_snapshot.contactEmailIndex` | HMAC label `bytecore-blind-index-v1` | `<64 hex>` |

- `email_index` carries a `UNIQUE` constraint, which replaces the old plaintext
  `users_email_key`. Uniqueness is therefore **case-insensitive**: `A@x.ee` and
  `a@x.ee` conflict, both in the database and in `UserService`.
- The entity computes the index in `@PrePersist`/`@PreUpdate`. Lookups go through
  the index: `existsByEmail`, registration reservation lookup, Kratos provisioning
  and email-change conflicts, and staff order search. Rows that have not been
  backfilled yet (`email_index IS NULL`) still match through a `lower(trim(email))`
  fallback.
- The version prefix allows a future re-keying. Bump `USER_EMAIL_INDEX_VERSION` and
  its derivation label, then recompute (the backfill recomputes any row whose
  ciphertext is not under the active key).
- **Staff search limitation:** user search (`searchUsers`) can no longer match a
  *substring* of an email. It matches a username substring, an exact email (through
  the index, case-insensitive) or an exact numeric user id. Order search matches an
  exact customer email through the account index or the guest `contactEmailIndex`.

## Keys

```dotenv
# id:base64(32 random bytes); comma-separated ring, first entry encrypts by default
APP_DATA_KEYS=v1:<base64 or base64url 32-byte key>
APP_DATA_ACTIVE_KEY=          # optional, defaults to the first id
APP_DATA_BACKFILL=true        # encrypt legacy plaintext rows on startup
```

- **Generate a key:** `openssl rand -base64 32`, or run `scripts/review-up.sh`, which
  fills `@b64url32@` markers in `.env.review`.
- **Where it is set:**
  - Dev: `.env`, which Git ignores.
  - Reviewer stack: `.env.review`, also ignored.
  - Docker Compose fails to start the backend if `APP_DATA_KEYS` is missing.
- **Backup:** store the key ring separately from database backups, for example in a
  password manager or secret store. If the key is lost, the encrypted data is lost
  with it.
- **Rotation:** both blind-index keys are derived from the **first** key in the ring.
  Changing the first entry would silently invalidate every index, so rotate with
  `APP_DATA_ACTIVE_KEY`:
  1. Append a new key, e.g. `APP_DATA_KEYS=v1:<old>,v2:<new>`, and set
     `APP_DATA_ACTIVE_KEY=v2`.
  2. Restart the backend. New writes use `v2`.
  3. The startup backfill re-encrypts rows still under `v1`. Batches commit
     individually, so the backfill is resumable.
  4. Keep `v1` in the ring as the index root. It no longer encrypts data.

## Migration path

V25 widens the affected columns. On startup, `EncryptionBackfill` encrypts legacy
plaintext in batches and is idempotent; values already under the active key are
skipped. Readers accept both plaintext and ciphertext, so a partly migrated database
keeps working.

Checkout snapshots remain immutable. The V25 trigger permits only two updates:

- legacy plaintext to a sealed envelope;
- re-sealing under a different key id.

Any other change is rejected, as before.

### V27: account email and date of birth

`V27__user_pii_encryption.sql` changes `users` and `user_registration_reservations`
in place. IDs, foreign keys and the GraphQL contract (`User.email`,
`User.dateOfBirth`) are unchanged:

- `email` widens to `varchar(1024)`;
- `date_of_birth` becomes `varchar(1024)`, with existing dates rewritten as ISO
  strings;
- a nullable `email_index varchar(80)` is added with a `UNIQUE` constraint;
- the plaintext unique constraint on `email` is dropped.

`EncryptionBackfill.backfillAccounts` (under `APP_DATA_BACKFILL=true`) then works
through each table in batches, ordered by id:

1. It selects rows with no index, or with an email or DOB not under the active key.
2. It encrypts both values, computes the index, and **decrypts again to verify the
   round trip** before writing.
3. It updates the row in its own transaction, guarded by compare-and-set on the
   old values, so concurrent edits are never overwritten.

The backfill is idempotent and restart-safe; a second run touches no rows. A row
that cannot be indexed (for example a legacy case-insensitive duplicate email,
which now violates the unique index) is logged by table and id only. It stays
readable and is retried on the next start; resolve the duplicate and restart.

**Rollback and recovery:** the plaintext value is replaced only after its
ciphertext has been written and verified, so there is no window in which a row is
unreadable. There is no separate plaintext copy afterwards. Take a `pg_dump`
backup before the first V27 start. Recovery needs either that backup or the key
ring: any value can be decrypted with `FieldEncryption.open` and the ring. Flyway
migrations are forward-only. To roll back the code, restore the pre-V27 backup.

`UserPiiEncryptionIntegrationTest` (Testcontainers) checks:

- the email and DOB columns hold `enc:` ciphertext and the index has the `v1:`
  prefix, and lookup by an upper-cased email works;
- case-insensitive duplicates are rejected by both `UserService` and the database;
- plaintext legacy rows are backfilled and a re-run changes nothing;
- a legacy duplicate is left readable;
- reservations are encrypted and indexed;
- staff search finds users by exact email, id and username substring, but not by
  email substring.

### Evidence for V25

Evidence: `EncryptionAtRestIntegrationTest` (Testcontainers) checks four things:

- address columns contain ciphertext and read back as plaintext;
- the legacy backfill works;
- sealing a snapshot keeps the email index searchable;
- the trigger rejects an unsealed overwrite.

`ContactMessageIntegrationTest` and `OrderLifecycleIntegrationTest` assert ciphertext
at rest. On the dev database, the first start reported:
`sealed 34 addresses, 55 order snapshots, 104 notifications`.

## Limitations

- Ciphertext can only be filtered by the email blind index. Free-text search over
  encrypted fields is intentionally unsupported.
- PostgreSQL volume- or disk-level encryption is a deployment concern and is not
  configured for local Docker.
- TLS protects data in transit on the reviewer stack. See
  [`../review-setup.md`](../review-setup.md).
