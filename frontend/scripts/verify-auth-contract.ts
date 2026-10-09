import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { NextRequest } from "next/server";
import axios from "axios";
import { wrapper } from "axios-cookiejar-support";
import { CookieJar } from "tough-cookie";
import { handleAuth } from "../src/lib/auth/handlers";
import { createBrowser, createTransaction, identifier, identifierHash, withBrowser, transactionFor, database,
  closeDatabase } from "../src/lib/auth/store";
import { readFlow } from "../src/lib/auth/flows";
import { authenticatedState, maintainSessions } from "../src/lib/auth/session";
import { exchangeCode, newTransaction } from "../src/lib/auth/oauth";
import { authConfig } from "../src/lib/auth/config";
import { emptyJar } from "../src/lib/auth/provider";
import { AuthError } from "../src/lib/auth/model";
import type { FormKind } from "../src/lib/auth/model";
import type { ProviderFlow } from "../src/lib/auth/flows";

const environment = JSON.parse(readFileSync(process.argv[2], "utf8"));
for (const [key, value] of Object.entries(environment)) {
  assert.equal(typeof value, "string");
  if (typeof value === "string") process.env[key] = value;
}
const origin = process.env.STOREFRONT_ORIGIN!;
const issuer = process.env.AUTH_OAUTH_ISSUER!;
const bridge = process.env.AUTH_BRIDGE_URL!;
assert.equal(origin, "http://127.0.0.1:3300", "This runner may only use the isolated fixture");
const browser = new CookieJar();
const http = wrapper(axios.create({ jar: browser, maxRedirects: 0, validateStatus: () => true }));

async function bff(path: string, form?: URLSearchParams, requestOrigin = origin, jar = browser, accept?: string) {
  const url = new URL(path, origin);
  const request = new NextRequest(url, {
    method: form ? "POST" : "GET",
    headers: { Host: url.host, Cookie: await jar.getCookieString(url.href), ...(accept ? { Accept: accept } : {}),
      ...(form ? { Origin: requestOrigin, "Content-Type": "application/x-www-form-urlencoded" } : {}) },
    ...(form ? { body: form.toString() } : {}),
  });
  const response = await handleAuth(request, url.pathname.split("/").at(-1)!);
  for (const cookie of response.headers.getSetCookie()) await jar.setCookie(cookie, url.href);
  assert.match(response.headers.get("cache-control") ?? "", /no-store/);
  return response;
}

async function cookie(name: string) {
  const values = (await browser.getCookies(origin)).filter((item) => item.key === name);
  assert.equal(values.length, 1);
  return values[0].value;
}

function nodeValue(flow: ProviderFlow, name: string): string {
  const node = flow.nodes.find((item) => {
    const attributes = item.attributes;
    return attributes && typeof attributes === "object" && "name" in attributes && attributes.name === name;
  });
  assert.ok(node);
  const attributes = node.attributes;
  assert.ok(attributes && typeof attributes === "object" && "value" in attributes && typeof attributes.value === "string");
  return attributes.value;
}

async function formFor(id: string, kind: FormKind) {
  const sid = await cookie("bc_auth");
  return withBrowser(sid, async (state, client) => {
    const flow = await readFlow(state, sid, id, kind, client);
    return new URLSearchParams({
      flow: id, _csrf: state.csrf, csrf_token: nodeValue(flow, "csrf_token"),
      method: kind === "verification" ? "code" : "password",
    });
  });
}

function location(response: Response) {
  assert.equal(response.status, 303, "The real BFF operation must return a redirect");
  const value = response.headers.get("location");
  assert.ok(value);
  return new URL(value);
}

async function mailCode(email: string, previous?: string): Promise<string> {
  for (let attempt = 0; attempt < 30; attempt++) {
    const inbox = await (await fetch("http://127.0.0.1:28025/api/v1/messages")).json();
    const message = inbox.messages.find((item: { To: { Address: string }[] }) => item.To.some((to) => to.Address === email));
    if (message) {
      const content = await (await fetch(`http://127.0.0.1:28025/api/v1/message/${message.ID}`)).json();
      const code = content.Text.match(/\b[0-9]{6}\b/);
      assert.ok(code, "The genuine courier message must contain a verification code");
      if (code[0] !== previous) return code[0];
    }

    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("Verification mail was not delivered");
}

function captchaRows(query: string, parameters: string[]) {
  return JSON.parse(execFileSync("docker", ["exec", "-i", "-w", "/app/src",
    "bytecore-auth-eb7151-oauth-service-1", "python", "-c",
    "import sys,json,psycopg;from config import load_config;"
      + "q,p=json.load(sys.stdin);"
      + "c=psycopg.connect(load_config().auth_database_url);"
      + "r=c.execute(q,p);v=r.fetchall() if r.description else [];c.commit();"
      + "sys.stdout.write(json.dumps(v));c.close()"],
  { input: JSON.stringify([query, parameters]), encoding: "utf8" }));
}

async function registrationHook(body: object, authenticated = true, trigger = randomUUID()) {
  return fetch(`${bridge}/internal/registration/pre-persist`, {
    method: "POST", headers: { "Content-Type": "application/json", "Ory-Webhook-Trigger-ID": trigger,
      ...(authenticated ? { Authorization: `Bearer ${process.env.AUTH_BRIDGE_SECRET!}` } : {}) },
    body: JSON.stringify(body), signal: AbortSignal.timeout(8000),
  });
}

function providerConfidentiality(credentials: string[]) {
  const config = authConfig();
  const secrets = [...credentials, config.clientSecret, config.bridgeSecret,
    Buffer.from(`${config.clientId}:${config.clientSecret}`).toString("base64"),
    ...Array.from(config.keys.values(), (key) => Buffer.from(key).toString("base64url"))];
  for (const service of ["kratos", "hydra", "oauth-service", "backend", "hydra-public"]) {
    const result = spawnSync("docker", ["logs", `bytecore-auth-eb7151-${service}-1`], {
      encoding: "utf8", maxBuffer: 128 * 1024 * 1024,
    });
    assert.equal(result.error, undefined, "Provider log audit must complete");
    assert.equal(result.status, 0, "Provider log audit must complete");
    const logs = result.stdout + result.stderr;
    assert.equal(secrets.some((secret) => secret && logs.includes(secret)), false,
      "Provider credential exposure; values suppressed");
    assert.equal(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}/.test(logs), false,
      "Provider JWT exposure; values suppressed");
  }
  console.info("PASS actual provider stdout/stderr contains no tested passwords, session IDs, tokens, client/bridge secrets or sealing keys");
}

async function authorizationResponse(hop: URL, denyConsent = false, credentials?: { email: string; password: string }) {
  for (let count = 0; count < 12; count++) {
    if (hop.origin === bridge) {
      assert.ok(["/login", "/consent"].includes(hop.pathname));
      const response = await http.get(hop.href);
      assert.equal(response.status, 302);
      hop = new URL(response.headers.location);
    } else if (hop.origin === origin) {
      if (hop.pathname === "/login") {
        assert.ok(credentials, "The actual provider requested a fresh password login");
        const form = await formFor(hop.searchParams.get("flow")!, "login");
        form.set("identifier", credentials.email);
        form.set("password", credentials.password);
        hop = location(await bff("/auth/submit?kind=login", form));
      } else if (denyConsent && hop.pathname === "/auth/consent") {
        const rejection = await fetch(`http://127.0.0.1:24445/admin/oauth2/auth/requests/consent/reject${hop.search}`, {
          method: "PUT", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ error: "access_denied" }),
        });
        assert.equal(rejection.status, 200);
        hop = new URL((await rejection.json()).redirect_to);
      } else {
        hop = location(await bff(hop.pathname + hop.search));
      }
    } else {
      assert.equal(hop.origin, issuer);
      const upstream = await http.get(hop.href);
      if (upstream.status === 200 && typeof upstream.data === "string" && upstream.data.includes("<form")) {
        assert.match(upstream.data, /<noscript>[\s\S]*Continue to ByteCore/);
        assert.match(upstream.data, /document\.forms\[0\]\.submit\(\)/);
        const action = upstream.data.match(/<form[^>]*action="([^"]+)"/);
        assert.ok(action);
        assert.equal(action[1], `${origin}/auth/callback`);
        const form = new URLSearchParams();
        for (const match of upstream.data.matchAll(/<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"/g)) {
          form.append(match[1], match[2]);
        }
        assert.equal(form.getAll("state").length, 1);
        assert.equal(form.getAll(denyConsent ? "error" : "code").length, 1);
        assert.equal(form.has("access_token") || form.has("refresh_token") || form.has("id_token"), false);
        return form;
      }
      assert.equal(upstream.status, 302);
      hop = new URL(upstream.headers.location);
    }
  }
  throw new Error("The actual Hydra authorization response did not complete");
}

async function existingAuthorization(credentials?: { email: string; password: string }, denyConsent = false) {
  const authorize = location(await bff("/auth/start?returnTo=%2Faccount"));
  const preauth = await cookie("bc_auth");
  const state = authorize.searchParams.get("state")!;
  return { form: await authorizationResponse(authorize, denyConsent, credentials), preauth, state,
    transaction: await transactionFor(preauth, state) };
}

async function processProbe(session: string): Promise<{ id: string; fingerprint: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn("bun", ["--conditions=react-server", "scripts/auth-session-probe.ts"],
      { stdio: ["pipe", "pipe", "pipe"] });
    let output = "";
    child.stdout.on("data", (chunk) => { output += chunk.toString(); });
    child.stderr.resume();
    child.on("error", () => reject(new Error("Isolated refresh probe could not start")));
    child.on("close", (code) => {
      if (code !== 0) { reject(new Error("Isolated refresh probe failed; credentials suppressed")); return; }
      try { resolve(JSON.parse(output)); } catch { reject(new Error("Invalid isolated probe result")); }
    });
    child.stdin.end(JSON.stringify({ environment, session }));
  });
}

async function pauseFixture(service: "hydra-public" | "kratos" | "oauth-service", callback: () => Promise<void>) {
  const container = `bytecore-auth-eb7151-${service}-1`;
  assert.equal(execFileSync("docker", ["inspect", "--format",
    '{{index .Config.Labels "com.docker.compose.project"}}', container], { encoding: "utf8" }).trim(), "bytecore-auth-eb7151");
  execFileSync("docker", ["pause", container], { stdio: "ignore" });
  try { await callback(); } finally { execFileSync("docker", ["unpause", container], { stdio: "ignore" }); }
}

async function callbackRejections(credentials: { email: string; password: string }, session: string) {
  const pending = await existingAuthorization(credentials);
  const other = await createBrowser({ jar: emptyJar(), csrf: identifier(), returnTo: "/account" });
  const foreign = new CookieJar();
  await foreign.setCookie(`bc_auth=${other.id}; Path=/; HttpOnly`, origin);
  assert.equal((await bff("/auth/callback", pending.form, issuer, foreign)).status, 400);
  assert.equal((await (await bff("/auth/session", undefined, origin, foreign)).json()).kind, "anonymous");
  const missing = new URLSearchParams(pending.form);
  missing.delete("state");
  assert.equal((await bff("/auth/callback", missing, issuer)).status, 400);
  const invalid = new URLSearchParams(pending.form);
  invalid.set("state", identifier());
  assert.equal((await bff("/auth/callback", invalid, issuer)).status, 400);
  await database().query("UPDATE oauth_transactions SET expires_at=now()-interval '1 second' WHERE state_hash=$1",
    [identifierHash(pending.state)]);
  assert.equal((await bff("/auth/callback", pending.form, issuer)).status, 400);
  assert.equal(await cookie("bc_session"), session);
  const denied = await existingAuthorization(credentials, true);
  assert.equal(denied.form.get("error"), "access_denied");
  assert.equal((await bff("/auth/callback", denied.form, issuer)).status, 403);
  assert.equal((await bff("/auth/callback", denied.form, issuer)).status, 400);
  for (const field of ["nonce", "verifier"] as const) {
    const mismatch = await existingAuthorization(credentials);
    await database().query("DELETE FROM oauth_transactions WHERE state_hash=$1", [identifierHash(mismatch.state)]);
    await createTransaction(mismatch.preauth, { ...mismatch.transaction, [field]: identifier() });
    assert.equal((await bff("/auth/callback", mismatch.form, issuer)).status, 400);
    assert.equal(await cookie("bc_session"), session);
  }
  console.info("PASS real Hydra denial, missing/invalid/expired/foreign/replayed state and actual nonce/PKCE mismatch rejection");

  const destination = "/products/contract-product?variant=42";
  const route = location(await bff(`/auth/flow?kind=verification&returnTo=${encodeURIComponent(destination)}`));
  const flowId = route.searchParams.get("flow")!;
  const form = await formFor(flowId, "verification");
  execFileSync("docker", ["exec", "bytecore-auth-eb7151-postgres-1", "psql", "-U", "auth_fixture", "-d", "kratos",
    "-v", "ON_ERROR_STOP=1", "-c",
    `UPDATE selfservice_verification_flows SET expires_at=now()-interval '1 minute' WHERE id='${flowId}';`],
  { stdio: "ignore" });
  const expired = location(await bff(`/auth/submit?kind=verification&returnTo=${encodeURIComponent(destination)}`,
    form, origin, browser, "text/html"));
  assert.equal(expired.pathname, "/auth/error");
  assert.equal(expired.searchParams.get("reason"), "expired");
  assert.equal(expired.searchParams.get("kind"), "verification");
  assert.equal(expired.searchParams.get("returnTo"), destination);
  assert.equal((await bff("/auth/captcha?flow=not-a-uuid")).status, 400);
  console.info("PASS actual provider-flow expiration preserves the correct recovery kind and shared product destination");

  await pauseFixture("kratos", async () => {
    await assert.rejects(() => authenticatedState(session),
      (error) => error instanceof AuthError && error.code === "unavailable");
    await withBrowser(session, async (_state, _client, row) => { assert.equal(row.revoked_at, null); });
  });
  await authenticatedState(session);
  console.info("PASS provider outage fails protected access explicitly without presenting a cached authenticated fallback");
}

async function expiredAndAmbiguousSessions(credentials: { email: string; password: string }) {
  const live = await existingAuthorization(credentials);
  location(await bff("/auth/callback", live.form, issuer));
  const liveSession = await cookie("bc_session");
  const liveAccount = await authenticatedState(liveSession);
  assert.ok(liveAccount.state.provider);
  assert.equal((await fetch(`http://127.0.0.1:24434/admin/sessions/${liveAccount.state.provider.sessionId}`, {
    method: "DELETE",
  })).status, 204);
  await assert.rejects(() => authenticatedState(liveSession),
    (error) => error instanceof AuthError && error.code === "expired");
  await maintainSessions();
  console.info("PASS actual Kratos session revocation immediately rejects the still-valid BFF grant");
  for (const column of ["idle_until", "expires_at"]) {
    const authorization = await existingAuthorization(credentials);
    location(await bff("/auth/callback", authorization.form, issuer));
    const session = await cookie("bc_session");
    await database().query(`UPDATE browser_sessions SET ${column}=now()-interval '1 second' WHERE id_hash=$1`,
      [identifierHash(session)]);
    await assert.rejects(() => authenticatedState(session),
      (error) => error instanceof AuthError && error.code === "expired");
    await maintainSessions();
  }
  const fresh = await existingAuthorization(credentials);
  location(await bff("/auth/callback", fresh.form, issuer));
  const session = await cookie("bc_session");
  await withBrowser(session, async (state) => {
    assert.ok(state.tokens);
    state.tokens.expiresAt = Date.now() - 1;
  });
  await pauseFixture("hydra-public", async () => {
    await assert.rejects(() => authenticatedState(session),
      (error) => error instanceof AuthError && error.code === "reauthentication_required");
  });
  await assert.rejects(() => authenticatedState(session),
    (error) => error instanceof AuthError && error.code === "expired");
  await maintainSessions();
  console.info("PASS absolute/idle expiration and ambiguous real refresh permanently invalidate the BFF session");

  const renewed = await existingAuthorization(credentials);
  location(await bff("/auth/callback", renewed.form, issuer));
  const sid = await cookie("bc_session");
  const account = await authenticatedState(sid);
  await pauseFixture("oauth-service", async () => {
    const response = await bff("/auth/logout", new URLSearchParams({ _csrf: account.state.csrf }));
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: "provider_logout_pending", signedOut: true });
    assert.equal((await browser.getCookies(origin)).some((item) => item.key === "bc_session"), false);
    await assert.rejects(() => authenticatedState(sid),
      (error) => error instanceof AuthError && error.code === "expired");
  });
  const maintenance = await maintainSessions();
  assert.equal(maintenance.pending, 0);
  console.info("PASS real logout outage clears local access, reports pending provider revocation and completes its durable retry");
}

async function storeContracts() {
  const state = { jar: emptyJar(), csrf: identifier(), returnTo: "/account" };
  const original = await createBrowser(state);
  const swapped = await createBrowser(state);
  const oldKey = await createBrowser(state);
  const cipher = (await database().query<{ sealed: string }>(
    "SELECT sealed FROM browser_sessions WHERE id_hash=$1", [identifierHash(original.id)])).rows[0].sealed;
  assert.ok(!cipher.includes(state.csrf) && !cipher.includes(state.jar));
  await database().query("UPDATE browser_sessions SET sealed=$2 WHERE id_hash=$1", [identifierHash(swapped.id), cipher]);
  await assert.rejects(() => withBrowser(swapped.id, async () => undefined));
  const transaction = newTransaction("/account");
  await createTransaction(original.id, transaction);
  await database().query("UPDATE oauth_transactions SET sealed=$2 WHERE state_hash=$1",
    [identifierHash(transaction.state), cipher]);
  await assert.rejects(() => transactionFor(original.id, transaction.state));
  const configured = process.env.AUTH_SEAL_KEYS!;
  const keys = Object.fromEntries(Array.from(authConfig().keys, ([id, key]) => [id, Buffer.from(key).toString("base64url")]));
  keys.rotation = randomBytes(32).toString("base64url");
  try {
    process.env.AUTH_SEAL_KEYS = JSON.stringify({ active: "rotation", keys });
    await withBrowser(original.id, async (context) => { assert.equal(context.csrf, state.csrf); });
    const rotated = (await database().query<{ sealed: string }>(
      "SELECT sealed FROM browser_sessions WHERE id_hash=$1", [identifierHash(original.id)])).rows[0].sealed;
    assert.equal(JSON.parse(Buffer.from(rotated.split(".")[0], "base64url").toString()).kid, "rotation");
    process.env.AUTH_SEAL_KEYS = JSON.stringify({ active: "rotation", keys: { rotation: keys.rotation } });
    await assert.rejects(() => withBrowser(oldKey.id, async () => undefined));
    await withBrowser(original.id, async () => undefined);
  } finally {
    process.env.AUTH_SEAL_KEYS = configured;
    await database().query("DELETE FROM browser_sessions WHERE id_hash=ANY($1)",
      [[original.id, swapped.id, oldKey.id].map(identifierHash)]);
  }
  console.info("PASS actual encrypted row/purpose binding and overlapping key rotation");
}

async function main() {
  await storeContracts();
  const suffix = randomBytes(6).toString("hex");
  const email = `contract-${suffix}@bytecore.example`;
  const password = `Fixture!${randomBytes(24).toString("base64url")}9`;
  const registration = location(await bff("/auth/flow?kind=registration&returnTo=%2Fcatalog%2Fall-products"));
  const registrationId = registration.searchParams.get("flow")!;
  const challengeResponse = await bff(`/auth/captcha?flow=${registrationId}`);
  assert.equal(challengeResponse.status, 200);
  const challenge = await challengeResponse.json();
  const proof = execFileSync(process.argv[3], ["-c",
    "import sys,json;from altcha import Challenge,Payload,solve_challenge;"
      + "c=Challenge.from_dict(json.load(sys.stdin));s=solve_challenge(c);"
      + "assert s is not None;sys.stdout.write(Payload(c,s).to_base64())"],
  { input: JSON.stringify(challenge), encoding: "utf8" });
  const registrationForm = await formFor(registrationId, "registration");
  registrationForm.set("traits.email", email);
  registrationForm.set("traits.username", `contract${suffix}`);
  registrationForm.set("traits.dateOfBirth", "1990-06-01");
  registrationForm.set("password", password);
  registrationForm.set("altcha", proof);
  const expiresAt = await withBrowser(await cookie("bc_auth"), async (context, client) =>
    (await readFlow(context, await cookie("bc_auth"), registrationId, "registration", client)).expiresAt);
  const webhook = { flow_id: registrationId, flow_type: "browser", schema_id: "customer-v1",
    expires_at: expiresAt, traits: { email, username: `contract${suffix}`, dateOfBirth: "1990-06-01" }, altcha: proof };
  assert.equal((await registrationHook(webhook, false)).status, 403);
  for (const altcha of [null, "malformed-proof"]) {
    assert.equal((await registrationHook({ ...webhook, altcha })).status, 400);
  }
  assert.equal((await registrationHook({ ...webhook, schema_id: "default" })).status, 400);
  const nativeFlowResponse = await fetch("http://127.0.0.1:24433/self-service/registration/api");
  assert.equal(nativeFlowResponse.status, 200);
  const nativeFlow = await nativeFlowResponse.json();
  assert.equal((await registrationHook({ ...webhook, flow_id: nativeFlow.id })).status, 400);
  const nativeAction = new URL(nativeFlow.ui.action);
  assert.equal(nativeAction.origin, "http://127.0.0.1:24433");
  assert.equal(nativeAction.pathname, "/self-service/registration");
  assert.equal((await fetch(nativeAction, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ method: "password", password, traits: webhook.traits, transient_payload: { altcha: proof } }),
  })).status, 400, "Actual native registration must not bypass the browser-only webhook");
  const nonce = challenge.parameters.nonce;
  captchaRows("UPDATE captcha_challenges SET expires_at=now()-interval '1 second' WHERE nonce=%s", [nonce]);
  assert.equal((await registrationHook(webhook)).status, 400);
  captchaRows("UPDATE captcha_challenges SET expires_at=now()+interval '5 minutes' WHERE nonce=%s", [nonce]);
  execFileSync("docker", ["pause", "bytecore-auth-eb7151-postgres-1"], { stdio: "ignore" });
  try {
    assert.equal((await registrationHook(webhook)).status, 502);
  } finally {
    execFileSync("docker", ["unpause", "bytecore-auth-eb7151-postgres-1"], { stdio: "ignore" });
  }
  assert.equal((await bff("/auth/submit?kind=registration", registrationForm, "https://foreign.invalid")).status, 403);
  const verification = location(await bff("/auth/submit?kind=registration", registrationForm));
  assert.equal(verification.pathname, "/verify");
  const verificationId = verification.searchParams.get("flow")!;
  assert.ok(verificationId);
  assert.equal((await registrationHook(webhook)).status, 400, "A different delivery cannot reuse a consumed proof");
  const used = captchaRows("SELECT used_trigger::text FROM captcha_challenges WHERE nonce=%s", [nonce]);
  assert.equal((await registrationHook(webhook, true, used[0][0])).status, 200,
    "The matching genuine authenticated webhook delivery can retry idempotently");
  console.info("PASS real CAPTCHA, pre-persistence reservation, identity binding and BFF CSRF");
  console.info("PASS actual malformed/missing/expired/foreign/reused CAPTCHA, authenticated retry, database outage and native/legacy bypass rejection");
  const preauth = await cookie("bc_auth");
  await withBrowser(preauth, async (state, _client, row) => {
    assert.equal(row.authenticated, false);
    assert.equal(state.tokens, undefined);
  });
  const unverified = await fetch("http://127.0.0.1:24447/internal/token", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email, password }),
  });
  assert.equal(unverified.ok, false, "An unverified real identity must not receive OAuth access");
  assert.equal((await unverified.json()).access_token, undefined);
  const previousCode = await mailCode(email);
  const resend = await formFor(verificationId, "verification");
  resend.set("email", email);
  const resent = location(await bff("/auth/submit?kind=verification", resend));
  assert.equal(resent.pathname, "/verify");
  assert.equal(resent.searchParams.has("validation"), false);
  const verificationForm = await formFor(verificationId, "verification");
  verificationForm.set("code", await mailCode(email, previousCode));
  const restart = location(await bff("/auth/submit?kind=verification", verificationForm));
  assert.equal(restart.pathname, "/auth/start");
  console.info("PASS actual unverified OAuth rejection and genuine verification-code resend before login");
  const authorize = location(await bff(restart.pathname + restart.search));
  assert.equal(authorize.origin, issuer);
  assert.equal(authorize.searchParams.get("response_mode"), "form_post");
  assert.equal(authorize.searchParams.get("code_challenge_method"), "S256");
  const state = authorize.searchParams.get("state")!;
  const hydra = await http.get(authorize.href);
  assert.equal(hydra.status, 302);
  let challengeUrl = new URL(hydra.headers.location);
  if (challengeUrl.origin === bridge) {
    const dispatched = await http.get(challengeUrl.href);
    assert.equal(dispatched.status, 302);
    challengeUrl = new URL(dispatched.headers.location);
  }
  const login = location(await bff(challengeUrl.pathname + challengeUrl.search));
  assert.equal(login.pathname, "/login");
  const loginId = login.searchParams.get("flow")!;
  const credentials = await formFor(loginId, "login");
  credentials.set("identifier", email);
  credentials.set("password", "ThisIsNotTheAccountPassword!19");
  const incorrect = location(await bff("/auth/submit?kind=login", credentials));
  assert.equal(incorrect.searchParams.get("validation"), "1");
  credentials.set("password", password);
  let callbackForm = await authorizationResponse(location(await bff("/auth/submit?kind=login", credentials)));
  const sid = await cookie("bc_auth");
  let transaction = await transactionFor(sid, state);
  assert.equal((await bff("/auth/callback", callbackForm, "https://foreign.invalid")).status, 403);
  const done = location(await bff("/auth/callback", callbackForm, "null"));
  assert.equal(done.pathname, "/catalog/all-products");
  assert.equal((await browser.getCookies(origin)).some((item) => item.key === "bc_auth"), false);
  let session = await cookie("bc_session");
  assert.ok(session !== sid, "Authentication must rotate the opaque browser identifier");
  const account = await authenticatedState(session);
  assert.equal(account.user.email, email);
  assert.equal(account.user.role, "USER");
  console.info("PASS genuine Mailpit verification, incorrect/correct login, OIDC callback and canonical caller-owned Hive me");
  const replay = await bff("/auth/callback", callbackForm, issuer);
  assert.ok(replay.status === 400 || replay.status === 401);
  assert.equal((await bff("/auth/start?returnTo=https%3A%2F%2Fforeign.invalid")).status, 400);
  const previousSession = session;
  const renewed = await existingAuthorization();
  await withBrowser(renewed.preauth, async (context) => {
    assert.equal(context.previousSessionHash, identifierHash(previousSession));
  });
  location(await bff("/auth/callback", renewed.form, issuer));
  callbackForm = renewed.form;
  transaction = renewed.transaction;
  session = await cookie("bc_session");
  assert.notEqual(session, previousSession);
  await assert.rejects(() => authenticatedState(previousSession),
    (error) => error instanceof AuthError && error.code === "expired");
  await withBrowser(previousSession, async (context) => { assert.equal(context.revokeProvider, false); }, true);
  await maintainSessions();
  assert.equal((await authenticatedState(session)).user.id, account.user.id);
  console.info("PASS reauthentication rotates the SID, retires the prior grant and preserves the reused provider session");
  await callbackRejections({ email, password }, session);
  const currentAccount = await authenticatedState(session);
  await new Promise((resolve) => setTimeout(resolve, 17000));
  const processes = await Promise.all(Array.from({ length: 3 }, () => processProbe(session)));
  assert.equal(new Set(processes.map((item) => item.fingerprint)).size, 1);
  assert.ok(processes.every((item) => item.id === account.user.id));
  const refreshed = await Promise.all(Array.from({ length: 4 }, () => authenticatedState(session)));
  assert.equal(new Set(refreshed.map((item) => item.state.tokens?.accessToken)).size, 1);
  assert.ok(refreshed[0].state.tokens?.accessToken !== currentAccount.state.tokens?.accessToken, "The expired access grant must refresh");
  assert.equal(createHash("sha256").update(refreshed[0].state.tokens!.accessToken).digest("hex"), processes[0].fingerprint);
  console.info("PASS callback replay rejection, safe-return rejection and serialized real refresh across independent processes");
  await assert.rejects(() => exchangeCode(new Request(`${origin}/auth/callback`, {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: callbackForm!.toString(),
  }), transaction), (error) => error instanceof AuthError && error.code === "invalid_request");
  await new Promise((resolve) => setTimeout(resolve, 17000));
  await assert.rejects(() => authenticatedState(session),
    (error) => error instanceof AuthError && error.code === "reauthentication_required");
  console.info("PASS real authorization-code reuse rejection and its paired refresh-grant revocation");
  const logout = new URLSearchParams({ _csrf: refreshed[0].state.csrf });
  assert.equal((await bff("/auth/logout", logout, "https://foreign.invalid")).status, 403);
  location(await bff("/auth/logout", logout));
  await assert.rejects(() => authenticatedState(session), (error) => error instanceof AuthError && error.code === "expired");
  assert.equal((await browser.getCookies(origin)).some((item) => item.key === "bc_session"), false);
  console.info("PASS immediate durable logout, cookie clearing and session rejection");
  await expiredAndAmbiguousSessions({ email, password });
  providerConfidentiality([password, session, previousSession,
    account.state.tokens!.accessToken, account.state.tokens!.refreshToken,
    refreshed[0].state.tokens!.accessToken, refreshed[0].state.tokens!.refreshToken]);
}

try {
  await main();
} catch (error) {
  console.error("Authentication contract verification failed:", error instanceof Error ? error.name : "unknown");
  if (error instanceof AuthError) console.error("Safe authentication error code:", error.code);
  if (error instanceof Error && error.name === "AssertionError") {
    console.error("Contract assertion failed; provider responses, cookie values and credentials suppressed.");
  }
  process.exitCode = 1;
} finally {
  await closeDatabase();
}
