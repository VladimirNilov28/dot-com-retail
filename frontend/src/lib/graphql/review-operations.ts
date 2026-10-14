import {
  DeleteMyReviewSource,
  MyReviewSource,
  ProductReviewsSource,
  UnvoteReviewHelpfulSource,
  UpsertMyReviewSource,
  VoteReviewHelpfulSource,
  type DeleteMyReviewMutation,
  type DeleteMyReviewMutationVariables,
  type MyReviewQuery,
  type MyReviewQueryVariables,
  type ProductReviewsQuery,
  type ProductReviewsQueryVariables,
  type ReviewSort,
  type ReviewStatus,
  type UnvoteReviewHelpfulMutation,
  type UnvoteReviewHelpfulMutationVariables,
  type UpsertMyReviewMutation,
  type UpsertMyReviewMutationVariables,
  type VoteReviewHelpfulMutation,
  type VoteReviewHelpfulMutationVariables,
} from "./generated";
import type { Operation } from "./operations";
import { boolean, float, integer, list, object, text } from "./scalars";
import { BODY_MAX, PAGE_SIZE, TITLE_MAX, reviewIdPattern, reviewSorts } from "@/lib/reviews/model";

function variables(value: unknown, keys: readonly string[]) {
  const input = object(value);
  if (Object.keys(input).some((key) => !keys.includes(key))) throw new TypeError("Unexpected variables");
  return input;
}

function id(value: unknown): string {
  const result = text(value);
  if (!reviewIdPattern.test(result)) throw new TypeError("Invalid id");
  return result;
}

function nullableText(value: unknown): string | null {
  return value === null ? null : text(value);
}

function stars(value: unknown): number {
  const result = integer(value);
  if (result < 1 || result > 5) throw new TypeError("Invalid stars");
  return result;
}

function count(value: unknown): number {
  const result = integer(value);
  if (result < 0) throw new TypeError("Invalid count");
  return result;
}

function sort(value: unknown): ReviewSort {
  const match = reviewSorts.find((option) => option.value === value);
  if (!match) throw new TypeError("Unknown review sort");
  return match.value;
}

function status(value: unknown): ReviewStatus {
  if (value === "VISIBLE" || value === "HIDDEN") return value;
  throw new TypeError("Unknown review status");
}

function ownReview(value: unknown) {
  const review = object(value);
  return {
    id: id(review.id), stars: stars(review.stars), title: nullableText(review.title), body: nullableText(review.body),
    status: status(review.status), createdAt: text(review.createdAt), updatedAt: text(review.updatedAt),
  };
}

function helpful(value: unknown) {
  const review = object(value);
  return { id: id(review.id), helpfulCount: count(review.helpfulCount), viewerHasVoted: boolean(review.viewerHasVoted) };
}

export const productReviews: Operation<ProductReviewsQuery, ProductReviewsQueryVariables> = {
  name: "ProductReviews", source: ProductReviewsSource, kind: "query", access: "public",
  variables(value) {
    const input = variables(value, ["productId", "page", "size", "sort"]);
    const page = integer(input.page ?? 0);
    const size = integer(input.size ?? PAGE_SIZE);
    if (page < 0 || page > 10_000 || size < 1 || size > 20) throw new TypeError("Invalid review page");
    return { productId: id(input.productId), page, size, sort: sort(input.sort ?? "NEWEST") };
  },
  decode(value) {
    const page = object(object(value).productReviews);
    const info = object(page.pageInfo);
    return {
      productReviews: {
        averageRating: page.averageRating === null ? null : float(page.averageRating),
        ratingCount: count(page.ratingCount),
        items: list(page.items, (value) => {
          const review = object(value);
          return {
            id: id(review.id), stars: stars(review.stars), title: nullableText(review.title), body: nullableText(review.body),
            authorName: text(review.authorName), helpfulCount: count(review.helpfulCount),
            viewerHasVoted: boolean(review.viewerHasVoted), viewerIsAuthor: boolean(review.viewerIsAuthor),
            createdAt: text(review.createdAt), updatedAt: text(review.updatedAt),
          };
        }),
        pageInfo: { page: count(info.page), size: count(info.size), totalItems: count(info.totalItems), totalPages: count(info.totalPages) },
        distribution: list(page.distribution, (value) => {
          const bucket = object(value);
          return { stars: stars(bucket.stars), count: count(bucket.count) };
        }),
      },
    };
  },
};

export const myReview: Operation<MyReviewQuery, MyReviewQueryVariables> = {
  name: "MyReview", source: MyReviewSource, kind: "query", access: "authenticated",
  variables(value) { return { productId: id(variables(value, ["productId"]).productId) }; },
  decode(value) {
    const review = object(value).myReview;
    return { myReview: review === null ? null : ownReview(review) };
  },
};

export const upsertMyReview: Operation<UpsertMyReviewMutation, UpsertMyReviewMutationVariables> = {
  name: "UpsertMyReview", source: UpsertMyReviewSource, kind: "mutation", access: "authenticated",
  variables(value) {
    const input = variables(value, ["productId", "stars", "title", "body"]);
    const title = text(input.title);
    const body = text(input.body);
    // Coarse transport bound only; ProductRatingService performs the real validation.
    if (title.length > TITLE_MAX * 2 || body.length > BODY_MAX * 2) throw new TypeError("Review is too long");
    return { productId: id(input.productId), stars: stars(input.stars), title, body };
  },
  decode(value) { return { upsertMyReview: ownReview(object(value).upsertMyReview) }; },
};

export const deleteMyReview: Operation<DeleteMyReviewMutation, DeleteMyReviewMutationVariables> = {
  name: "DeleteMyReview", source: DeleteMyReviewSource, kind: "mutation", access: "authenticated",
  variables(value) { return { productId: id(variables(value, ["productId"]).productId) }; },
  decode(value) { return { deleteMyReview: boolean(object(value).deleteMyReview) }; },
};

export const voteReviewHelpful: Operation<VoteReviewHelpfulMutation, VoteReviewHelpfulMutationVariables> = {
  name: "VoteReviewHelpful", source: VoteReviewHelpfulSource, kind: "mutation", access: "authenticated",
  variables(value) { return { reviewId: id(variables(value, ["reviewId"]).reviewId) }; },
  decode(value) { return { voteReviewHelpful: helpful(object(value).voteReviewHelpful) }; },
};

export const unvoteReviewHelpful: Operation<UnvoteReviewHelpfulMutation, UnvoteReviewHelpfulMutationVariables> = {
  name: "UnvoteReviewHelpful", source: UnvoteReviewHelpfulSource, kind: "mutation", access: "authenticated",
  variables(value) { return { reviewId: id(variables(value, ["reviewId"]).reviewId) }; },
  decode(value) { return { unvoteReviewHelpful: helpful(object(value).unvoteReviewHelpful) }; },
};

/** Same document with the customer's token, so viewerHasVoted/viewerIsAuthor reflect the caller. */
export const viewerProductReviews: Operation<ProductReviewsQuery, ProductReviewsQueryVariables> = {
  ...productReviews, access: "authenticated",
};
