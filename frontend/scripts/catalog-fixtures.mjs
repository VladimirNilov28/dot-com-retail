import assert from "node:assert/strict";
import { spawn, execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { createServer } from "node:http";
import { createWriteStream } from "node:fs";
import { mkdtemp, readFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { seedFixture } from "./catalog-fixture-data.mjs";

const root = resolve(import.meta.dirname, "../..");
const temporary = await mkdtemp(join(tmpdir(), "bytecore-catalog-fixture-"));
const identity = `bytecore-catalog-${randomBytes(6).toString("hex")}`;
const containers = [];
let spring;
let bridge;
let stopping = false;
const counts = {};
let mode = "normal";
let failureCategoryId;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const endpoint = "http://127.0.0.1:4063/graphql";
const authIssuer = new URL(process.env.CATALOG_AUTH_ISSUER ?? "http://127.0.0.1:4444");
const tokenEndpoint = new URL(process.env.CATALOG_TOKEN_URL ?? "http://127.0.0.1:4447/internal/token");
for (const url of [authIssuer, tokenEndpoint]) {
  assert.equal(url.protocol, "http:");
  assert.equal(url.hostname, "127.0.0.1", "Fixture credentials may only reach an explicitly configured loopback provider");
  assert.equal(url.username + url.password + url.search + url.hash, "");
}
assert.equal(authIssuer.pathname, "/");
assert.equal(tokenEndpoint.pathname, "/internal/token");

async function requireFreePorts() {
  for (const port of [5463, 8063, 4063, 4064]) {
    const probe = createServer();
    await new Promise((resolve, reject) => {
      probe.once("error", reject);
      probe.listen(port, "127.0.0.1", resolve);
    });
    await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  }
}

async function cleanup() {
  if (stopping) return;
  stopping = true;
  bridge?.close();
  if (spring && spring.exitCode === null) {
    spring.kill("SIGTERM");
    await Promise.race([new Promise((resolve) => spring.once("exit", resolve)), sleep(5000)]);
    if (spring.exitCode === null) spring.kill("SIGKILL");
  }
  for (const id of containers.reverse()) execFileSync("docker", ["rm", "-f", "-v", id], { stdio: "ignore" });
  await rm(temporary, { recursive: true });
}

function docker(environment, ...args) {
  try {
    const id = execFileSync("docker", ["run", "--detach", ...args],
      { encoding: "utf8", env: { ...process.env, ...environment } }).trim();
    containers.push(id);
    return id;
  } catch (error) {
    const id = typeof error.stdout === "string" ? error.stdout.trim() : "";
    if (/^[a-f0-9]{64}$/.test(id)) containers.push(id);
    throw error;
  }
}

async function ready(url) {
  for (let attempt = 0; attempt < 90; attempt++) {
    if (spring?.exitCode !== null && spring?.exitCode !== undefined) throw new Error("Isolated Spring exited; inspect startup output");
    try {
      if ((await fetch(url, { signal: AbortSignal.timeout(1500) })).ok) return;
    } catch (error) {
      if (!(error instanceof TypeError || error instanceof DOMException)) throw error;
    }
    await sleep(1000);
  }
  throw new Error(`Readiness failed: ${url}`);
}

async function setupToken() {
  if (process.env.CATALOG_SETUP_TOKEN) return process.env.CATALOG_SETUP_TOKEN;
  const email = process.env.CATALOG_SETUP_EMAIL;
  const password = process.env.CATALOG_SETUP_PASSWORD;
  assert.ok(email && password, "Supply CATALOG_SETUP_TOKEN or private CATALOG_SETUP_EMAIL/CATALOG_SETUP_PASSWORD. No credential fallback.");
  const response = await fetch(tokenEndpoint, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email, password }),
  });
  assert.ok(response.ok, `Supported dev-token operation failed: HTTP ${response.status}; AAL2 accounts require CATALOG_SETUP_TOKEN`);
  const data = await response.json();
  assert.ok(typeof data.access_token === "string", "Dev-token response has no access_token");
  return data.access_token;
}

async function handle(request, response) {
  const url = new URL(request.url, "http://127.0.0.1:4064");
  if (url.pathname === "/control") {
    const next = url.searchParams.get("mode");
    if (next) {
      if (!["normal", "delay", "error", "categories-error", "home-error", "home-delay", "home-section-error", "product-error", "product-delay"].includes(next)) {
        response.writeHead(400); response.end("Unknown verification mode"); return;
      }
      mode = next;
    }
    const categoryId = url.searchParams.get("categoryId");
    if (categoryId !== null) {
      assert.match(categoryId, /^[1-9]\d*$/, "Expected an isolated category ID");
      failureCategoryId = categoryId;
    }
    if (url.searchParams.get("reset") === "1") {
      for (const name of Object.keys(counts)) delete counts[name];
    }
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({ identity, mode, counts, failureCategoryId })); return;
  }
  if (url.pathname !== "/graphql" || request.method !== "POST") {
    response.writeHead(404); response.end(); return;
  }
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const body = Buffer.concat(chunks);
  const payload = JSON.parse(body);
  const name = payload.operationName ?? "unnamed";
  counts[name] = (counts[name] ?? 0) + 1;
  const current = mode;
  const listing = name === "CatalogListing";
  const home = name === "HomeDiscovery" || name === "HomeCategoryProducts";
  if ((current === "delay" && listing) || (current === "home-delay" && home) ||
      (current === "product-delay" && name === "ProductDetail")) await sleep(2500);
  if ((current === "error" && listing) || (current === "categories-error" && name === "CatalogCategories") ||
      (current === "home-error" && name === "HomeDiscovery") ||
      (current === "product-error" && name === "ProductDetail") ||
      (current === "home-section-error" && name === "HomeCategoryProducts" &&
        (!failureCategoryId || payload.variables?.categoryId === failureCategoryId))) {
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({ errors: [{ message: "Controlled verification failure", extensions: { errorType: "INTERNAL" } }] }));
    return;
  }
  const upstream = await fetch(endpoint, { method: "POST", headers: { "Content-Type": "application/json" }, body });
  response.writeHead(upstream.status, { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" });
  response.end(await upstream.text());
}

try {
  await requireFreePorts();
  const password = randomBytes(24).toString("hex");
  const database = docker({ POSTGRES_PASSWORD: password }, "--name", `${identity}-postgres`, "--publish", "127.0.0.1:5463:5432",
    "--env", "POSTGRES_DB=catalog_verification", "--env", "POSTGRES_USER=catalog_verification",
    "--env", "POSTGRES_PASSWORD", "postgres:17");
  let databaseReady = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    const check = spawn("docker", ["exec", database, "pg_isready", "-U", "catalog_verification"], { stdio: "ignore" });
    if (await new Promise((resolve) => check.once("exit", resolve)) === 0) { databaseReady = true; break; }
    await sleep(1000);
  }
  assert.ok(databaseReady, "Isolated Postgres readiness failed");
  const log = createWriteStream(join(temporary, "spring.log"));
  spring = spawn("java", ["-jar", join(root, "backend/build/libs/bytecore-backend.jar")], {
    env: {
      PATH: process.env.PATH, JAVA_HOME: process.env.JAVA_HOME,
      SERVER_ADDRESS: "127.0.0.1", SERVER_PORT: "8063", SPRING_PROFILES_ACTIVE: "dev",
      SPRING_DATASOURCE_URL: "jdbc:postgresql://127.0.0.1:5463/catalog_verification",
      SPRING_DATASOURCE_USERNAME: "catalog_verification", SPRING_DATASOURCE_PASSWORD: password,
      SPRING_KAFKA_LISTENER_AUTO_STARTUP: "false", SPRING_KAFKA_ADMIN_AUTO_CREATE: "false",
      SPRING_KAFKA_BOOTSTRAP_SERVERS: "127.0.0.1:59999",
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: authIssuer.origin,
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: `${authIssuer.origin}/.well-known/jwks.json`,
      CART_GUEST_ALLOWED_ORIGINS: "http://localhost:3164,http://localhost:4002",
      MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  spring.stdout.pipe(log); spring.stderr.pipe(log);
  try { await ready("http://127.0.0.1:8063/actuator/health/readiness"); }
  catch (error) {
    console.error((await readFile(join(temporary, "spring.log"), "utf8")).slice(-10000));
    throw error;
  }
  const source = await readFile(join(root, "infrastructure/hive/supergraph.graphql"), "utf8");
  assert.equal(source.split('url: "http://backend:8080/graphql"').length, 2, "Expected one retail routing URL");
  await writeFile(join(temporary, "supergraph.graphql"),
    source.replace('url: "http://backend:8080/graphql"', 'url: "http://127.0.0.1:8063/graphql"'));
  const config = await readFile(join(root, "infrastructure/hive/router.config.yaml"), "utf8");
  await writeFile(join(temporary, "router.config.yaml"),
    config.replace("host: 0.0.0.0", "host: 127.0.0.1").replace("port: 4000", "port: 4063"));
  docker({}, "--name", `${identity}-hive`, "--network", "host",
    "--volume", `${join(temporary, "router.config.yaml")}:/app/router.config.yaml:ro`,
    "--volume", `${join(temporary, "supergraph.graphql")}:/app/supergraph.graphql:ro`,
    "ghcr.io/graphql-hive/router:0.2.19");
  await ready("http://127.0.0.1:4063/readiness");
  for (const id of containers) {
    assert.equal(execFileSync("docker", ["inspect", "--format", "{{.State.Running}}", id], { encoding: "utf8" }).trim(), "true",
      "An owned fixture container stopped before seeding");
  }
  assert.equal(spring.exitCode, null, "Owned Spring stopped before seeding");
  if (!process.argv.includes("--empty")) await seedFixture(endpoint, await setupToken());
  bridge = createServer((request, response) => {
    handle(request, response).catch((error) => {
      console.error("Verification bridge failed:", error.message);
      if (!response.headersSent) response.writeHead(502);
      response.end("Verification bridge failure");
    });
  });
  await new Promise((resolve, reject) => {
    bridge.once("error", reject);
    bridge.listen(4064, "127.0.0.1", resolve);
  });
  console.log(`READY ${identity}: isolated Postgres :5463, Spring :8063, real Hive :4063, forwarding/control :4064`);
  await new Promise((resolve) => {
    process.once("SIGTERM", resolve); process.once("SIGINT", resolve);
  });
} finally {
  await cleanup();
}
