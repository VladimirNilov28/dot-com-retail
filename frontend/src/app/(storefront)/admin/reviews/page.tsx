import type { Metadata } from "next";
import Link from "next/link";
import { AdminDenied } from "@/components/admin-denied";
import { adminAccess, adminGraphql } from "@/lib/admin/gate";
import { hideReview, restoreReview } from "@/lib/admin/review-actions";
import { REASON_MAX, REASON_MIN, ratingLabel, reviewDate } from "@/lib/reviews/model";

export const metadata: Metadata = { title: "Admin · Reviews", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

type Review = {
  id: string; productName: string; productSlug: string; stars: number; title: string | null; body: string | null;
  authorName: string; status: "VISIBLE" | "HIDDEN"; helpfulCount: number; moderationReason: string | null;
  moderatedAt: string | null; createdAt: string;
};
type Page = { items: Review[]; pageInfo: { page: number; totalItems: number; totalPages: number } };

const PAGE_SIZE = 20;
const filters = [
  { value: null, label: "All" },
  { value: "VISIBLE", label: "Visible" },
  { value: "HIDDEN", label: "Hidden" },
] as const;

function href(status: string | null, page: number): string {
  const query = new URLSearchParams();
  if (status) query.set("status", status);
  if (page > 1) query.set("page", String(page));
  const text = query.toString();
  return text ? `/admin/reviews?${text}` : "/admin/reviews";
}

export default async function AdminReviews({ searchParams }: PageProps<"/admin/reviews">) {
  const access = await adminAccess();
  if (access.kind !== "ok") return <AdminDenied access={access} />;
  const query = await searchParams;
  const status = query.status === "VISIBLE" || query.status === "HIDDEN" ? query.status : null;
  const page = typeof query.page === "string" && /^[1-9][0-9]{0,3}$/.test(query.page) ? Number(query.page) : 1;
  const notice = typeof query.notice === "string" ? query.notice.slice(0, 200) : null;
  const error = typeof query.error === "string" ? query.error.slice(0, 200) : null;
  let data: Page | null = null;
  try {
    data = (await adminGraphql<{ moderationReviews: Page }>(access.session.accessToken,
      `query($status: ReviewStatus, $page: Int, $size: Int) { moderationReviews(status: $status, page: $page, size: $size) {
        items { id productName productSlug stars title body authorName status helpfulCount moderationReason moderatedAt createdAt }
        pageInfo { page totalItems totalPages } } }`,
      { status, page: page - 1, size: PAGE_SIZE })).moderationReviews;
  } catch {
    data = null;
  }
  const totalPages = data?.pageInfo.totalPages ?? 0;
  return (
    <div className="account-main">
      <h1 className="text-2xl font-semibold tracking-tight">Review moderation</h1>
      <p className="text-sm text-muted">Hidden reviews are removed from the storefront and from product ratings. Every change is audited.</p>
      <nav aria-label="Filter reviews by status" className="review-actions">
        {filters.map((filter) => (
          <Link key={filter.label} prefetch={false} className="store-cta" href={href(filter.value, 1)}
            aria-current={status === filter.value ? "page" : undefined}>{filter.label}</Link>
        ))}
      </nav>
      <div id="status" tabIndex={-1}>
        {notice ? <p role="status" className="account-badge" data-tone="ok">{notice}</p> : null}
        {error ? <p role="alert" className="account-badge" data-tone="off">{error}</p> : null}
      </div>
      {data === null ? <p role="alert" className="text-muted">Reviews could not be loaded. Retry shortly.</p> : (
        <section className="account-card" aria-labelledby="reviews-heading">
          <h2 id="reviews-heading" className="text-lg font-semibold">
            {data.pageInfo.totalItems} {status ? `${status.toLowerCase()} ` : ""}review{data.pageInfo.totalItems === 1 ? "" : "s"}
          </h2>
          {data.items.length === 0 ? <p className="text-muted">No reviews match this filter.</p> : (
            <ul className="account-rows" aria-label="Reviews">
              {data.items.map((review) => (
                <li key={review.id} className="account-row admin-review">
                  <div className="admin-review-body">
                    <p className="text-sm">
                      <Link prefetch={false} className="store-link" href={`/products/${review.productSlug}#reviews`}>{review.productName}</Link>
                      {" · "}{ratingLabel(review.stars)} · {review.authorName} · <time dateTime={review.createdAt}>{reviewDate(review.createdAt)}</time>
                      {" · "}{review.helpfulCount} helpful
                    </p>
                    <h3 className="font-semibold">{review.title ?? "Rating only (no written review)"}</h3>
                    {review.body ? <p className="review-body text-sm">{review.body}</p> : null}
                    {review.status === "HIDDEN" ? <p className="text-sm text-muted">
                      Hidden{review.moderatedAt ? ` ${reviewDate(review.moderatedAt)}` : ""}: {review.moderationReason ?? "no reason recorded"}
                    </p> : null}
                  </div>
                  <span className="account-badge" data-tone={review.status === "VISIBLE" ? "ok" : "off"}>
                    {review.status === "VISIBLE" ? "Visible" : "Hidden"}
                  </span>
                  {review.status === "VISIBLE" ? (
                    <form action={hideReview} className="admin-inline">
                      <input type="hidden" name="reviewId" value={review.id} />
                      <input type="hidden" name="slug" value={review.productSlug} />
                      <input type="hidden" name="filter" value={status ?? ""} />
                      <input type="hidden" name="page" value={page} />
                      <div className="auth-field">
                        <label htmlFor={`reason-${review.id}`} className="text-sm">Reason for hiding</label>
                        <input id={`reason-${review.id}`} name="reason" className="auth-input" required
                          minLength={REASON_MIN} maxLength={REASON_MAX} autoComplete="off" />
                      </div>
                      <button type="submit" className="store-cta">Hide<span className="sr-only"> review {review.title ?? review.id}</span></button>
                    </form>
                  ) : (
                    <form action={restoreReview} className="admin-inline">
                      <input type="hidden" name="reviewId" value={review.id} />
                      <input type="hidden" name="slug" value={review.productSlug} />
                      <input type="hidden" name="filter" value={status ?? ""} />
                      <input type="hidden" name="page" value={page} />
                      <button type="submit" className="store-cta">Restore<span className="sr-only"> review {review.title ?? review.id}</span></button>
                    </form>
                  )}
                </li>
              ))}
            </ul>
          )}
          {totalPages > 1 ? (
            <nav aria-label="Review pages" className="review-pagination">
              {page > 1 ? <Link prefetch={false} className="store-cta" href={href(status, page - 1)}>Previous</Link> : null}
              <span className="text-sm text-muted">Page {page} of {totalPages}</span>
              {page < totalPages ? <Link prefetch={false} className="store-cta" href={href(status, page + 1)}>Next</Link> : null}
            </nav>
          ) : null}
        </section>
      )}
    </div>
  );
}
