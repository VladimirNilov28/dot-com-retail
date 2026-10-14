# Reviewer stack (Docker + TLS, one command)

This stack runs the whole application in Docker with TLS, so a reviewer can use the
complete browser flow: catalog, registration, email verification, sign-in, cart,
checkout and staff admin. It is separate from the HTTP development workflow
(`make dev` plus `bun dev` on http://127.0.0.1:3000, see [local-setup.md](local-setup.md)).
That workflow is unchanged, because everything described here is opt-in through
`infrastructure/compose.review.yml` and `.env.review`.

## Prerequisites

- Docker Engine with Docker Compose **v2.24.4 or newer** (the override uses `!reset`
  and `!override`).
- A Docker host that provides `/dev/fuse` (standard on Linux). The review Postgres
  stores its data directory in a gocryptfs-encrypted volume, and its container gets
  `/dev/fuse`, `CAP_SYS_ADMIN` and `apparmor:unconfined`. `review-up.sh` generates
  the storage key in `infrastructure/review/secrets/pgdata.key` (Git-ignored).
  Back it up: without it the database cannot be opened. See
  [`security/encryption-at-rest.md`](security/encryption-at-rest.md#storage-layer-encryption-review-stack).
- `bash` and the repository checkout. On Windows, use WSL2 or Git Bash.
- Optional: the [Stripe CLI](https://docs.stripe.com/stripe-cli) for webhooks, and
  Google OAuth credentials.

The host does not need Java, Node, Bun, Python, openssl or psql. Certificates,
secrets and the frontend build are all produced inside containers.

## Start and stop

```bash
make review            # same as: scripts/review-up.sh
make review-down       # same as: scripts/review-down.sh (keeps data)
scripts/review-down.sh --volumes   # also deletes database, Kafka and media volumes
```

`scripts/review-up.sh` takes these steps:

1. Creates `.env.review` from `.env.review.example` on the first run. It fills in
   strong random secrets generated inside a container. Later runs keep the existing
   file.
2. Generates the local CA and certificates into `infrastructure/review/certs/` if they
   are missing. This directory is git-ignored.
3. Runs `docker compose -p bytecore-review -f infrastructure/compose.yml -f
   infrastructure/compose.review.yml --env-file .env.review up -d --build --wait`. The
   command returns only once every service reports healthy. The first build takes
   several minutes.
4. Loads the demo catalog (`backend/src/main/resources/db/testdata/test-data.sql`) on
   a fresh database only, meaning no products and no users other than the bootstrap
   admin. Set `REVIEW_SEED=0` to skip this.
5. Prints the URLs and the admin login name. It never prints secrets; the password
   stays in `.env.review`.

The script is idempotent: running it again rebuilds and restarts the stack and keeps
existing data.

## URLs and ports

The stack publishes only the Caddy TLS edge, bound to `127.0.0.1` by default. Every
other service stays on the private Compose network. The hostname `bytecore.localhost`
resolves to loopback in browsers and curl. Inside the network, the same name is an
alias for Caddy, so containers and browsers use identical public URLs.

| URL                                   | Service                                        |
|---------------------------------------|------------------------------------------------|
| https://bytecore.localhost:8443       | Storefront / BFF (Next.js), `/webhooks/stripe` → payment-service |
| https://bytecore.localhost:8444       | Hydra public (OAuth2/OIDC issuer)              |
| https://bytecore.localhost:8433       | Kratos public                                  |
| https://bytecore.localhost:8446       | OAuth bridge (login/consent)                   |
| https://bytecore.localhost:8425       | Mailpit (verification, recovery, order emails) |

These ports do not overlap with the dev stack's ports (3000, 4433–4447, 5432, 8080,
8081, 9092, 8025, 1025), so both stacks can run at the same time. The Docker subnet
also differs from the dev subnet.

### Overrides

Set these in the shell or in `.env.review`. A shell value takes precedence.

| Variable | Default | Purpose |
|---|---|---|
| `REVIEW_PROJECT` | `bytecore-review` | Compose project name (container/volume prefix) |
| `REVIEW_ENV_FILE` | `.env.review` | Env file to create and use |
| `REVIEW_HOST` | `bytecore.localhost` | Public hostname (must resolve to loopback; regenerate certs after changing) |
| `REVIEW_STOREFRONT_PORT` / `REVIEW_HYDRA_PORT` / `REVIEW_KRATOS_PORT` / `REVIEW_BRIDGE_PORT` / `REVIEW_MAILPIT_PORT` | 8443 / 8444 / 8433 / 8446 / 8425 | Published ports (the same inside the network) |
| `REVIEW_BIND` | `127.0.0.1` | Host interface for published ports |
| `REVIEW_SUBNET` / `REVIEW_HIVE_IP` | `172.28.90.0/24` / `172.28.90.40` | Internal network and the Hive Router's fixed address, the only peer Spring trusts for `X-Forwarded-For` (change together on collision) |
| `REVIEW_WAIT_TIMEOUT` | `900` | Seconds `up --wait` may take |
| `REVIEW_SEED` | `1` | `0` skips the demo dataset |
| `REVIEW_CERT_DIR` | `infrastructure/review/certs` | Certificate output directory |

## TLS and trusting the local CA

`scripts/gen-review-certs.sh` runs openssl in a pinned `alpine/openssl` container with
no network. It produces the following:

- `ca.crt`: a local CA with **name constraints** that limit it to `bytecore.localhost`
  and `postgres`. The CA private key is **destroyed** right after signing. Even if
  you trust this CA, it cannot issue certificates for any other site.
- `server.crt`/`server.key`: the edge certificate for `REVIEW_HOST`.
- `postgres.crt`/`postgres.key`: the database certificate, with SAN `postgres`.

To replace the certificates, for example after changing `REVIEW_HOST`, run
`scripts/gen-review-certs.sh --force` and then `make review`. A browser that trusted
the old CA has to import the new `ca.crt`.

You can trust `infrastructure/review/certs/ca.crt` in one of these ways, or click
through the browser warning for each port:

- **Linux (Debian/Ubuntu):** `sudo cp infrastructure/review/certs/ca.crt
  /usr/local/share/ca-certificates/bytecore-review.crt && sudo update-ca-certificates`.
  On Fedora/Arch, use `sudo trust anchor infrastructure/review/certs/ca.crt`.
  Chrome on Linux uses its own NSS store: open Settings → Privacy and security →
  Security → Manage certificates → Authorities → Import.
- **macOS:** `sudo security add-trusted-cert -d -r trustRoot -k
  /Library/Keychains/System.keychain infrastructure/review/certs/ca.crt`
- **Windows** (elevated PowerShell): `certutil -addstore -f Root ca.crt`. Copy the file
  out of WSL first, for example via `\\wsl$\...`.
- **Firefox:** open Settings → Privacy & Security → Certificates → View Certificates →
  Authorities → Import, then tick "Trust this CA to identify websites". Alternatively,
  set `security.enterprise_roots.enabled=true` to use the OS store.

To remove the CA later, delete it from the same store (on Debian/Ubuntu, also run
`update-ca-certificates --fresh`).

For curl: `curl --cacert infrastructure/review/certs/ca.crt https://bytecore.localhost:8443/`.

### Encryption in transit inside the stack

- Browser traffic goes to the Caddy edge over TLS 1.2/1.3 with a short HSTS max-age
  (1 day), so trusting and then removing the CA stays painless.
- The containers that call public URLs (the frontend BFF calling Hydra, Kratos and the
  bridge) go through the same Caddy edge and trust the CA via `NODE_EXTRA_CA_CERTS`.
- **PostgreSQL accepts network connections only over TLS** (`hostssl` with
  scram-sha-256; plain `host` connections are rejected). The backend, the frontend
  auth and captcha stores, Hydra, Kratos and the payment service connect with
  `sslmode=verify-full` (which the frontend requires in this mode) against the
  `postgres` certificate.
- Behind the edge, service-to-service HTTP (Caddy → upstreams, backend ↔ Hive router,
  Kafka) stays on the private Compose network, the same as in the dev stack.

## Secrets provisioning

`.env.review` is git-ignored and created with mode 600. On the first run the script
replaces the `@hex32@`/`@hex16@`/`@b64url32@` markers in `.env.review.example` with
random values generated in a container. This covers database passwords, Ory system
and cookie secrets, the bridge secret, the bootstrap admin password, and the BFF
sealing keys.

- Keep `.env.review` together with the Docker volumes. The databases are initialised
  with these secrets, so if you delete or regenerate the file you must also run
  `scripts/review-down.sh --volumes`.
- You can add optional settings later (`STRIPE_*`, `GOOGLE_*`) by editing the file and
  re-running `make review`.

## Accounts

- **Customers:** register at `/register`. The verification code arrives in Mailpit
  (https://bytecore.localhost:8425). The demo dataset also contains catalog products
  and sample data.
- **Bootstrap administrator:** `admin@bytecore.ee`. The password is `ADMIN_PASSWORD` in
  `.env.review`. The oauth-service creates this identity on first start and keeps it
  reconciled (Spring user id 1, role `ADMIN`). Keep `ADMIN_USERNAME=admin`, because the
  demo seed re-creates user id 1 with that username.
- **Staff MFA:** admin pages require AAL2. After signing in, open
  https://bytecore.localhost:8443/admin/security and enrol an authenticator app (TOTP).
  This narrowly authorized enrollment page is the only admin page reachable before
  MFA. After enrolling, every staff sign-in asks for the TOTP code before
  `/admin/products` is available. There is no bypass.

  The "Verify authenticator" step-up returns to `/admin/...`, which is permitted by the
  storefront return-path allowlist (`safeReturnTo`, fixed in `89d1a32`).

## Optional: Stripe (test mode)

1. Put your **test-mode** `STRIPE_SECRET_KEY` and `STRIPE_PUBLISHABLE_KEY` into
   `.env.review`.
2. Forward webhooks through the TLS edge:

   ```bash
   stripe listen --forward-to https://bytecore.localhost:8443/webhooks/stripe --skip-verify
   ```

   `--skip-verify` is needed because the Stripe CLI does not use the OS trust store
   for the local CA. Only POST requests are routed to the payment service; other
   methods return 405.
3. Copy the printed `whsec_...` value into `STRIPE_WEBHOOK_SECRET` and run
   `make review` again so the payment service picks it up.

Without keys, the catalog, cart and auth flows work, but card payment is disabled.

## Optional: Google sign-in

1. Create an OAuth client (Web application) in Google Cloud Console.
2. Register this **authorized redirect URI** (adjust it if you changed the host or port):
   `https://bytecore.localhost:8443/self-service/methods/oidc/callback/google`
3. Set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `.env.review` and run
   `make review` again.

Google may reject `.localhost` redirect URIs for some client types. If it does, use a
real hostname that resolves to the machine, set `REVIEW_HOST`, regenerate the
certificates with `--force`, and register the matching callback.

## Verified behaviour

The stack was verified against a disposable project
(`REVIEW_PROJECT=dcr-review-test`):

- All services become healthy.
- The storefront renders over TLS and the demo catalog is seeded.
- OIDC discovery reports the https issuer.
- A real headless-Chromium registration → email code (Mailpit) → verification →
  sign-in → `/account` flow works.
- The bootstrap admin can sign in and reaches the TOTP gate.
- Postgres rejects non-TLS connections.
- Products, accounts and media survive a `review-down` / `review-up` cycle.
- Re-checked on 14 Oct after the commerce, admin, reviews, customer and encryption
  merges (disposable project `dcr-review-check`):
  - all 12 services became healthy;
  - Flyway reached V25;
  - the seeded addresses are stored encrypted (`enc:v1:`);
  - `/`, `/about`, `/contact`, `/cart` and `/catalog/all-products` returned 200, and
    an unknown path returned 404;
  - GraphQL catalog search works through the TLS edge;
  - a contact form submission delivered both the support email and the
    acknowledgement to Mailpit.
- Re-checked on 15 Oct (00:30 local) after the token-revocation, PII-encryption,
  payment-decline, purchase-gated reviews, media-variant and edge merges, from a
  fresh disposable project (`dcr-review-tmp`, own volumes, torn down afterwards):
  - `review-up.sh` completed; all services healthy, including `auth-maintenance`;
  - the raw `postgres_crypt_volume` contained 0 files with a seeded product name
    (the decrypted view inside the container: 1) and no plaintext file names;
  - `scripts/verify-token-revocation.mjs`, run inside the review network: 19/19
    PASS (rotation, old refresh rejected, reuse detection, logout and subject
    revocation → Spring 401);
  - rate limiting through Caddy with two client containers: client A got
    5 × 200 then 429 with `Retry-After: 120` on the contact policy; forged
    `X-Forwarded-For`/`Forwarded`/`X-Real-IP` kept A in its bucket; client B was
    independent. An authenticated subject stayed limited when the same token was
    used from another address, while that address's anonymous bucket was separate.
    Rotating `X-Forwarded-For` sent straight to Spring from an untrusted container
    did not escape the bucket. Only Caddy publishes host ports;
  - `/`, `/catalog/all-products`, `/search`, a PDP, `/cart`, `/checkout`, `/about`,
    `/contact`, `/account` and `/admin/products` returned 200 over TLS; unknown → 404.

## Troubleshooting

- **`up --wait` fails or times out:** run `docker compose -p bytecore-review -f
  infrastructure/compose.yml -f infrastructure/compose.review.yml --env-file
  .env.review ps`, then check `... logs <service>`. On slow machines, raise
  `REVIEW_WAIT_TIMEOUT`.
- **Port already in use:** change the `REVIEW_*_PORT` values. Ports are the same inside
  and outside the network, so all URLs follow automatically.
- **Certificate errors after changing `REVIEW_HOST`:** run
  `scripts/gen-review-certs.sh --force`, then `make review`, then re-import `ca.crt`.
- **`bytecore.localhost` does not resolve** (some non-browser tools): add
  `127.0.0.1 bytecore.localhost` to the hosts file.
- **`!reset`/`!override` errors:** your Docker Compose is older than v2.24.4; upgrade it.
- **Postgres exits with `encrypted-storage: FATAL: …`:** the log line names the cause.
  - *Wrong key* or *key file missing*: restore `infrastructure/review/secrets/pgdata.key`
    from your backup.
  - */dev/fuse* or *could not mount*: the host lacks FUSE, or a security profile blocks
    it. See the fallback in
    [`security/encryption-at-rest.md`](security/encryption-at-rest.md#storage-layer-encryption-review-stack).
- **Sign-in fails after regenerating `.env.review`:** the volumes still hold the old
  secrets. Run `scripts/review-down.sh --volumes` and then `make review`.
- **A clean slate:** run `scripts/review-down.sh --volumes`, delete `.env.review`,
  `infrastructure/review/certs/` and `infrastructure/review/secrets/`, then run
  `make review`.
