import { expect, test } from "bun:test";
import { assertBrowserNavigation, authBoundary, boundedForm } from "./http";
import { AuthError } from "./model";

const request = (body, headers = {}) => new Request("http://127.0.0.1/auth/submit", {
  method: "POST", body, headers: { "Content-Type": "application/x-www-form-urlencoded", ...headers },
  ...(body instanceof ReadableStream ? { duplex: "half" } : {}),
});

test("auth forms require exact bounded lengths and an allowed encoding", async () => {
  expect((await boundedForm(request("field=value"))).get("field")).toBe("value");
  for (const length of ["no-length", "-1", "65537", "2", "0"]) {
    await expect(boundedForm(request("field=value", { "Content-Length": length }))).rejects.toThrow();
  }
  await expect(boundedForm(request("{}", { "Content-Type": "application/json" }))).rejects.toThrow();
  await expect(boundedForm(request("x".repeat(65537)))).rejects.toThrow();
});

test("auth initiation rejects RSC, prefetch and fetch requests before opening a transaction", () => {
  const url = "http://127.0.0.1:3300/auth/start";
  expect(() => assertBrowserNavigation(new Request(url, {
    headers: { "Sec-Fetch-Dest": "document", "Sec-Fetch-Mode": "navigate" },
  }))).not.toThrow();
  for (const headers of [
    { RSC: "1" }, { "Next-Router-Prefetch": "1" }, { "Next-Router-State-Tree": "private" },
    { "Next-Router-Segment-Prefetch": "/login" }, { Purpose: "prefetch" },
    { "Sec-Purpose": "prefetch;prerender" }, { "Sec-Fetch-Dest": "empty" },
    { "Sec-Fetch-Mode": "cors" },
  ]) {
    expect(() => assertBrowserNavigation(new Request(url, { headers }))).toThrow();
  }
});

test("expired session polling clears only the stale authenticated cookie, preserving the active login jar", async () => {
  const names = ["STOREFRONT_ORIGIN", "AUTH_OAUTH_ISSUER", "AUTH_KRATOS_URL", "AUTH_BRIDGE_URL",
    "AUTH_CLIENT_ID", "AUTH_CLIENT_SECRET", "AUTH_BRIDGE_SECRET", "AUTH_DATABASE_URL", "AUTH_SEAL_KEYS"];
  const previous = Object.fromEntries(names.map((name) => [name, process.env[name]]));
  Object.assign(process.env, {
    STOREFRONT_ORIGIN: "http://127.0.0.1:3300", AUTH_OAUTH_ISSUER: "http://127.0.0.1:24444",
    AUTH_KRATOS_URL: "http://127.0.0.1:24433", AUTH_BRIDGE_URL: "http://127.0.0.1:24446",
    AUTH_CLIENT_ID: "fixture-client", AUTH_CLIENT_SECRET: "fixture-client-secret-not-issued-32",
    AUTH_BRIDGE_SECRET: "fixture-bridge-secret-not-issued-32",
    AUTH_DATABASE_URL: "postgresql://fixture:fixture@127.0.0.1:25432/fixture",
    AUTH_SEAL_KEYS: JSON.stringify({ active: "fixture", keys: { fixture: Buffer.alloc(32, 1).toString("base64url") } }),
  });
  try {
    for (const code of ["expired", "reauthentication_required"]) {
      const response = await authBoundary(async () => { throw new AuthError(code, 401); }, "session",
        new Request("http://127.0.0.1:3300/auth/session", { headers: { Accept: "application/json" } }));
      expect(response.status).toBe(401);
      expect(response.cookies.get("bc_session")?.value).toBe("");
      expect(response.headers.get("set-cookie")).toContain("Max-Age=0");
      expect(response.cookies.get("bc_auth")).toBeUndefined();
    }
    const unavailable = await authBoundary(async () => { throw new AuthError("unavailable"); }, "session");
    expect(unavailable.headers.get("set-cookie")).toBeNull();
  } finally {
    for (const name of names) if (previous[name] === undefined) delete process.env[name]; else process.env[name] = previous[name];
  }
});

test("cancelling a timed-out stream cannot accept the partial form", async () => {
  const body = new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode("field=value")); },
  });
  await expect(boundedForm(request(body))).rejects.toMatchObject({ code: "invalid_request", status: 408 });
}, 10000);

test("native error recovery preserves flow kind and validated bound return destination", async () => {
  const original = process.env.STOREFRONT_ORIGIN;
  process.env.STOREFRONT_ORIGIN = "http://127.0.0.1:3300";
  try {
    const response = await authBoundary(async () => { throw new AuthError("expired", 401); }, "submit",
      new Request("http://127.0.0.1:3300/auth/submit?kind=registration&returnTo=%2Faccount", {
        headers: { Accept: "text/html" },
      }), async () => "/products/actual?variant=2");
    const url = new URL(response.headers.get("location"));
    expect(response.status).toBe(303);
    expect(url.searchParams.get("reason")).toBe("expired");
    expect(url.searchParams.get("kind")).toBe("registration");
    expect(url.searchParams.get("returnTo")).toBe("/products/actual?variant=2");
    const expired = await authBoundary(async () => { throw new AuthError("expired", 401); }, "submit",
      new Request("http://127.0.0.1:3300/auth/submit?kind=verification&returnTo=%2Faccount", {
        headers: { Accept: "text/html" },
      }), async () => { throw new AuthError("expired", 401); });
    expect(new URL(expired.headers.get("location")).searchParams.get("returnTo")).toBe("/account");
    const unsafe = await authBoundary(async () => { throw new AuthError("invalid_request", 400); }, "start",
      new Request("http://127.0.0.1:3300/auth/start?returnTo=https%3A%2F%2Fforeign.invalid", {
        headers: { Accept: "text/html" },
      }));
    expect(new URL(unsafe.headers.get("location")).searchParams.get("returnTo")).toBe("/");
  } finally {
    if (original === undefined) delete process.env.STOREFRONT_ORIGIN;
    else process.env.STOREFRONT_ORIGIN = original;
  }
});
