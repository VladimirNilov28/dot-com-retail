"use server";

import { headers } from "next/headers";
import { authConfig } from "@/lib/auth/config";
import { authCookie, hasSessionCookie } from "@/lib/auth/model";
import { authenticatedState } from "@/lib/auth/session";
import { hiveConfig, requestHive } from "@/lib/graphql/server";
import type { FailureKind } from "@/lib/graphql/errors";
import type { MyReviewQuery, ProductReviewsQuery, ReviewSort } from "@/lib/graphql/generated";
import {
  deleteMyReview, myReview, productReviews, unvoteReviewHelpful, upsertMyReview, viewerProductReviews, voteReviewHelpful,
} from "@/lib/graphql/review-operations";
import { PAGE_SIZE, reviewIdPattern, reviewSort, validateReview, type ReviewErrors } from "./model";

export type ReviewViewer = "anonymous" | "customer" | "staff";
export type ReviewPageData = ProductReviewsQuery["productReviews"];
export type OwnReview = NonNullable<MyReviewQuery["myReview"]>;
export type ReviewsState =
  | { status: "ok"; viewer: ReviewViewer; sort: ReviewSort; page: ReviewPageData; mine: OwnReview | null }
  | { status: "error"; viewer: ReviewViewer; sort: ReviewSort; message: string };
export type SaveReviewResult = { ok: true; review: OwnReview } | { ok: false; errors?: ReviewErrors; message: string };
export type ActionResult = { ok: true } | { ok: false; message: string };
export type HelpfulResult =
  | { ok: true; review: { id: string; helpfulCount: number; viewerHasVoted: boolean } }
  | { ok: false; message: string };

type Session = { viewer: "anonymous" } | { viewer: "customer" | "staff"; accessToken: string };

async function session(): Promise<Session> {
  try {
    const cookieHeader = (await headers()).get("cookie");
    if (!hasSessionCookie(cookieHeader)) return { viewer: "anonymous" };
    const id = authCookie(cookieHeader, authConfig().sessionCookie);
    if (!id) return { viewer: "anonymous" };
    const { state, user } = await authenticatedState(id);
    if (!state.tokens) return { viewer: "anonymous" };
    // Reviews are written from customer accounts; staff sessions only read them.
    return { viewer: user.role === "USER" ? "customer" : "staff", accessToken: state.tokens.accessToken };
  } catch {
    return { viewer: "anonymous" };
  }
}

function message(kind: FailureKind | "partial", action: "read" | "save" | "delete" | "vote"): string {
  if (kind === "unauthenticated") return "Your session has expired. Sign in again to continue.";
  if (kind === "forbidden") {
    return action === "vote" ? "You can't mark your own review as helpful." : "Your account can't do this.";
  }
  if (kind === "not-found") return action === "vote" ? "This review is no longer available." : "This product is no longer available.";
  if (kind === "validation") return action === "save" ? "Check your review and try again." : "The request was not accepted.";
  return action === "read" ? "Reviews could not be loaded. Try again shortly." : "The change could not be saved. Try again shortly.";
}

function productIdOf(value: unknown): string | null {
  return typeof value === "string" && reviewIdPattern.test(value) ? value : null;
}

export async function loadReviews(productIdInput: string, sortInput: string, pageInput: number): Promise<ReviewsState> {
  const current = await session();
  const sort = reviewSort(sortInput);
  const productId = productIdOf(productIdInput);
  const page = Number.isInteger(pageInput) && pageInput >= 0 && pageInput <= 10_000 ? pageInput : 0;
  if (!productId) return { status: "error", viewer: current.viewer, sort, message: message("validation", "read") };
  const config = hiveConfig(process.env);
  const variables = { productId, page, size: PAGE_SIZE, sort };
  const [list, own] = await Promise.all([
    current.viewer === "anonymous" ?
      requestHive(productReviews, variables, config, { kind: "public" }) :
      requestHive(viewerProductReviews, variables, config, { kind: "authenticated", accessToken: current.accessToken }),
    current.viewer === "customer" ?
      requestHive(myReview, { productId }, config, { kind: "authenticated", accessToken: current.accessToken }) :
      Promise.resolve(null),
  ]);
  if (list.result.status !== "success") {
    console.error("Review read failed:", list.result.status === "failure" ? list.result.error.kind : "partial");
    return { status: "error", viewer: current.viewer, sort,
      message: message(list.result.status === "failure" ? list.result.error.kind : "partial", "read") };
  }
  // A failed own-review read must not hide the public list; the form then starts empty.
  const mine = own && own.result.status === "success" ? own.result.data.myReview : null;
  return { status: "ok", viewer: current.viewer, sort, page: list.result.data.productReviews, mine };
}

export async function saveReview(productIdInput: string, input: { stars: number; title: string; body: string }): Promise<SaveReviewResult> {
  const productId = productIdOf(productIdInput);
  const checked = validateReview(input);
  if (!checked.ok) return { ok: false, errors: checked.errors, message: "Check the highlighted fields." };
  const current = await session();
  if (current.viewer !== "customer" || !productId) return { ok: false, message: message("unauthenticated", "save") };
  const { result } = await requestHive(upsertMyReview, { productId, ...checked.value }, hiveConfig(process.env),
    { kind: "authenticated", accessToken: current.accessToken });
  if (result.status !== "success") {
    console.error("Review save failed:", result.status === "failure" ? result.error.kind : "partial");
    return { ok: false, message: message(result.status === "failure" ? result.error.kind : "partial", "save") };
  }
  return { ok: true, review: result.data.upsertMyReview };
}

export async function removeReview(productIdInput: string): Promise<ActionResult> {
  const productId = productIdOf(productIdInput);
  const current = await session();
  if (current.viewer !== "customer" || !productId) return { ok: false, message: message("unauthenticated", "delete") };
  const { result } = await requestHive(deleteMyReview, { productId }, hiveConfig(process.env),
    { kind: "authenticated", accessToken: current.accessToken });
  if (result.status !== "success") {
    console.error("Review delete failed:", result.status === "failure" ? result.error.kind : "partial");
    return { ok: false, message: message(result.status === "failure" ? result.error.kind : "partial", "delete") };
  }
  return { ok: true };
}

export async function setHelpful(reviewIdInput: string, helpful: boolean): Promise<HelpfulResult> {
  const reviewId = productIdOf(reviewIdInput);
  const current = await session();
  if (current.viewer !== "customer" || !reviewId) return { ok: false, message: message("unauthenticated", "vote") };
  const config = hiveConfig(process.env);
  const context = { kind: "authenticated", accessToken: current.accessToken } as const;
  if (helpful) {
    const { result } = await requestHive(voteReviewHelpful, { reviewId }, config, context);
    if (result.status === "success") return { ok: true, review: result.data.voteReviewHelpful };
    return { ok: false, message: message(result.status === "failure" ? result.error.kind : "partial", "vote") };
  }
  const { result } = await requestHive(unvoteReviewHelpful, { reviewId }, config, context);
  if (result.status === "success") return { ok: true, review: result.data.unvoteReviewHelpful };
  return { ok: false, message: message(result.status === "failure" ? result.error.kind : "partial", "vote") };
}
