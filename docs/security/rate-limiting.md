# Rate limiting

The storefront API is protected by a **token bucket** per client and policy.
A bucket holds up to `capacity` tokens (the permitted burst) and refills
continuously at `refill-tokens / refill-period` (the sustained rate). Each
request consumes one token from every bucket that applies to it; a request
that finds fewer than one token is rejected without consuming anything.

## Where it runs

| Layer | Mechanism | Scope |
|---|---|---|
| Spring (`security/ratelimit`) | `RateLimitFilter` in the Spring Security chain, right after bearer-token authentication and before the guest-cart filter, DGS GraphQL and REST handlers | All API traffic (GraphQL and REST endpoints). `/actuator/**`, `GET /media/products/**` images and WebSocket upgrades are exempt. |
| Next BFF (`src/lib/rate-limit/token-bucket.ts`) | In-process buckets in `/auth/submit` | Login and recovery form submissions, which go to Kratos and never reach Spring. Registration is protected by Kratos + CAPTCHA. |

### PostgreSQL-backed buckets (Spring)

Buckets live in `rate_limit_buckets(bucket_key PK, tokens, updated_at)`
(Flyway `V23`). A decision is **one atomic statement**:

```sql
INSERT INTO rate_limit_buckets AS b (bucket_key, tokens, updated_at)
VALUES (:key, :capacity - 1, now())
ON CONFLICT (bucket_key) DO UPDATE
   SET tokens = LEAST(:capacity, b.tokens + elapsed(b.updated_at) * :rate) - 1,
       updated_at = GREATEST(b.updated_at, now())
 WHERE LEAST(:capacity, b.tokens + elapsed(b.updated_at) * :rate) >= 1
RETURNING tokens
```

The row lock taken by `ON CONFLICT DO UPDATE` serialises concurrent requests
for the same key, so limits hold across any number of backend instances. No
row returned means "denied"; only then is the row read to compute
`Retry-After`. The refill arithmetic is mirrored by `TokenBucket` (unit tested).

Storage is bounded: `PostgresRateLimiter#deleteIdleBuckets` runs every
`cleanup-interval` and deletes rows untouched for `cleanup-idle-after`.
Startup validation rejects an idle period shorter than the time any policy
needs to refill completely, so deleting a row (≙ full bucket) never grants
extra tokens.

If the database is unavailable the filter **fails open** (logs a throttled
warning) rather than taking the API down with it.

## Keys

* **Authenticated** requests (valid JWT): `sub:<JWT subject>`.
* **Anonymous** requests: `ip:<client address>`.
* Trusted-proxy requests **without** a client address (server-side rendering
  and `unstable_cache` catalog reads by the Next server): `proxy:<proxy address>`,
  under the larger `proxy` policy instead of `general`.

Bucket keys are `<policy>|<key>`; keys over 150 characters are SHA-256 hashed.

### Client address and trusted proxies

Spring does **not** use `server.forward-headers-strategy`; the TCP peer is
authoritative. `X-Forwarded-For` (`rate-limit.client-ip-header`) is honoured
**only** when the TCP peer is inside `rate-limit.trusted-proxies` (CIDR list,
IP literals only — no DNS). The chain is walked right-to-left skipping trusted
hops; the first untrusted hop is the client, so entries a client prepends
cannot select another bucket. Requests from untrusted peers are keyed by the
peer address and any forwarded header is ignored.

The request path in this project is browser → Next BFF (`/graphql`) → Hive
Router → Spring:

1. The BFF (`src/lib/graphql/client-address.ts`) takes the right-most
   `X-Forwarded-For` entry (Next fills it from the socket when absent), skipping
   `BFF_TRUSTED_PROXY_HOPS` entries appended by reverse proxies in front of
   Next (default `0`). It validates it with `net.isIP` and sends it as a single
   `X-Forwarded-For` value to Hive; browser-supplied forwarding headers are
   never passed through as-is.
2. Hive propagates `X-Forwarded-For` to the `retail` subgraph and propagates
   `Retry-After` back (`infrastructure/hive/router.config.yaml`).
3. Spring trusts the header because Hive's address is in
   `RATE_LIMIT_TRUSTED_PROXIES`:
   * dev (`compose.yml`): `127.0.0.1/32,::1/128,172.28.88.0/24` (pinned dev subnet);
   * review (`compose.review.yml`): `127.0.0.1/32,::1/128,${REVIEW_HIVE_IP}/32`
     — only the Hive Router's fixed address (`172.28.90.40` on the review
     subnet `172.28.90.0/24`), so no other container can name a client.

In the review stack the Caddy edge is in front of Next: it trusts no upstream
proxy, discards any client-supplied `X-Forwarded-For`/`Forwarded`/`X-Real-IP`
and sets `X-Forwarded-For` to the TCP peer, so with `BFF_TRUSTED_PROXY_HOPS=0`
the BFF forwards the real client address. Spring, Hive, the BFF and Postgres
publish no host ports in the review stack; only Caddy does.

**Deployment requirements / limitations**

* Only the BFF may reach Hive, and only Hive/BFF may reach Spring. In local
  development Hive (`:4002`) is exposed on the host, so a client talking to
  Hive directly can choose its `X-Forwarded-For`; that is acceptable for dev
  only.
* If Next itself is the internet edge, a client can send its own
  `X-Forwarded-For` which Next keeps; put a reverse proxy that overwrites or
  appends the header in front and set `BFF_TRUSTED_PROXY_HOPS` accordingly.
* Requests carrying an **invalid** bearer token are rejected with 401 by the
  bearer-token filter before the rate limiter runs.
* The BFF login/recovery buckets are per Next.js process.

## Policies

Configured under `rate-limit.policies` in `backend/src/main/resources/application.yaml`.
`general` and `proxy` are mandatory; any other policy applies **in addition**
when the GraphQL operation selects one of its `operations` (root fields of the
selected operation, following fragments and aliases).

| Policy | Burst | Sustained | Applies to |
|---|---|---|---|
| `general` | 60 | 20/s | every request, per subject/IP |
| `proxy` | 600 | 200/s | trusted proxy without client address |
| `cart-write` | 30 | 1/s | cart, guest-cart, merge and wishlist mutations |
| `sensitive` | 10 | 1 per 6 s | order placement/cancellation, `rateProduct`, review writes/votes, saved-address writes, `submitContactMessage` |
| `contact` | 5 | 5 per 10 min | `submitContactMessage` |

BFF (`src/lib/auth/handlers.ts`): login 10 burst / 1 per 30 s; recovery 5 burst /
1 per 2 min, per client address.

Review mutation names in `sensitive` are placeholders for the reviews feature;
align them with its final schema.

## Responses

Spring answers HTTP **429** with `Retry-After: <seconds>`, `Cache-Control:
no-store` and a GraphQL-shaped body:

```json
{"errors":[{"message":"Too many requests. Please wait 6s and try again.",
  "extensions":{"code":"RATE_LIMITED","classification":"RATE_LIMITED",
                "policy":"sensitive","retryAfterSeconds":6}}],"data":null}
```

The frontend maps HTTP 429 or code `RATE_LIMITED` to the failure kind
`rate-limited` ("Too many requests in a short time. Please wait a moment and try
again."), and the BFF answers the browser with 429 + `Retry-After`.
Rate-limited mutations are never retried automatically. Rate-limited auth
submissions redirect back to the form with `rateLimited=1`, which renders an
alert.

## Configuration and tests

* `rate-limit.enabled` defaults to `true`; the `prod` profile refuses to start
  with it disabled. Only `backend/src/test/resources/config/application.yaml`
  disables it, so ordinary tests are not throttled.
* Tests: `TokenBucketTest` (refill, burst, Retry-After, key isolation),
  `ClientAddressResolverTest` (spoofing), `RateLimitFilterTest` (operation
  parsing), `RateLimitIntegrationTest` and
  `RateLimitUntrustedPeerIntegrationTest` (Testcontainers PostgreSQL: 429 after a
  burst, recovery after refill, isolation per IP/subject, operation policy,
  spoofed `X-Forwarded-For` ignored, idle cleanup), and frontend
  `token-bucket.test.mjs`, `client-address.test.mjs`, `transport.test.mjs`.

```bash
cd backend && ./gradlew test --tests "ee.bytecore.backend.security.ratelimit.*"
cd frontend && bun test src/lib/rate-limit src/lib/graphql
```
