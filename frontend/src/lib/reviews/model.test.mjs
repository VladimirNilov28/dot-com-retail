import { expect, test } from "bun:test";
import { characterCount, distribution, reviewSort, validateReason, validateReview } from "./model";

const valid = { stars: 4, title: "  Solid laptop  ", body: "Works well for daily development.\r\nBattery is fine." };

test("valid review is trimmed and newline-normalised", () => {
  const result = validateReview(valid);
  expect(result.ok).toBe(true);
  expect(result.value).toEqual({ stars: 4, title: "Solid laptop", body: "Works well for daily development.\nBattery is fine." });
});

test.each([
  [{ ...valid, stars: 0 }, "stars"], [{ ...valid, stars: 6 }, "stars"], [{ ...valid, stars: 2.5 }, "stars"],
  [{ ...valid, title: "   " }, "title"], [{ ...valid, title: "x".repeat(121) }, "title"], [{ ...valid, title: "a\nb" }, "title"],
  [{ ...valid, title: "<b>bold</b>" }, "title"], [{ ...valid, body: "too short" }, "body"],
  [{ ...valid, body: "y".repeat(4001) }, "body"], [{ ...valid, body: "Nice <script>alert(1)</script> laptop" }, "body"],
  [{ ...valid, body: "Contains a \u0007 bell char" }, "body"],
])("rejects invalid input %#", (input, field) => {
  const result = validateReview(input);
  expect(result.ok).toBe(false);
  expect(Object.keys(result.errors)).toEqual([field]);
});

test("limits are measured in code points and allow plain comparison operators", () => {
  expect(characterCount("😀😀")).toBe(2);
  expect(validateReview({ ...valid, title: "😀".repeat(120) }).ok).toBe(true);
  expect(validateReview({ ...valid, body: "Price < 1000 and speed > 3 GHz" }).ok).toBe(true);
});

test("moderation reason is bounded plain text", () => {
  expect(validateReason("  Spam link  ")).toEqual({ ok: true, value: "Spam link" });
  expect(validateReason("no").ok).toBe(false);
  expect(validateReason("<a href=x>").ok).toBe(false);
  expect(validateReason("z".repeat(501)).ok).toBe(false);
});

test("distribution always has five buckets from 5 to 1 stars", () => {
  expect(distribution([{ stars: 5, count: 3 }, { stars: 1, count: 1 }])).toEqual([
    { stars: 5, count: 3, percent: 75 }, { stars: 4, count: 0, percent: 0 }, { stars: 3, count: 0, percent: 0 },
    { stars: 2, count: 0, percent: 0 }, { stars: 1, count: 1, percent: 25 },
  ]);
  expect(distribution([]).every((bucket) => bucket.percent === 0)).toBe(true);
});

test("unknown sort falls back to newest", () => {
  expect(reviewSort("HELPFUL")).toBe("HELPFUL");
  expect(reviewSort("DROP TABLE")).toBe("NEWEST");
});
