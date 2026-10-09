import { expect, test } from "bun:test";
import { authBoundary, boundedForm } from "./http";
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
