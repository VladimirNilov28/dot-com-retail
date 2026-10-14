import type { Metadata } from "next";
import Link from "next/link";
import { CircleAlert, CircleCheck, Eye, EyeOff, MessageSquareText, Star, ThumbsUp } from "lucide-react";
import { AdminDenied } from "@/components/admin-denied";
import { AdminEmpty, AdminPageHeader, AdminPanel } from "@/components/admin-page-header";
import { AdminLoadError } from "@/components/admin-status";
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
      <AdminPageHeader title="Review moderation"
        description="Hidden reviews are removed from the storefront and from product ratings. Every change is audited." />
      <nav aria-label="Filter reviews by status" className="admin-segmented">
        {filters.map((filter) => (
          <Link key={filter.label} prefetch={false} href={href(filter.value, 1)}
            aria-current={status === filter.value ? "page" : undefined}>{filter.label}</Link>
        ))}
      </nav>
      <div id="status" tabIndex={-1} className="admin-status">
        {notice ? <p role="status" className="admin-alert" data-tone="success"><CircleCheck size={18} aria-hidden="true" /><span>{notice}</span></p> : null}
        {error ? <p role="alert" className="admin-alert" data-tone="danger"><CircleAlert size={18} aria-hidden="true" /><span>{error}</span></p> : null}
      </div>
      {data === null ? <AdminLoadError>Reviews could not be loaded. Retry shortly.</AdminLoadError> : (
        <AdminPanel id="reviews"
          title={`${data.pageInfo.totalItems} ${status ? `${status.toLowerCase()} ` : ""}review${data.pageInfo.totalItems === 1 ? "" : "s"}`}>
          {data.items.length === 0 ? <AdminEmpty icon={<MessageSquareText size={22} />} title="No reviews match this filter" /> : (
            <ul aria-label="Reviews" className="admin-flush">
              {data.items.map((review) => (
                <li key={review.id} className="admin-review-card">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="grid gap-1 min-w-0">
                      <Link prefetch={false} className="store-link font-medium" href={`/products/${review.productSlug}#reviews`}>{review.productName}</Link>
                      <div className="admin-review-meta">
                        <span className="admin-stars" role="img" aria-label={ratingLabel(review.stars)}>
                          {[1, 2, 3, 4, 5].map((n) => <Star key={n} size={14} aria-hidden="true" fill={n <= review.stars ? "currentColor" : "none"} className={n <= review.stars ? undefined : "admin-star-off"} />)}
                        </span>
                        <span>{review.authorName}</span>
                        <time dateTime={review.createdAt}>{reviewDate(review.createdAt)}</time>
                        <span className="inline-flex items-center gap-1"><ThumbsUp size={12} aria-hidden="true" />{review.helpfulCount} helpful</span>
                      </div>
                    </div>
                    <span className="account-badge" data-tone={review.status === "VISIBLE" ? "ok" : "warn"}>
                      {review.status === "VISIBLE" ? "Visible" : "Hidden"}
                    </span>
                  </div>
                  <h3 className="font-semibold">{review.title ?? <span className="text-muted font-normal">Rating only (no written review)</span>}</h3>
                  {review.body ? <p className="admin-quote">{review.body}</p> : null}
                  {review.status === "HIDDEN" ? <p className="admin-hint">
                    Hidden{review.moderatedAt ? ` ${reviewDate(review.moderatedAt)}` : ""}: {review.moderationReason ?? "no reason recorded"}
                  </p> : null}
                  {review.status === "VISIBLE" ? (
                    <form action={hideReview} className="admin-inline">
                      <input type="hidden" name="reviewId" value={review.id} />
                      <input type="hidden" name="slug" value={review.productSlug} />
                      <input type="hidden" name="filter" value={status ?? ""} />
                      <input type="hidden" name="page" value={page} />
                      <div className="auth-field admin-search">
                        <label htmlFor={`reason-${review.id}`} className="text-sm">Reason for hiding</label>
                        <input id={`reason-${review.id}`} name="reason" className="auth-input" required
                          minLength={REASON_MIN} maxLength={REASON_MAX} autoComplete="off" />
                      </div>
                      <button type="submit" className="store-cta" data-variant="danger" data-size="sm"><EyeOff size={14} aria-hidden="true" />Hide<span className="sr-only"> review {review.title ?? review.id}</span></button>
                    </form>
                  ) : (
                    <form action={restoreReview} className="admin-inline">
                      <input type="hidden" name="reviewId" value={review.id} />
                      <input type="hidden" name="slug" value={review.productSlug} />
                      <input type="hidden" name="filter" value={status ?? ""} />
                      <input type="hidden" name="page" value={page} />
                      <button type="submit" className="store-cta" data-size="sm"><Eye size={14} aria-hidden="true" />Restore<span className="sr-only"> review {review.title ?? review.id}</span></button>
                    </form>
                  )}
                </li>
              ))}
            </ul>
          )}
        </AdminPanel>
      )}
      {totalPages > 1 ? (
        <nav aria-label="Review pages" className="admin-pagination">
          <span>Page {page} of {totalPages}</span>
          <div>
            {page > 1 ? <Link prefetch={false} className="store-cta" data-size="sm" href={href(status, page - 1)}>Previous</Link> : null}
            {page < totalPages ? <Link prefetch={false} className="store-cta" data-size="sm" href={href(status, page + 1)}>Next</Link> : null}
          </div>
        </nav>
      ) : null}
    </div>
  );
}
