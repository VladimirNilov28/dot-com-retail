import { describe, expect, test } from "bun:test";
import { TokenBuckets } from "./token-bucket.ts";

function clock() {
  let now = 0;
  return { now: () => now, advance: (ms) => { now += ms; } };
}

describe("TokenBuckets", () => {
  test("allows a burst up to capacity, then refills over time", () => {
    const time = clock();
    const buckets = new TokenBuckets({ capacity: 3, refillPerSecond: 0.5 }, 100, time.now);
    expect([1, 2, 3].map(() => buckets.consume("a").allowed)).toEqual([true, true, true]);
    expect(buckets.consume("a")).toEqual({ allowed: false, retryAfterSeconds: 2 });
    time.advance(1_000);
    expect(buckets.consume("a")).toEqual({ allowed: false, retryAfterSeconds: 1 });
    time.advance(1_000);
    expect(buckets.consume("a").allowed).toBe(true);
    time.advance(60_000);
    expect([1, 2, 3, 4].map(() => buckets.consume("a").allowed)).toEqual([true, true, true, false]);
  });

  test("isolates keys", () => {
    const buckets = new TokenBuckets({ capacity: 1, refillPerSecond: 0.01 }, 100, clock().now);
    expect(buckets.consume("a").allowed).toBe(true);
    expect(buckets.consume("a").allowed).toBe(false);
    expect(buckets.consume("b").allowed).toBe(true);
  });

  test("bounds memory by evicting least recently used keys", () => {
    const buckets = new TokenBuckets({ capacity: 1, refillPerSecond: 0.001 }, 2, clock().now);
    for (const key of ["a", "b", "c"]) buckets.consume(key);
    expect(buckets.size).toBe(2);
    expect(buckets.consume("a").allowed).toBe(true);
    expect(buckets.consume("c").allowed).toBe(false);
  });

  test("rejects invalid policies", () => {
    expect(() => new TokenBuckets({ capacity: 0, refillPerSecond: 1 })).toThrow(TypeError);
    expect(() => new TokenBuckets({ capacity: 1, refillPerSecond: 0 })).toThrow(TypeError);
  });
});
