import { describe, expect, test } from "bun:test";
import { clientAddress } from "./client-address.ts";

const headers = (value) => new Headers(value === undefined ? {} : { "X-Forwarded-For": value });

describe("BFF client address", () => {
  test("uses the entry added by the nearest hop", () => {
    expect(clientAddress(headers("203.0.113.5"), {})).toBe("203.0.113.5");
    expect(clientAddress(headers("6.6.6.6, 203.0.113.5"), {})).toBe("203.0.113.5");
    expect(clientAddress(headers("6.6.6.6, 203.0.113.5, 10.0.0.2"), { BFF_TRUSTED_PROXY_HOPS: "1" })).toBe("203.0.113.5");
    expect(clientAddress(headers("::ffff:127.0.0.1"), {})).toBe("127.0.0.1");
    expect(clientAddress(headers("2001:db8::1"), {})).toBe("2001:db8::1");
  });

  test("rejects missing, malformed or misconfigured values", () => {
    expect(clientAddress(headers(undefined), {})).toBeUndefined();
    expect(clientAddress(headers("example.com"), {})).toBeUndefined();
    expect(clientAddress(headers("203.0.113.5"), { BFF_TRUSTED_PROXY_HOPS: "1" })).toBeUndefined();
    expect(clientAddress(headers("203.0.113.5"), { BFF_TRUSTED_PROXY_HOPS: "-1" })).toBeUndefined();
    expect(clientAddress(headers(Array(25).fill("1.1.1.1").join(",")), {})).toBeUndefined();
  });
});
