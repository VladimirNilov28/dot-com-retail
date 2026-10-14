"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { reviewIdPattern, validateReason } from "@/lib/reviews/model";

function field(form: FormData, name: string, pattern: RegExp): string {
  const value = form.get(name);
  if (typeof value !== "string" || !pattern.test(value)) throw new Error("Invalid request");
  return value;
}

/** Keeps the moderator on the same filter/page after the change. */
function listHref(form: FormData, outcome: "notice" | "error", text: string): string {
  const status = form.get("filter");
  const page = form.get("page");
  const query = new URLSearchParams();
  if (status === "VISIBLE" || status === "HIDDEN") query.set("status", status);
  if (typeof page === "string" && /^[1-9][0-9]{0,3}$/.test(page) && page !== "1") query.set("page", page);
  query.set(outcome, text);
  return `/admin/reviews?${query}#status`;
}

async function run(form: FormData, work: (token: string) => Promise<string>) {
  const access = await adminAccess();
  if (access.kind !== "ok") redirect("/admin/reviews");
  let text: string;
  let failed = false;
  try {
    text = await work(access.session.accessToken);
  } catch (error) {
    failed = true;
    text = error instanceof Error ? error.message.slice(0, 200) : "Request failed";
  }
  const slug = form.get("slug");
  if (!failed && typeof slug === "string" && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug)) revalidatePath(`/products/${slug}`);
  redirect(listHref(form, failed ? "error" : "notice", text));
}

export async function hideReview(form: FormData) {
  await run(form, async (token) => {
    const reviewId = field(form, "reviewId", reviewIdPattern);
    const reason = validateReason(form.get("reason"));
    if (!reason.ok) throw new Error(reason.error);
    await adminGraphql(token, `mutation($id: ID!, $reason: String!) { hideReview(reviewId: $id, reason: $reason) { id status } }`,
      { id: reviewId, reason: reason.value });
    return "Review hidden";
  });
}

export async function restoreReview(form: FormData) {
  await run(form, async (token) => {
    await adminGraphql(token, `mutation($id: ID!) { restoreReview(reviewId: $id) { id status } }`,
      { id: field(form, "reviewId", reviewIdPattern) });
    return "Review restored";
  });
}
