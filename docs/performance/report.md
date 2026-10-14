# Performance report (14 Oct 2026)

All numbers below were measured on 14 Oct 2026 with k6 0.57 (Docker image), using
`perf/run.sh`. Raw results are written to `perf/results/<timestamp>-<scenario>/`,
which is Git-ignored. Each result directory contains:

- `k6.log`;
- `summary.json`;
- `samples.csv.gz` (per-request samples, for the cart and capacity runs);
- `docker-stats.csv`, sampled every 5 s.

## Environment

**Host:** one Linux workstation with 20 logical CPUs and 15.6 GiB RAM. k6 ran on the
same host as the application, so absolute capacity is understated.

**Stack (dev topology):**

- Next.js 16 production build (`next start`, one process, on the host) acting as the BFF.
- Hive Router.
- Spring Boot (DGS).
- PostgreSQL 17.
- Kratos and Hydra.
- Kafka.
- The Go payment-service.
- Mailpit.

The reviewer stack (`make review`) runs the same images, plus the Caddy TLS edge and a
containerised Next.js.

**Dataset:** the seeded demo catalog with genuine reviews. Guest carts are created and
emptied by the test. No orders or payments are created, so Stripe is not load-tested.

**Client identity.** Every k6 virtual user (VU) sends its own `X-Forwarded-For`
address from the RFC 2544 benchmark range (198.18.0.0/15). This simulates distinct
shoppers. Without it, all load comes from one IP, and the token-bucket limiter
(`docs/security/rate-limiting.md`) correctly throttles it. The catalog scenario was
also run that way, and that run is kept as evidence:
`20261014-201605-catalog-single-ip`. In that run, 8.2% of requests returned 429
`RATE_LIMITED` with `Retry-After`, while latency stayed low (p90 18 ms). Those are
expected limiter responses, not application failures.

## Required targets

The targets are:

- p90 under 2 s;
- 50 concurrent users;
- at least 10 TPS;
- at least 98% successful requests;
- under 5% errors.

| # | Scenario | Load | Requests (rate) | p90 | p95 | Max | Failed | Checks | Result |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **browse**: SSR pages (home, listing, search, product) with 1 s think time | 50 constant VUs, 3 min | 6,450 (35.0/s) | 871 ms | 1.26 s | 2.67 s | 0.00% | 100% | Pass |
| 2 | **catalog**: GraphQL listing, search, suggestions and product pages | Arrival rate of 10 TPS for 2 min, then a 30 TPS peak (up to 60 VUs), 4 min | 4,259 (17.7/s) | 17 ms | 19 ms | 96 ms | 0.00% | 100% | Pass |
| 3 | **cart**: guest cart start → add → view → update quantity → remove, with about 1 s think time between writes | 50 constant VUs, 3 min | 9,050 (49.5/s), 9.8 cart transactions/s | 13 ms | 15 ms | 708 ms | 0.00% | 100% | Pass |

**Run directories:**

1. browse: `20261014-201254-browse`
2. catalog: `20261014-202235-catalog`
3. cart: `20261014-203742-cart`

The browse run predates per-VU addressing, so all its VUs shared one client address.
It still had no throttled or failed requests.

**Peak container CPU during these runs:**

- backend: 115%, 34% and 20% of one core;
- Postgres: 11–16%;
- the Next.js process: about 22% (cart run).

Host memory stayed below 61%.

An earlier cart run had no think time: each VU issued about 2.5 cart writes per second
against the `cart-write` policy (1 write/s sustained, burst 30). That run
(`20261014-202655-cart-no-thinktime`) showed 31% failures, all from rate limiting. The
p90 latency of the requests that succeeded was 24 ms.

## Capacity (scenario 4)

The capacity scenario uses a mixed workload: 50% catalog, 30% browse and 20% cart. It
ramps from 10 to 500 VUs over 6 minutes and aborts if p90 exceeds 5 s. Run directory:
`20261014-204106-capacity`.

The table gives p90 latency per 30 s window:

| VUs (end of window) | Requests/s | p90 | p95 | Host CPU | Next.js process CPU | Backend CPU |
|---|---|---|---|---|---|---|
| 49 | 46 | 154 ms | 224 ms | 8% | 57% | 16% |
| 99 | 89 | 544 ms | 691 ms | 12% | 96% | 25% |
| 148 | 102 | 1.26 s | 2.07 s | 14% | 111% | 35% |
| 198 | 129 | 1.36 s | 1.64 s | 15% | 126% | 53% |
| 248 | 129 | 1.97 s | 2.35 s | 16% | 137% | 39% |
| 298 | 124 | 2.67 s | 3.37 s | 15% | 133% | 35% |
| 348 | 105 | 4.56 s | 5.28 s | 14% | 118% | 38% |
| 398 | 145 | 3.55 s | 3.92 s | 15% | 121% | 47% |
| 448 | 130 | **5.20 s** | 5.71 s | 16% | 125% | 38% |
| 498 | 121 | 5.77 s | 6.81 s | 15% | 138% | 27% |

Over the whole run, there were 37,583 requests at 101 req/s, with p90 3.46 s and 0.00%
HTTP failures. Checks passed at 99.87%; 76 GraphQL responses (about 0.13%) carried
errors under saturation.

**Findings:**

- **Maximum concurrency before p90 exceeds 5 s:** about 400 concurrent users. p90 first
  passes 5 s in the window ending at 448 VUs. p90 stays under 2 s up to about 250 VUs.
- **Throughput:**
  - normal load (50 VUs): 35–50 req/s;
  - peak: throughput plateaus at about 125–145 req/s from about 200 VUs onward.
- **CPU and memory:** host CPU never exceeded 16%, and host memory peaked at 60.5%. The
  90% threshold was not reached at host level. The Next.js server process grew to an
  RSS of 944 MB.
- **Bottleneck:** the single Next.js server process. Its CPU rises to 96–138% (one
  saturated event-loop thread plus helper threads). Latency then grows on SSR pages
  first (product, listing and search), while Spring, Postgres and Hive have headroom.
  The bottleneck is per-process, not host-wide.
- **Remedy (not applied):** run several Next.js replicas behind the edge proxy, and add
  page caching (ISR/revalidate) for the catalog and product pages. This was not done
  before submission because it changes the deployment topology.
- **Kafka:** Kafka showed periodic CPU spikes (median 1%, occasional samples above
  100%) that are unrelated to load, since none of these scenarios publish to Kafka.

## Reproduce

```bash
perf/run.sh browse                      # 50 VUs, 3 minutes
perf/run.sh catalog                     # 10 TPS, then a 30 TPS peak
perf/run.sh cart                        # 50 VUs, guest cart writes
HOST_PID=<next pid> perf/run.sh capacity  # ramp to 500 VUs; HOST_PID samples a host process
SINGLE_CLIENT=1 perf/run.sh catalog     # everything from one address: shows the rate limiter
```

Only Docker is required.

**Running against the reviewer stack.** Behind Caddy, the edge replaces any
`X-Forwarded-For` with the real peer address. All VUs therefore share one client
bucket, and you should expect the same behaviour as a `SINGLE_CLIENT=1` run unless you
raise the rate-limit policies for the test.

```bash
INSECURE=1 perf/run.sh browse https://bytecore.localhost:8443
```
