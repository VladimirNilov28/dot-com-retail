import { expect, mock, test } from "bun:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

mock.module("@/lib/reviews/actions", () => ({
  loadReviews: async () => ({}), saveReview: async () => ({}), removeReview: async () => ({}), setHelpful: async () => ({}),
}));
const { ProductReviews } = await import("./product-reviews");

const review = (overrides = {}) => ({ id: "7", stars: 4, title: "Good <b>keyboard</b>", body: "Line one\nLine two", authorName: "alice",
  helpfulCount: 3, verifiedPurchase: false, viewerHasVoted: false, viewerIsAuthor: false, createdAt: "2025-01-02T00:00:00Z", updatedAt: "2025-01-02T00:00:00Z", ...overrides });
const page = (items, totalItems = items.length) => ({ items, pageInfo: { page: 0, size: 5, totalItems, totalPages: Math.ceil(totalItems / 5) },
  averageRating: items.length ? 4 : null, ratingCount: items.length, distribution: items.length ? [{ stars: 4, count: items.length }] : [] });
const html = (initial) => renderToStaticMarkup(createElement(ProductReviews, { productId: "1", slug: "kb", initial }));

test("anonymous readers see reviews, escaped text and a sign-in prompt but no vote button", () => {
  const out = html({ status: "ok", viewer: "anonymous", sort: "NEWEST", page: page([review()]), mine: null });
  expect(out).toContain("Customer reviews");
  expect(out).toContain("Good &lt;b&gt;keyboard&lt;/b&gt;");
  expect(out).toContain('href="/login?returnTo=%2Fproducts%2Fkb%23reviews"');
  expect(out).toContain("3 people found this helpful");
  expect(out).not.toContain("aria-pressed");
  expect(out).not.toContain("<form");
  expect(out).toContain('aria-label="4 out of 5 stars"');
});

test("customers get a helpful toggle on others' reviews only and an own-review form", () => {
  const out = html({ status: "ok", viewer: "customer", sort: "NEWEST", mine: null,
    page: page([review({ viewerHasVoted: true }), review({ id: "8", viewerIsAuthor: true })]) });
  expect(out.match(/aria-pressed="true"/g)).toHaveLength(1);
  expect(out).toContain("Write a review");
  expect(out).toContain("<legend");
  expect(out.match(/type="radio"/g)).toHaveLength(5);
  expect(out).toContain("/ 120 characters");
});

test("an existing hidden review is editable and flagged", () => {
  const mine = { id: "9", stars: 2, title: "Meh", body: "Not for me at all.", status: "HIDDEN", createdAt: "x", updatedAt: "x" };
  const out = html({ status: "ok", viewer: "customer", sort: "NEWEST", page: page([]), mine });
  expect(out).toContain("Edit your review");
  expect(out).toContain("hidden by a moderator");
  expect(out).toContain("Delete review");
  expect(out).toContain("No written reviews yet.");
});

test("empty and error states are announced", () => {
  expect(html({ status: "ok", viewer: "anonymous", sort: "NEWEST", page: page([]), mine: null })).toContain("No ratings yet.");
  const out = html({ status: "error", viewer: "anonymous", sort: "NEWEST", message: "Reviews could not be loaded." });
  expect(out).toContain('role="alert"');
  expect(out).toContain("Try again");
});

test("customers without a qualifying purchase get an explanation instead of the form", () => {
  const out = html({ status: "ok", viewer: "customer", sort: "NEWEST", mine: null, page: page([review()]),
    eligibility: { canReview: false, hasReview: false, reason: "PURCHASE_REQUIRED" } });
  expect(out).not.toContain("<form");
  expect(out).toContain("Only customers who bought this product can write a review.");
});

test("eligible buyers get the form and verified purchases are labelled", () => {
  const out = html({ status: "ok", viewer: "customer", sort: "NEWEST", mine: null, page: page([review({ verifiedPurchase: true })]),
    eligibility: { canReview: true, hasReview: false, reason: null } });
  expect(out).toContain("Write a review");
  expect(out).toContain("Verified purchase");
});
