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

## Provider-owned data (Kratos, Hydra)

Kratos and Hydra (both `v26.2.0`, see `infrastructure/compose.yml`) keep their own
tables in the same PostgreSQL server. We do not patch or fork them. What these
versions actually protect:

| Data | Kratos/Hydra behaviour |
|---|---|
| Passwords | Hashed (bcrypt/argon2); never stored reversibly |
| OIDC (social sign-in) tokens | Encrypted with Kratos `secrets.cipher`. This is the **only** use of `secrets.cipher` |
| TOTP secrets | **Plaintext**: `identity_credentials.config` stores `{"totp_url":"otpauth://…"}` |
| Identity traits (email, username, date of birth) | **Plaintext** JSON in `identities.traits` |
| Session tokens | Stored as issued in `sessions.token` |
| Hydra JWK private keys | Encrypted with Hydra `secrets.system` |
| Hydra OAuth2 tokens | Hydra stores token signatures, not the bearer tokens |

These findings come from the Kratos source for this version: `encryptOAuth2Tokens`
in the OIDC strategy is the only caller of the cipher, and the TOTP credential
config is written as plain JSON. A disposable Kratos confirmed the plaintext TOTP
URL and traits in the database. Kratos offers no application-level encryption for
those columns, so they are protected at the **storage layer** instead.

## Storage-layer encryption (review stack)

The reviewer stack (`infrastructure/compose.review.yml`, started by
`scripts/review-up.sh`) runs PostgreSQL from
`infrastructure/review/postgres-encrypted/`. This is `postgres:17` plus
[gocryptfs](https://nuetzlich.net/gocryptfs/), which encrypts file contents
(AES-256-GCM) and file names.

- The named volume `postgres_crypt_volume` holds **ciphertext only**. The plaintext
  `postgres_volume` of the dev stack is not mounted.
- `encrypted-storage-entrypoint.sh` reads the key file, mounts the decrypted view at
  `PGDATA` inside the container only, then execs the normal (TLS) Postgres
  entrypoint. Postgres runs as PID 1 as usual.
- Everything under `PGDATA` is encrypted. That covers all databases in the server
  (`retail`, Kratos, Hydra, BFF `storefront_auth`, payment service), WAL, temporary
  files and the server configuration.
- Key: `infrastructure/review/secrets/pgdata.key` (Git-ignored; override with
  `REVIEW_PGDATA_KEY_FILE`). `review-up.sh` generates it once (32 random bytes,
  mode 600) and bind-mounts it read-only at `/run/secrets/pgdata_key`. It is
  separate from `APP_DATA_KEYS` and lives outside the encrypted volume.
  `review-down.sh --volumes` deletes the data but keeps the key.
- **Fail closed:** Postgres does not start, and the container exits 1 with a
  clear `encrypted-storage: FATAL: …` log line, if:
  - the key is missing, empty or wrong;
  - `/dev/fuse` or `CAP_SYS_ADMIN` is missing;
  - the cipher volume holds non-gocryptfs data;
  - unencrypted data is already present at `PGDATA`.

  If the volume exists but the key file is gone, `review-up.sh` refuses to
  generate a new key.
- **Host prerequisites:** a Linux Docker host (or Docker Desktop VM) that provides
  `/dev/fuse`. The container gets `--device /dev/fuse`, `cap_add: SYS_ADMIN` and
  `security_opt: apparmor:unconfined`; the last is needed only on AppArmor hosts
  (Ubuntu, Debian), where the default profile blocks FUSE mounts. Rootless Docker
  and hosts without FUSE are not supported.
- **Fallback when FUSE is unavailable:** encrypt the Docker data root at the host
  instead (LUKS/dm-crypt, FileVault, BitLocker), and run Postgres with a plaintext
  volume by removing the `build`, `image`, `devices`, `cap_add`, `security_opt`,
  `environment` keys of the `postgres` service in `compose.review.yml`. Also set
  `entrypoint: ["/review/postgres-tls-entrypoint.sh"]`, and in the `!override`
  volume list replace the cipher and key mounts with
  `postgres_volume:/var/lib/postgresql/data`. Document that choice in the deployment.

### Threat boundary

| Protected | Not protected |
|---|---|
| Copies of the Docker volume or disk, file-level backups and snapshots of `postgres_crypt_volume`, a stolen host disk (with the key file kept elsewhere) | Query results and anything a database role can read while the stack runs |
| WAL, temp files and file names | Logical dumps (`pg_dump`/`pg_dumpall`): they are plaintext and must be encrypted separately |
| | Host root or a container with access to the running mount, memory, or the key file |

Key separation: the storage key (`pgdata.key`) and the application data keys
(`APP_DATA_KEYS`) are independent. Spring field encryption still protects its
columns from database roles and dumps; storage encryption protects provider tables
that Spring cannot encrypt. Store `pgdata.key` apart from volume backups: a backup
of the key with the volume defeats the purpose.

### Verification evidence

Verified on disposable resources (`dcr-crypto-tmp-*`, removed afterwards). Results
are counts and booleans only:

1. **Standalone image with a disposable Kratos `v26.2.0`:**
   - Registration, login and TOTP enrollment via the native API all succeeded.
   - Decrypted database: identities with the marker trait = 1; TOTP credentials
     with a plaintext `otpauth` URL = 1.
   - Plaintext view inside the container: files containing the marker = 8,
     `otpauth` = 3, the session token = 3.
   - Raw cipher volume (1742 files): files containing the marker = 0, `otpauth`
     = 0, the session token = 0; `PG_VERSION` file names = 0.
   - After `docker restart` and after recreating the container (crash
     simulation), login still worked and the old session was still valid.
   - Wrong key, missing key, no `/dev/fuse` and no `CAP_SYS_ADMIN` each exited 1
     with a clear log line.
2. **The review `postgres` service from the merged compose files (TLS entrypoint
   and init scripts):**
   - The service became healthy; non-template databases = 6; `ssl` = on.
   - Decrypted marker rows = 1; files in the plaintext view with the marker = 2.
   - Raw volume (2520 files): files with the marker = 0; `PG_VERSION` and
     `pg_wal` names = 0.
   - Marker rows after restart = 1.
   - A wrong or missing key made `up --wait` fail, and the container exited 1.
     Recovering with the right key gave marker rows = 1.
3. **`review-up.sh` key handling:**
   - It generated a 44-character key with mode 600 in a 700 directory.
   - An existing key was left unchanged.
   - A directory left behind by Docker was replaced with a key file.
   - A missing key with an existing encrypted volume made it exit 1 without
     generating a key.

Not verified: the complete review stack end to end with this override, AppArmor
hosts, and Docker Desktop on macOS or Windows.

### Owner dev stack: cutover procedure (pending)

The dev stack (`make dev`, `compose.yml`) still uses the plaintext `postgres_volume`
and is **not converted**. To move an existing installation to encrypted storage
without data loss:

1. **Back up.**
   ```bash
   # stop every database client first (and a Spring app / storefront running on the host)
   docker compose -f infrastructure/compose.yml stop backend payment-service oauth-service kratos hydra hydra-public
   docker exec postgres pg_dumpall -U "$DB_USERNAME" --clean --if-exists > backup.sql   # plaintext: keep it safe
   ```
2. **Verify the backup on a disposable server.**
   ```bash
   docker run -d --name pg-restore-check -e POSTGRES_PASSWORD=x postgres:17
   docker exec -i pg-restore-check psql -U postgres -v ON_ERROR_STOP=0 < backup.sql
   ```
   Compare row counts with the live server, e.g. `users`, `orders`, Kratos
   `identities`, then run `docker rm -f pg-restore-check`.
3. **Stop and create the encrypted volume.**
   ```bash
   docker compose -f infrastructure/compose.yml stop postgres
   umask 077 && mkdir -p infrastructure/review/secrets && openssl rand -base64 32 > infrastructure/review/secrets/pgdata.key
   ```
   Back the key up separately, e.g. in a password manager.
4. **Switch the compose config.** Copy the `postgres` block of
   `compose.review.yml` (build, image, devices, cap_add, security_opt,
   `PGDATA_KEY_FILE`, `volumes: !override` with `postgres_crypt_volume` and the key)
   into a local override, without the TLS entrypoint and certificates. Use
   `entrypoint: ["encrypted-storage-entrypoint.sh", "docker-entrypoint.sh"]` and
   `command: ["postgres"]`, and declare `postgres_crypt_volume`.
5. **Start and restore.**
   ```bash
   docker compose -f infrastructure/compose.yml -f <override> up -d postgres
   docker exec -i postgres psql -U "$DB_USERNAME" -d postgres -v ON_ERROR_STOP=0 < backup.sql
   ```
   The init scripts create the users and databases first; the dump then replaces
   their contents.
6. **Verify.**
   - The counts from step 2 match.
   - `docker compose up -d` brings up a healthy stack; login and TOTP work.
   - `grep -rl` of a known value in the raw `postgres_crypt_volume` returns
     nothing.
7. **Clean up after confirmation.** Keep the old `postgres_volume` until the
   encrypted stack has been confirmed over normal use, then remove it with
   `docker volume rm`. Shred `backup.sql`, or keep it encrypted (e.g. `gpg -c`).

Existing **review** installations need the same steps to keep their data. Without
them, `review-up.sh` leaves the old `bytecore-review_postgres_volume` untouched,
warns about it, and starts a fresh encrypted database.

## Limitations

- Ciphertext can only be filtered by the email blind index. Free-text search over
  encrypted fields is intentionally unsupported.
- Storage-layer encryption is on by default in the review stack only. The dev stack
  awaits the owner cutover above.
- TLS protects data in transit on the reviewer stack. See
  [`../review-setup.md`](../review-setup.md).
