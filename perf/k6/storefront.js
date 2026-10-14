// k6 load scenarios for the ByteCore storefront. Run via perf/run.sh (Docker only).
// SCENARIO=browse|catalog|cart|capacity, BASE_URL=storefront origin.
import http from "k6/http";
import { check, sleep, group } from "k6";
import { SharedArray } from "k6/data";
import { Counter } from "k6/metrics";

// Throttled responses (expected rate-limiter behaviour) counted apart from application errors.
const rateLimited = new Counter("rate_limited_429");

const BASE = (__ENV.BASE_URL || "http://127.0.0.1:3000").replace(/\/$/, "");
const SCENARIO = __ENV.SCENARIO || "browse";

// Exact allow-listed operation sources, read from the generated BFF registry.
const SOURCES = (() => {
  const text = open(__ENV.OPERATIONS_FILE || "/work/generated.ts");
  const out = {};
  const re = /export const (\w+)Source = (".*");/g;
  let m;
  while ((m = re.exec(text)) !== null) out[m[1]] = JSON.parse(m[2]);
  return out;
})();

const SEARCH_TERMS = new SharedArray("terms", () => ["phone", "laptop", "usb", "cable", "monitor", "keyboard", "mouse", "audio"]);

// Each VU simulates a distinct shopper. Next is the edge in the dev stack, so a per-VU
// X-Forwarded-For (RFC 2544 benchmark range) selects per-client rate-limit buckets.
// SINGLE_CLIENT=1 sends everything from one address to exercise the limiter instead.
function clientHeaders() {
  if (__ENV.SINGLE_CLIENT === "1") return {};
  return { "X-Forwarded-For": `198.18.${Math.floor(__VU / 250) % 256}.${(__VU % 250) + 1}` };
}

function gql(name, variables, headers = {}) {
  const res = http.post(`${BASE}/graphql`, JSON.stringify({ operationName: name, query: SOURCES[name], variables }), {
    headers: { "Content-Type": "application/json", Origin: BASE, ...clientHeaders(), ...headers },
    tags: { name: `gql:${name}` },
  });
  if (res.status === 429) rateLimited.add(1, { name });
  let body = null;
  try { body = res.json(); } catch (_) { /* non-JSON is a failure below */ }
  check(res, { [`${name} 200`]: (r) => r.status === 200, [`${name} no errors`]: () => body && !body.errors });
  return body;
}

function page(path, label) {
  const res = http.get(`${BASE}${path}`, { headers: clientHeaders(), tags: { name: `page:${label}` }, redirects: 0 });
  check(res, { [`${label} 200`]: (r) => r.status === 200 });
  return res;
}

let slugs = [];
let variantIds = [];
let catalogTried = false;
function ensureCatalog() {
  if (catalogTried) return;
  catalogTried = true;
  const body = gql("CatalogListing", { input: { page: 0, size: 20, sort: "PRICE_ASC" } });
  const items = body && body.data ? body.data.searchProducts.items : [];
  slugs = items.map((i) => i.slug);
  variantIds = items.flatMap((i) => i.variants.filter((v) => v.isActive).map((v) => v.id));
}
const pick = (a) => a[Math.floor(Math.random() * a.length)];

export function browse() {
  ensureCatalog();
  group("browse", () => {
    page("/", "home");
    sleep(1);
    page("/catalog/all-products", "listing");
    sleep(1);
    page(`/search?q=${encodeURIComponent(pick(SEARCH_TERMS))}`, "search");
    sleep(1);
    if (slugs.length) page(`/products/${pick(slugs)}`, "product");
    sleep(1);
  });
}

export function catalog() {
  ensureCatalog();
  const sorts = ["PRICE_ASC", "PRICE_DESC", "RATING_DESC"];
  const r = Math.random();
  if (r < 0.4) gql("CatalogListing", { input: { page: Math.floor(Math.random() * 2), size: 20, sort: pick(sorts) } });
  else if (r < 0.6) gql("CatalogListing", { input: { page: 0, size: 20, sort: "RELEVANCE", query: pick(SEARCH_TERMS) } });
  else if (r < 0.85) gql("CatalogSuggestions", { query: pick(SEARCH_TERMS), limit: 6 });
  else if (slugs.length) page(`/products/${pick(slugs)}`, "product");
}

export function cart() {
  ensureCatalog();
  if (!variantIds.length) return;
  const h = { "X-Guest-Cart-Request": "1" };
  group("guest-cart", () => {
    // Think time keeps one shopper near the cart-write policy (1 write/s sustained, burst 30).
    gql("GuestCartStart", {}, h);
    sleep(1);
    const added = gql("GuestCartAdd", { input: { productVariantId: pick(variantIds), quantity: 1 } }, h);
    sleep(1);
    gql("GuestCartView", {}, h);
    const item = added && added.data && added.data.addGuestCartItem && added.data.addGuestCartItem.items[0];
    if (item) {
      sleep(1);
      gql("GuestCartUpdate", { id: item.id, input: { quantity: 2 } }, h);
      sleep(1);
      gql("GuestCartRemove", { id: item.id }, h);
    }
  });
  sleep(1);
}

export function mixed() {
  const r = Math.random();
  if (r < 0.5) catalog(); else if (r < 0.8) browse(); else cart();
}

const targets = { "http_req_duration": ["p(90)<2000"], "http_req_failed": ["rate<0.05"], "checks": ["rate>0.98"] };

const scenarios = {
  // 1) 50 concurrent anonymous shoppers browsing SSR pages.
  browse: { browse: { executor: "constant-vus", exec: "browse", vus: 50, duration: __ENV.DURATION || "3m" } },
  // 2) Catalog/search API at a fixed 10 TPS normal load then 30 TPS peak.
  catalog: { catalog: { executor: "ramping-arrival-rate", exec: "catalog", startRate: 10, timeUnit: "1s",
    preAllocatedVUs: 60, maxVUs: 200,
    stages: [{ target: 10, duration: "2m" }, { target: 30, duration: "30s" }, { target: 30, duration: "1m" }, { target: 10, duration: "30s" }] } },
  // 3) 50 concurrent guest cart write transactions (start/add/view/update/remove).
  cart: { cart: { executor: "constant-vus", exec: "cart", vus: 50, duration: __ENV.DURATION || "3m" } },
  // 4) Capacity search: ramp mixed users until p90 exceeds 5s (aborts) or the plan ends.
  capacity: { capacity: { executor: "ramping-vus", exec: "mixed", startVUs: 10,
    stages: [{ target: 50, duration: "1m" }, { target: 100, duration: "1m" }, { target: 200, duration: "1m" },
      { target: 300, duration: "1m" }, { target: 400, duration: "1m" }, { target: 500, duration: "1m" }] } },
};

export const options = {
  scenarios: scenarios[SCENARIO],
  thresholds: SCENARIO === "capacity"
    ? { "http_req_duration": [{ threshold: "p(90)<5000", abortOnFail: true, delayAbortEval: "30s" }] }
    : targets,
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "max"],
  insecureSkipTLSVerify: __ENV.INSECURE === "1",
};
