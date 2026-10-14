# Production-mode Docker stack

`make prod` builds and starts the complete ByteCore stack in Docker with production
builds, migrations, health checks, a TLS edge and encrypted database storage. It is
separate from `make dev` (owner development infrastructure) and `make review`
(reviewer stack): it uses its own Compose project (`bytecore-prod`), network
(`172.28.92.0/24`), volumes, private configuration (`.env.prod`) and secrets directory
(`infrastructure/prod/secrets/`).

**By default it needs no keys, domain or real data.** The first `make prod` creates
`.env.prod` with local defaults and starts the stack at `https://bytecore.localhost`.
It uses a generated local CA, Mailpit captures the emails, and the database starts with
no demo data. A real host is optional (see [Running on a real host](#running-on-a-real-host)).
Choosing the host, domain, DNS and provider dashboards is a separate step.

## Commands

| Make target | Equivalent script | Effect |
| --- | --- | --- |
| `make prod` | `bash scripts/prod.sh up` | validate config, build images, run migrations, wait for health, smoke-check the edge |
| `make prod-down` | `bash scripts/prod.sh down` | stop and remove containers; **keeps all volumes, keys and configuration** |
| `make prod-status` | `bash scripts/prod.sh status` | container state and health, storefront URL |
| `make prod-logs [SERVICE=caddy]` | `bash scripts/prod.sh logs [-f] [service]` | logs (the Make target follows the last 200 lines) |
| `make seed-prod` | `bash scripts/prod.sh seed` | load the demo showcase dataset into a fresh stack (refuses if data exists) |
| — | `bash scripts/prod.sh check` | validate configuration only; prints missing variable *names* |

`make` is only a convenience wrapper. No normal command deletes volumes:
`scripts/prod.sh down` rejects extra arguments such as `--volumes`.

Environment overrides (mainly for rehearsals): `PROD_PROJECT` (Compose project name,
default `bytecore-prod`), `PROD_ENV_FILE` (default `.env.prod`), `PROD_WAIT_TIMEOUT`
(seconds, default 900).

## Host requirements

- Docker Engine with Compose v2.24.4 or newer. Nothing else: Java, Node, Bun, Go and
  Python all run inside the images.
- `/dev/fuse` on the Docker host (`sudo modprobe fuse` on Linux). The `postgres`
  container runs with `--device /dev/fuse`, `CAP_SYS_ADMIN` and
  `apparmor=unconfined` so that gocryptfs can mount the encrypted data directory.
  `scripts/prod.sh` refuses to start on Linux without `/dev/fuse`, and there is no
  plaintext fallback.
- Free host ports 80, 443, 9433, 9444, 9446 and 18025 (bound to `127.0.0.1` by default;
  a real server uses `PROD_BIND=0.0.0.0`).

## First run

```bash
make prod            # creates .env.prod (mode 600) with local defaults, builds, migrates, starts
```

Then:

1. Trust `infrastructure/prod/secrets/tls/ca.crt` in the browser. The CA is name-constrained
   to `bytecore.localhost`. Without trusting it, the browser shows a certificate warning.
2. Open `https://bytecore.localhost`.
3. Read the emails in Mailpit at `http://127.0.0.1:18025`.
4. Sign in to `/admin/security` as `admin@bytecore.ee`, using `ADMIN_PASSWORD` from
   `.env.prod`, and enroll TOTP.
5. Add products and images through the admin UI.

**Reusing your dev sandbox credentials.** When `.env.prod` leaves `STRIPE_SECRET_KEY`,
`STRIPE_PUBLISHABLE_KEY`, `GOOGLE_CLIENT_ID` or `GOOGLE_CLIENT_SECRET` blank, `make prod`
takes them from the Git-ignored `.env.stripe`, and otherwise from the Git-ignored dev `.env`.

- They are exported to the containers at startup, not copied into tracked files.
- Live Stripe keys are still rejected.
- `PROD_DEV_ENV_FILE=` turns off the dev `.env` import.
- These are secrets even in sandbox mode, so they never go into a committed
  `*.example` file.
- For Google, add `https://bytecore.localhost/self-service/methods/oidc/callback/google` to
  the OAuth client's authorized redirect URIs. If Google rejects `.localhost`, see
  [Running on a real host](#running-on-a-real-host).

**Optional card payments (Stripe test mode).** Copy `.env.stripe.example` to `.env.stripe`
(Git-ignored) and paste your `sk_test_`/`pk_test_` keys, or rely on the dev `.env` import.
Then run `make prod` again:

- The webhook signing secret is fetched with the Stripe CLI image and is not written to disk.
- A `stripe-listener` container (Compose profile `stripe`) forwards the `payment_intent.*`
  events to the payment service, so no public URL is needed.
- Without keys, card payment is shown as unavailable and everything else works.

Generated secrets (database passwords, Hydra/Kratos secrets, client secrets, internal
tokens, `AUTH_SEAL_KEYS`, `APP_DATA_KEYS`, `ADMIN_PASSWORD`) are created once when
`.env.prod` is first written. An existing `.env.prod` is never overwritten, and keys
are never regenerated or rotated by startup. The storage key
`infrastructure/prod/secrets/pgdata.key` is generated only when both the key and the
encrypted volume are absent. If the volume exists but the key is missing, startup
refuses and asks you to restore the key.

### Operator configuration (`.env.prod`)

Required:

| Name | Purpose |
| --- | --- |
| `PROD_HOST` | public DNS name; all origins derive from it |
| `PROD_TLS_MODE` | `acme` (public certificates), `files` (your certificate) or `selfsigned` (local rehearsal) |
| `PROD_ACME_EMAIL` | ACME account email (`acme` mode) |
| `PROD_TLS_CERT_FILE`, `PROD_TLS_KEY_FILE` | absolute paths to PEM certificate chain and key (`files` mode) |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM` | outgoing mail relay and sender |
| `SUPPORT_MAIL_TO` | recipient for contact-form messages |
| `ADMIN_USERNAME` | bootstrap staff account local part (`<name>@bytecore.ee`) |

Optional:

- `SMTP_USERNAME` / `SMTP_PASSWORD`, `SMTP_STARTTLS` (default `true`),
  `SMTP_IMPLICIT_TLS`. A non-local relay without STARTTLS or implicit TLS is
  rejected.
- `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`. These must
  be **test-mode** keys (`sk_test_`, `pk_test_`); live keys are rejected.
  - Without them, browsing, cart and account features work. Card payment is shown as
    unavailable.
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`. Both or neither; without them,
  sign-in with Google is not offered.
- Ports and binding: `PROD_HTTP_PORT`, `PROD_HTTPS_PORT`, `PROD_KRATOS_PORT`,
  `PROD_HYDRA_PORT`, `PROD_BRIDGE_PORT`, `PROD_BIND`.
- Other settings: `PROD_HSTS_MAX_AGE`, `PROD_SECRETS_DIR`, `PROD_SUBNET`,
  `PROD_HIVE_IP`.

Validation reports variable names only. It also rejects placeholder values, a
malformed `KRATOS_SECRETS_CIPHER`, live Stripe keys and half-configured Google
credentials.

## Topology

```text
Internet ──> Caddy (only published service)
   :443   storefront + BFF (Next.js, production build) ── /webhooks/stripe ──> payment-service
   :9433  Kratos public          :9444 Hydra public        :9446 OAuth bridge
   :80    HTTP → HTTPS redirect and ACME challenge
internal network: Hive Router, Spring backend, Postgres (TLS only, gocryptfs),
                  Kafka, Kratos/Hydra admin APIs, oauth-service, auth-maintenance,
                  optional Mailpit (sandbox only)
```

- **Edge and ports.**
  - Only Caddy publishes ports.
  - Databases, Kafka, Hive, the backend and provider admin APIs have no host port.
  - Caddy sets HSTS, removes `Server` and overwrites `X-Forwarded-For` from the real
    peer.
  - It trusts no upstream proxy, so a client cannot choose its rate-limit identity.
- **Backend trust and limits.**
  - The backend trusts forwarded addresses only from the fixed Hive Router IP.
  - Rate limits keep their configured values.
- **Disabled in production.**
  - Kratos and Hydra run without `--dev`.
  - Spring runs with the `prod` profile and Next.js runs with `NODE_ENV=production`.
  - Hive introspection and CORS are off.
  - The OAuth dev-token endpoint (`:4447`) and the `bytecore-web` development client
    are not created.
  - `ADMIN_ALLOW_AAL1` and similar bypasses are not set.
- **Authentication controls.**
  - Staff operations still require AAL2: the backend rejects AAL1 tokens.
  - Access tokens are revoked on logout (see
    [`docs/auth/browser-contract.md`](auth/browser-contract.md#token-revocation)).
  - Session cookies are `__Host-`, `Secure` and `HttpOnly`.
- **Readiness.** `make prod` prints "ready" only after Compose reports every service
  healthy and the edge smoke checks pass:
  - Hive readiness;
  - storefront home and catalog;
  - Kratos health;
  - Hydra issuer discovery;
  - the webhook route.

  Otherwise it prints the failing services' recent logs and exits non-zero. A failed
  migration (`flyway`, `hydra-migrate`, `kratos-migrate`) blocks dependent services
  and is reported this way.

## TLS modes

- **`acme`.**
  - Caddy obtains public certificates for `PROD_HOST` and stores them in the
    `caddy_data` volume.
  - Requires a public DNS record pointing at the host and reachable ports 80/443.
  - The extra origins (9433/9444/9446) use the same name.
- **`files`.** Your certificate chain and key are mounted read-only.
- **`selfsigned`** (default).
  - A local CA in `infrastructure/prod/secrets/tls/` signs the edge certificate.
  - For local runs only: browsers must trust `ca.crt`.

Independently of the mode, internal traffic to Postgres uses certificates from the
generated internal CA with `sslmode=verify-full`. Plaintext TCP connections are
rejected.

## Running on a real host

This is optional; the local defaults need none of it. In `.env.prod`, set:

- `PROD_HOST`;
- `PROD_BIND=0.0.0.0`;
- `PROD_TLS_MODE=acme` with `PROD_ACME_EMAIL`, or `files`;
- a real SMTP relay.

Then complete the external setup below. These steps are prepared but cannot be verified
without a real host and accounts:

1. **DNS:** an `A`/`AAAA` record for `PROD_HOST`, plus open ports 80, 443, 9433, 9444
   and 9446.
2. **Google OAuth** (optional): add the authorized redirect URI
   `https://PROD_HOST/self-service/methods/oidc/callback/google` (with
   `:PROD_HTTPS_PORT` if not 443) and the JavaScript origin `https://PROD_HOST`.
3. **Stripe test mode** (optional): in the Stripe dashboard (test mode), create a
   webhook endpoint `https://PROD_HOST/webhooks/stripe`.
   - Events: `payment_intent.succeeded`, `payment_intent.payment_failed`,
     `payment_intent.canceled`, `payment_intent.processing`.
   - Refund outcomes come from the payment service's idempotent Stripe API calls,
     so no refund webhook events are needed.
   - Put its signing secret into `STRIPE_WEBHOOK_SECRET`. Setting it turns off the
     local `stripe-listener` container, which is meant for local runs only.
4. **SMTP:** a relay that accepts mail from `SMTP_FROM`, with SPF/DKIM for that
   domain.
   - For local sandbox tests only, `SMTP_HOST=mailpit` and `SMTP_PORT=1025` start the
     optional Mailpit container.
   - Its UI is at `127.0.0.1:18025`. Mailpit captures mail and never delivers to real
     inboxes.

## Initial administrator

On first start, `oauth-service` creates the staff account
`ADMIN_USERNAME@bytecore.ee` with role `ADMIN` and the password from
`ADMIN_PASSWORD` in `.env.prod`. The bootstrap is create-only: later runs log
"already linked, no-op" and never reset the password.

1. Sign in at `https://PROD_HOST/login`.
2. Open `/admin/security` and enrol an authenticator (TOTP). Every privileged
   operation requires this second factor; the backend rejects AAL1 staff tokens.
3. Change the password under `/account/security`.
4. Remove `ADMIN_PASSWORD` from `.env.prod` or store it elsewhere.

`make prod` loads no demo customers, orders or catalog. Add categories and products under
`/admin`, upload product images there, or import a catalog with the CSV/JSON bulk
import (`docs/api/bulk-import.md`).

For a showcase, run **`make seed-prod`** (`bash scripts/prod.sh seed`) once after `make prod`.
It loads the same demo dataset as the reviewer stack
(`backend/src/main/resources/db/testdata/test-data.sql`):

- 42 products and 46 variants, with categories, warehouses and stock;
- 15 demo users, their carts and wishlists;
- 12 orders.

The seed has these safeguards and limits:

- It loads in one transaction, only into a fresh stack: no products, and no user other than
  the bootstrap admin (user 1, `admin`).
- It refuses to run otherwise, so it never replaces data created through the application.
- It then restarts the backend so the startup backfill encrypts the seeded personal data
  at rest.
- Demo customers have no passwords; register your own account to shop.
- Product images are not in Git; upload them under `/admin/products`.

## Persistent data

| Item | Location | Notes |
| --- | --- | --- |
| Databases (retail, payment_service, hydra, kratos, captcha) | volume `bytecore-prod_postgres_crypt_volume` | gocryptfs ciphertext, including the data directory and WAL |
| Storage key | `infrastructure/prod/secrets/pgdata.key` | keep separate from volume backups |
| Uploaded product images and generated sizes | volume `bytecore-prod_media_volume` | |
| Kafka log | volume `bytecore-prod_kafka_volume` | transient: outboxes in Postgres are the source of truth |
| Certificates | volume `bytecore-prod_caddy_data`, `infrastructure/prod/secrets/tls/` | ACME account and certificates; internal CA |
| Configuration and application key rings | `.env.prod` | `APP_DATA_KEYS`, `AUTH_SEAL_KEYS`, `KRATOS_SECRETS_*`, `HYDRA_SYSTEM_SECRET` |

`.env.prod` and `infrastructure/prod/secrets/` are Git-ignored and lie outside every
image build context (`backend/`, `frontend/`, `payment-service/`,
`infrastructure/oauth/*`). Secrets reach containers only as runtime environment
variables or read-only mounts.

### Encryption boundary

- **What is encrypted.**
  - Postgres runs on a gocryptfs mount, so the database files and WAL on the Docker
    volume are ciphertext. This includes provider-owned Kratos and Hydra data.
  - Application-level AES-GCM additionally protects identity, contact, address and
    order PII columns, using `APP_DATA_KEYS` (see
    [`docs/security/encryption-at-rest.md`](security/encryption-at-rest.md)).
- **What is not.** Storage encryption does not encrypt SQL query results or logical
  dumps.
  - A `pg_dumpall` backup contains provider data in plaintext and application PII as
    application ciphertext.
  - Store backups encrypted and separately from the keys.
- **Key failures.** A missing or wrong storage key stops Postgres with a clear error;
  it never falls back to plaintext.

## Backup

Run while the stack is up. Replace `bytecore-prod` if you use another
`PROD_PROJECT`.

```bash
mkdir -p backup && umask 077
DBU=$(sed -n 's/^DB_USERNAME=//p' .env.prod)
docker exec bytecore-prod-postgres-1 pg_dumpall -U "$DBU" --clean --if-exists | gzip > backup/db.sql.gz
docker run --rm -v bytecore-prod_media_volume:/data:ro -v "$PWD/backup":/backup alpine \
  tar czf /backup/media.tgz -C /data .
```

Store separately from the dump:

- `.env.prod`;
- `infrastructure/prod/secrets/pgdata.key`;
- `infrastructure/prod/secrets/tls/`.

Without `APP_DATA_KEYS`, the encrypted application columns in the dump cannot be
read.

## Restore

1. Stop the application containers, keeping Postgres running:

   ```bash
   docker ps -q --filter label=com.docker.compose.project=bytecore-prod \
     | xargs docker inspect -f '{{.Name}} {{index .Config.Labels "com.docker.compose.service"}}' \
     | awk '$2!="postgres"{print $1}' | xargs docker stop
   ```

2. Restore the databases:

   ```bash
   gunzip -c backup/db.sql.gz | docker exec -i bytecore-prod-postgres-1 psql -U "$DBU" -d postgres -q
   ```

   Two errors are expected and harmless, because the connected superuser cannot be
   dropped or recreated:
   - `current user cannot be dropped`;
   - `role "<user>" already exists`.

3. Restore media:

   ```bash
   docker run --rm -v bytecore-prod_media_volume:/data -v "$PWD/backup":/backup alpine \
     sh -c 'rm -rf /data/* && tar xzf /backup/media.tgz -C /data'
   ```

4. Run `make prod`.

For a new host, first restore:

- `.env.prod`;
- `pgdata.key`, only if you are also copying the encrypted volume. Otherwise let
  `make prod` create a new volume and key, then restore the dump into it;
- `tls/`.

Then run `make prod` and continue from step 1. The application key rings in
`.env.prod` must be the original ones.

## Upgrade and rollback

1. Take a backup (above).
2. Check out the new release: `git fetch && git checkout <tag>`.
3. Run `make prod`.
   - Images are rebuilt.
   - Flyway, Hydra and Kratos migrations run forward only.
   - Containers are replaced; volumes are kept.
4. Rollback: check out the previous tag. If the new release ran migrations, restore
   the pre-upgrade backup (above) before `make prod`. Older code is not guaranteed
   to run on a newer schema.

Key rotation is never automatic. `APP_DATA_KEYS` and `AUTH_SEAL_KEYS` are key rings:
add a new key first, keep the old ones for decryption, and see the encryption
document.

## Stopping without affecting dev or review

`make prod-down` addresses only the `bytecore-prod` project. The development
(`infrastructure`) and review projects use different project names, networks and
volumes and keep running. Their volumes are never referenced by the production
files.

To rehearse safely next to an existing installation:

```bash
PROD_PROJECT=dcr-prod-tmp PROD_ENV_FILE=/tmp/dcr-prod-tmp/env make prod
```

Use different ports and `PROD_SECRETS_DIR` in that env file.
