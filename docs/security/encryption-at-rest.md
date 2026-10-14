# Encryption at rest

Personal, order and contact data is encrypted by the Spring backend before it reaches
PostgreSQL. This uses AES-256-GCM with a random 96-bit IV per value. Keys are supplied
through the environment and never stored in Git or the database.

## What is encrypted

| Data | Storage | Mechanism |
|---|---|---|
| Saved addresses: name, street, city, country, postal code, phone | `user_address` columns | JPA `EncryptedStringConverter` |
| Order checkout snapshot: contact email/phone, shipping/billing addresses, lines, totals | `orders.checkout_snapshot` → `{"sealed":"enc:…","contactEmailIndex":"…"}` | `SealedSnapshotConverter` |
| Order email outbox: recipient, body | `order_notifications` | `FieldEncryption.seal/open` |
| Contact form: name, email, message; outbox recipient/reply-to/body | `contact_messages`, `contact_notifications` | `FieldEncryption.seal/open` |
| BFF sessions, OAuth tokens and CAPTCHA state | BFF database | already encrypted by the BFF ([`../auth/browser-contract.md`](../auth/browser-contract.md)) |
| Kratos identities and credentials | Kratos database | Kratos hashing (passwords), `secrets.cipher` (TOTP secrets) |

The following are not encrypted:

- Order line facts (`order_items`) contain only product references, quantities and
  prices. They hold no personal data.
- Monetary totals and statuses are not personal data. They stay queryable for staff
  filters and integrity checks.
- Card data is never stored. Stripe hosted fields send it directly to Stripe, and the
  payment service stores only intent identifiers.

Staff can still search orders by customer email. `contactEmailIndex` is an HMAC-SHA256
of the lower-cased, trimmed email. Its key is derived from the first configured data
key, so the index reveals equality only.

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
- **Rotation:**
  1. Prepend a new key, e.g. `APP_DATA_KEYS=v2:<new>,v1:<old>`.
  2. Restart the backend. New writes use `v2`.
  3. The startup backfill re-encrypts rows still under `v1`. Batches commit
     individually, so the backfill is resumable.
  4. Remove `v1` after the backfill has finished.

  The email index key does not change while the first key stays the same. Keep the
  old first key in the ring until the backfill has finished.

## Migration path

V25 widens the affected columns. On startup, `EncryptionBackfill` encrypts legacy
plaintext in batches and is idempotent; values already under the active key are
skipped. Readers accept both plaintext and ciphertext, so a partly migrated database
keeps working.

Checkout snapshots remain immutable. The V25 trigger permits only two updates:

- legacy plaintext to a sealed envelope;
- re-sealing under a different key id.

Any other change is rejected, as before.

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
