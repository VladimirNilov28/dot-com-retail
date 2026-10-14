#!/usr/bin/env node
// Live demonstration of OAuth token rotation and revocation against a RUNNING stack.
//
//   node scripts/verify-token-revocation.mjs        # or: bun scripts/verify-token-revocation.mjs
//
// Environment (defaults match the dev stack, infrastructure/compose.yml):
//   HYDRA_PUBLIC_URL  http://127.0.0.1:4444
//   HYDRA_ADMIN_URL   http://127.0.0.1:4445   (loopback-published admin API)
//   SPRING_URL        http://127.0.0.1:8080   (Spring resource server, called directly — not via Hive)
//   OAUTH_CLIENT_ID   bytecore-web            (public PKCE client with refresh_token grant)
//   OAUTH_REDIRECT_URI http://localhost:4200/auth/callback
//   REVOCATION_CACHE_SECONDS 10              (Spring security.token-introspection.cache-ttl; the
//                                             script waits this long before post-revocation Spring checks)
//
// The script mints grants for a disposable synthetic subject by driving Hydra's
// authorization-code + PKCE flow headlessly and accepting the login/consent
// challenges through the Hydra admin API (the same calls the oauth-service bridge
// makes). No Kratos identity or Spring user row is created; every Hydra login and
// consent session for the synthetic subject is deleted at the end.
//
// Output is PASS/FAIL lines with HTTP status codes only. Tokens are never printed.
import { createHash, randomBytes, randomInt } from "node:crypto";

const HYDRA = (process.env.HYDRA_PUBLIC_URL ?? "http://127.0.0.1:4444").replace(/\/$/, "");
const HYDRA_ADMIN = (process.env.HYDRA_ADMIN_URL ?? "http://127.0.0.1:4445").replace(/\/$/, "");
const SPRING = (process.env.SPRING_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
const CLIENT_ID = process.env.OAUTH_CLIENT_ID ?? "bytecore-web";
const REDIRECT_URI = process.env.OAUTH_REDIRECT_URI ?? "http://localhost:4200/auth/callback";
const CACHE_SECONDS = Number(process.env.REVOCATION_CACHE_SECONDS ?? "10");
const SCOPE = "offline_access product:read";
// Spring subjects are numeric user ids; this one is far above any real sequence value.
const SUBJECT = String(9_000_000_000_000 + randomInt(0, 999_999_999));

let failures = 0;
function report(ok, label, detail = "") {
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"} ${label}${detail ? ` (${detail})` : ""}`);
}

async function waitForCache() {
  // Spring may serve a cached active=true for up to cache-ttl after the token's last introspection.
  await new Promise((resolve) => setTimeout(resolve, (CACHE_SECONDS + 1) * 1000));
}

const b64url = (buffer) => buffer.toString("base64url");

async function admin(method, path, body) {
  const response = await fetch(HYDRA_ADMIN + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok && response.status !== 204) throw new Error(`hydra admin ${method} ${path.split("?")[0]} -> ${response.status}`);
  return response.status === 204 ? null : response.json();
}

async function token(form) {
  const response = await fetch(HYDRA + "/oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, ...form }),
  });
  const body = await response.json().catch(() => ({}));
  return { status: response.status, body };
}

async function introspect(accessToken) {
  const response = await fetch(HYDRA_ADMIN + "/admin/oauth2/introspect", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ token: accessToken }),
  });
  const body = await response.json().catch(() => ({}));
  return { status: response.status, active: body.active === true, sub: body.sub };
}

async function revokeRefresh(refreshToken) {
  const response = await fetch(HYDRA + "/oauth2/revoke", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, token: refreshToken, token_type_hint: "refresh_token" }),
  });
  return response.status;
}

async function spring(accessToken) {
  const response = await fetch(SPRING + "/graphql", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify({ query: "query { __typename }" }),
  });
  return response.status;
}

/** Authorization code + PKCE for the synthetic subject; challenges accepted via the admin API. */
async function mintGrant() {
  const cookies = new Map();
  const browser = async (url) => {
    const response = await fetch(url, {
      redirect: "manual",
      headers: { Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join("; ") },
    });
    for (const header of response.headers.getSetCookie()) {
      const [pair] = header.split(";");
      const index = pair.indexOf("=");
      cookies.set(pair.slice(0, index), pair.slice(index + 1));
    }
    const location = response.headers.get("location");
    if (!location) throw new Error(`expected redirect from ${new URL(url).pathname}, got ${response.status}`);
    return new URL(location, url);
  };
  const verifier = b64url(randomBytes(48));
  const authorize = new URL(HYDRA + "/oauth2/auth");
  authorize.search = new URLSearchParams({
    client_id: CLIENT_ID, response_type: "code", redirect_uri: REDIRECT_URI, scope: SCOPE,
    state: b64url(randomBytes(16)), code_challenge_method: "S256",
    code_challenge: b64url(createHash("sha256").update(verifier).digest()),
  }).toString();

  const login = (await browser(authorize.toString())).searchParams.get("login_challenge");
  const acceptedLogin = await admin("PUT",
    `/admin/oauth2/auth/requests/login/accept?login_challenge=${encodeURIComponent(login)}`,
    { subject: SUBJECT, remember: false });
  const consent = (await browser(acceptedLogin.redirect_to)).searchParams.get("consent_challenge");
  const consentRequest = await admin("GET",
    `/admin/oauth2/auth/requests/consent?consent_challenge=${encodeURIComponent(consent)}`);
  const acceptedConsent = await admin("PUT",
    `/admin/oauth2/auth/requests/consent/accept?consent_challenge=${encodeURIComponent(consent)}`,
    {
      grant_scope: consentRequest.requested_scope,
      grant_access_token_audience: consentRequest.requested_access_token_audience ?? [],
      remember: false,
      session: { access_token: { role: "USER", aal: "aal1" } },
    });
  const code = (await browser(acceptedConsent.redirect_to)).searchParams.get("code");
  const grant = await token({ grant_type: "authorization_code", code, redirect_uri: REDIRECT_URI, code_verifier: verifier });
  if (grant.status !== 200 || !grant.body.access_token || !grant.body.refresh_token) {
    throw new Error(`authorization_code exchange -> ${grant.status}`);
  }
  return grant.body;
}

async function cleanup() {
  const subject = encodeURIComponent(SUBJECT);
  await admin("DELETE", `/admin/oauth2/auth/sessions/consent?subject=${subject}&all=true`).catch(() => {});
  await admin("DELETE", `/admin/oauth2/auth/sessions/login?subject=${subject}`).catch(() => {});
  const remaining = await admin("GET", `/admin/oauth2/auth/sessions/consent?subject=${subject}`).catch(() => null);
  report(Array.isArray(remaining) && remaining.length === 0, "cleanup: no Hydra consent sessions remain for the disposable subject");
}

async function main() {
  console.log(`Hydra ${HYDRA} (admin ${HYDRA_ADMIN}), Spring ${SPRING}, disposable subject ${SUBJECT}`);

  // 1. Refresh-token rotation and reuse rejection.
  const first = await mintGrant();
  let check = await introspect(first.access_token);
  report(check.active && check.sub === SUBJECT, "fresh access token introspects active=true with matching sub", `HTTP ${check.status}`);
  let status = await spring(first.access_token);
  report(status === 200, "fresh access token accepted by Spring /graphql directly", `HTTP ${status}`);

  const rotated = await token({ grant_type: "refresh_token", refresh_token: first.refresh_token });
  report(rotated.status === 200, "refresh_token grant succeeds", `HTTP ${rotated.status}`);
  report(Boolean(rotated.body.refresh_token) && rotated.body.refresh_token !== first.refresh_token,
    "refresh rotates: new refresh token differs from the old one");
  check = await introspect(first.access_token);
  report(!check.active, "after refresh the previous access token introspects active=false", `HTTP ${check.status}`);
  check = await introspect(rotated.body.access_token);
  report(check.active, "after refresh the new access token introspects active=true", `HTTP ${check.status}`);
  const reused = await token({ grant_type: "refresh_token", refresh_token: first.refresh_token });
  report(reused.status >= 400 && reused.body.error === "invalid_grant",
    "old (rotated) refresh token is rejected with invalid_grant", `HTTP ${reused.status}`);
  check = await introspect(rotated.body.access_token);
  report(!check.active, "refresh-token reuse detection also revokes the newer generation", `HTTP ${check.status}`);
  await waitForCache();
  status = await spring(first.access_token);
  report(status === 401, "rotated-away access token rejected by Spring", `HTTP ${status}`);

  // 2. Logout path: the BFF revokes its refresh token at Hydra's revocation endpoint.
  const logout = await mintGrant();
  status = await spring(logout.access_token);
  report(status === 200, "logout grant: access token accepted by Spring before logout", `HTTP ${status}`);
  status = await revokeRefresh(logout.refresh_token);
  report(status === 200, "refresh token revoked at /oauth2/revoke", `HTTP ${status}`);
  check = await introspect(logout.access_token);
  report(!check.active, "after refresh revocation the access token introspects active=false", `HTTP ${check.status}`);
  await waitForCache();
  status = await spring(logout.access_token);
  report(status === 401, "after logout revocation the access token is rejected by Spring", `HTTP ${status}`);
  const afterLogout = await token({ grant_type: "refresh_token", refresh_token: logout.refresh_token });
  report(afterLogout.body.error === "invalid_grant", "revoked refresh token cannot be refreshed", `HTTP ${afterLogout.status}`);

  // 3. Role change / account deletion path: HydraClient#revokeUser deletes all consent sessions.
  const roleChange = await mintGrant();
  status = await spring(roleChange.access_token);
  report(status === 200, "subject grant: access token accepted by Spring before revokeUser", `HTTP ${status}`);
  await admin("DELETE", `/admin/oauth2/auth/sessions/consent?subject=${encodeURIComponent(SUBJECT)}&all=true`);
  await admin("DELETE", `/admin/oauth2/auth/sessions/login?subject=${encodeURIComponent(SUBJECT)}`);
  check = await introspect(roleChange.access_token);
  report(!check.active, "after subject consent revocation the access token introspects active=false", `HTTP ${check.status}`);
  await waitForCache();
  status = await spring(roleChange.access_token);
  report(status === 401, "after subject consent revocation the access token is rejected by Spring", `HTTP ${status}`);
  const afterRevoke = await token({ grant_type: "refresh_token", refresh_token: roleChange.refresh_token });
  report(afterRevoke.body.error === "invalid_grant", "subject-revoked refresh token cannot be refreshed", `HTTP ${afterRevoke.status}`);
}

try {
  await main();
} catch (error) {
  report(false, "unexpected error", error instanceof Error ? error.message : "unknown");
} finally {
  await cleanup();
}
console.log(failures === 0 ? "RESULT: PASS" : `RESULT: FAIL (${failures} check(s) failed)`);
process.exit(failures === 0 ? 0 : 1);
