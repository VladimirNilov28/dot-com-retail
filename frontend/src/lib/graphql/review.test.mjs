import { expect, test } from "bun:test";
import { productReviews, upsertMyReview, viewerProductReviews, voteReviewHelpful } from "./review-operations";
import { requestHive } from "./server-transport";

const config = { endpoint: "http://localhost:4063/graphql", origin: "http://localhost:3000" };
const page = {
  items: [{ id: "7", stars: 5, title: "Great", body: "Really great keyboard.", authorName: "Customer", helpfulCount: 2,
    viewerHasVoted: false, viewerIsAuthor: false, createdAt: "2025-01-01T00:00:00Z", updatedAt: "2025-01-01T00:00:00Z" }],
  pageInfo: { page: 0, size: 5, totalItems: 1, totalPages: 1 },
  averageRating: 4.5, ratingCount: 2, distribution: [{ stars: 5, count: 1 }, { stars: 4, count: 1 }],
};
const json = (data) => new Response(JSON.stringify({ data }), { headers: { "Content-Type": "application/json" } });

test("anonymous review reads send no credentials", async () => {
  const { result } = await requestHive(productReviews, { productId: "1", page: 0, size: 5, sort: "HELPFUL" }, config, { kind: "public" },
    { fetch: async (_, options) => {
      expect(options.headers.has("authorization")).toBe(false);
      expect(JSON.parse(options.body).variables).toEqual({ productId: "1", page: 0, size: 5, sort: "HELPFUL" });
      return json({ productReviews: page });
    } });
  expect(result.status).toBe("success");
  expect(result.data.productReviews.items[0].title).toBe("Great");
  expect(result.data.productReviews.distribution).toHaveLength(2);
});

test("viewer review reads carry the customer token", async () => {
  const { result } = await requestHive(viewerProductReviews, { productId: "1" }, config, { kind: "authenticated", accessToken: "test-only-token" },
    { fetch: async (_, options) => {
      expect(options.headers.get("authorization")).toBe("Bearer test-only-token");
      return json({ productReviews: { ...page, items: [{ ...page.items[0], viewerHasVoted: true }] } });
    } });
  expect(result.status).toBe("success");
  expect(result.data.productReviews.items[0].viewerHasVoted).toBe(true);
});

test("review variables are bounded before any request", () => {
  expect(() => productReviews.variables({ productId: "1", size: 21 })).toThrow();
  expect(() => productReviews.variables({ productId: "abc" })).toThrow();
  expect(() => productReviews.variables({ productId: "1", sort: "BOGUS" })).toThrow();
  expect(() => productReviews.variables({ productId: "1", extra: true })).toThrow();
  expect(productReviews.variables({ productId: "1" })).toEqual({ productId: "1", page: 0, size: 5, sort: "NEWEST" });
  expect(() => upsertMyReview.variables({ productId: "1", stars: 9, title: "t", body: "long enough body" })).toThrow();
  expect(() => voteReviewHelpful.variables({ reviewId: "-1" })).toThrow();
});

test("malformed review payloads are protocol failures", async () => {
  const { result } = await requestHive(productReviews, { productId: "1" }, config, { kind: "public" },
    { fetch: async () => json({ productReviews: { ...page, items: [{ ...page.items[0], stars: 9 }] } }) });
  expect(result.status).toBe("failure");
  expect(result.error.kind).toBe("protocol");
});
