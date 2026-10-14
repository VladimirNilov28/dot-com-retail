import { expect, test } from "bun:test";
import { seedProductFixture } from "./product-fixture-data.mjs";
import { execFileSync } from "node:child_process";

test("product setup rejects owner or remote mutation targets before network access", async () => {
  for (const endpoint of ["http://localhost:4002/graphql", "http://remote.invalid:4063/graphql",
    "http://127.0.0.1:4063/other"]) {
    await expect(seedProductFixture(endpoint, "test-only")).rejects.toThrow();
  }
});
test("product verification entrypoints parse without executing resources", () => {
  for (const script of ["product-fixture-data.mjs", "product-smoke.mjs"]) {
    expect(() => execFileSync("node", ["--check", new URL(script, import.meta.url).pathname])).not.toThrow();
  }
});
