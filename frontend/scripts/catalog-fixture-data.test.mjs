import { expect, test } from "bun:test";
import { fixtureSlugs, seedFixture, verifyFixture } from "./catalog-fixture-data.mjs";

test("fixture mutations reject owner and non-loopback endpoints before any request", async () => {
  await expect(seedFixture("http://127.0.0.1:4002/graphql", "test-only-token")).rejects.toThrow("restricted to isolated Hive");
  await expect(seedFixture("https://remote.example:4063/graphql", "test-only-token")).rejects.toThrow();
  await expect(seedFixture("http://127.0.0.1:4063/other", "test-only-token")).rejects.toThrow();
  await expect(seedFixture("http://127.0.0.1:4063/graphql", "")).rejects.toThrow("private setup credential");
});

test("preflight rejects live slugs and wrong counts without weakening the fixture contract", async () => {
  const original = globalThis.fetch;
  let categories = [{ slug: "sulearvutid" }];
  let totalItems = 42;
  globalThis.fetch = async () => Response.json({ data: { categories, searchProducts: { pageInfo: { totalItems } } } });
  try {
    await expect(verifyFixture("http://127.0.0.1:4064/graphql")).rejects.toThrow("Expected isolated English-slug fixtures");
    categories = fixtureSlugs.map((slug) => ({ slug }));
    await expect(verifyFixture("http://127.0.0.1:4064/graphql")).rejects.toThrow("thirty-product recipe");
    totalItems = 30;
    await verifyFixture("http://127.0.0.1:4064/graphql");
  } finally {
    globalThis.fetch = original;
  }
});

test("seeding refuses an existing catalog instead of deleting or replacing data", async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return Response.json({ data: { categories: [{ id: "1" }], searchProducts: { pageInfo: { totalItems: 1 } } } });
  };
  try {
    await expect(seedFixture("http://127.0.0.1:4063/graphql", "test-only-token")).rejects.toThrow("nonempty database");
    expect(calls).toBe(1);
  } finally {
    globalThis.fetch = original;
  }
});
