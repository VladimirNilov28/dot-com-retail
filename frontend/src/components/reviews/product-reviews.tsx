"use client";

import Link from "next/link";
import { useId, useRef, useState, useTransition } from "react";
import { Star } from "lucide-react";
import type { ReviewSort } from "@/lib/graphql/generated";
import { loadReviews, removeReview, saveReview, setHelpful, type OwnReview, type ReviewsState } from "@/lib/reviews/actions";
import {
  BODY_MAX, BODY_MIN, TITLE_MAX, characterCount, countLabel, distribution, ratingLabel, reviewDate, reviewSorts, validateReview,
  type ReviewErrors,
} from "@/lib/reviews/model";

type Notice = { tone: "success" | "error"; text: string } | null;

export function ProductReviews({ productId, slug, initial }: { productId: string; slug: string; initial: ReviewsState }) {
  const [state, setState] = useState(initial);
  const [pageIndex, setPageIndex] = useState(initial.status === "ok" ? initial.page.pageInfo.page : 0);
  const [pending, startTransition] = useTransition();
  const [notice, setNotice] = useState<Notice>(null);
  const listRef = useRef<HTMLHeadingElement>(null);
  const sortId = useId();

  function refresh(sort: ReviewSort, page: number, focusList = false) {
    startTransition(async () => {
      const next = await loadReviews(productId, sort, page);
      setState(next);
      if (next.status === "ok") setPageIndex(next.page.pageInfo.page);
      if (focusList) listRef.current?.focus();
    });
  }

  async function toggle(reviewId: string, voted: boolean) {
    setNotice(null);
    const outcome = await setHelpful(reviewId, !voted);
    if (!outcome.ok) { setNotice({ tone: "error", text: outcome.message }); return; }
    setState((current) => current.status !== "ok" ? current : {
      ...current,
      page: { ...current.page, items: current.page.items.map((item) => item.id === outcome.review.id ? { ...item, ...outcome.review } : item) },
    });
  }

  const summary = state.status === "ok" ? state.page : null;
  const buckets = summary ? distribution(summary.distribution) : [];
  const totalPages = summary?.pageInfo.totalPages ?? 0;
  const loginHref = `/login?returnTo=${encodeURIComponent(`/products/${slug}#reviews`)}`;

  return <section id="reviews" className="product-reviews" aria-labelledby="reviews-heading" aria-busy={pending}>
    <h2 id="reviews-heading" className="text-xl font-semibold tracking-tight">Customer reviews</h2>
    {state.status === "error" ? <div className="cart-notice" data-tone="error" role="alert">
      <p>{state.message}</p>
      <button type="button" className="store-cta" disabled={pending} onClick={() => refresh(state.sort, pageIndex)}>Try again</button>
    </div> : <>
      <div className="review-summary">
        <div>
          {summary && summary.ratingCount > 0 && summary.averageRating !== null ? <>
            <p className="review-average"><Star size={24} aria-hidden="true" focusable="false" />
              <span>{summary.averageRating.toFixed(1)}<span className="sr-only"> out of 5</span></span></p>
            <p className="text-sm text-muted">Based on {countLabel(summary.ratingCount, "rating")}</p>
          </> : <p className="text-muted">No ratings yet.</p>}
        </div>
        {summary && summary.ratingCount > 0 && <ul className="review-distribution" aria-label="Rating distribution">
          {buckets.map((bucket) => <li key={bucket.stars}>
            <span>{bucket.stars} star</span>
            <span className="review-bar" aria-hidden="true"><span style={{ width: `${bucket.percent}%` }} /></span>
            <span className="text-muted">{bucket.count}</span>
            <span className="sr-only">{countLabel(bucket.count, "rating")} with {bucket.stars} stars, {bucket.percent}%</span>
          </li>)}
        </ul>}
      </div>

      {state.viewer === "customer" ? <OwnReviewForm key={state.mine?.id ?? "new"} productId={productId} mine={state.mine}
        onChanged={(text) => { setNotice({ tone: "success", text }); refresh(state.sort, 0); }} /> :
        state.viewer === "staff" ? <p className="text-sm text-muted">Reviews are written from customer accounts.</p> :
        <p className="text-sm"><Link href={loginHref} className="store-link">Sign in</Link> to write a review or mark reviews as helpful.</p>}

      {notice && <p className="cart-notice" data-tone={notice.tone} role={notice.tone === "error" ? "alert" : "status"}>{notice.text}</p>}

      <div className="review-list-header">
        <h3 ref={listRef} tabIndex={-1} className="text-lg font-semibold tracking-tight">
          {summary ? countLabel(summary.pageInfo.totalItems, "written review") : "Written reviews"}
        </h3>
        {summary && summary.pageInfo.totalItems > 1 && <div className="results-sort">
          <label htmlFor={sortId} className="text-xs text-muted">Sort reviews</label>
          <select id={sortId} className="results-sort-select" value={state.sort} disabled={pending}
            onChange={(event) => refresh(event.target.value as ReviewSort, 0)}>
            {reviewSorts.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </div>}
      </div>
      {summary && summary.items.length === 0 ? <p className="text-muted">No written reviews yet.</p> :
        <ol className="review-list">
          {summary?.items.map((review) => <li key={review.id} className="review-item">
            <article aria-labelledby={`review-${review.id}`}>
              <p className="product-rating text-sm" aria-label={ratingLabel(review.stars)}>
                {[1, 2, 3, 4, 5].map((n) => <Star key={n} size={16} aria-hidden="true" focusable="false"
                  className={n <= review.stars ? "review-star-on" : "review-star-off"} />)}
              </p>
              <h4 id={`review-${review.id}`} className="font-semibold">{review.title}</h4>
              <p className="text-sm text-muted">
                By {review.authorName}{review.viewerIsAuthor ? " (you)" : ""} · <time dateTime={review.createdAt}>{reviewDate(review.createdAt)}</time>
                {review.updatedAt !== review.createdAt ? " · edited" : ""}
              </p>
              <p className="review-body">{review.body}</p>
              <div className="review-helpful">
                {state.viewer === "customer" && !review.viewerIsAuthor ?
                  <button type="button" className="store-cta" aria-pressed={review.viewerHasVoted} disabled={pending}
                    onClick={() => void toggle(review.id, review.viewerHasVoted)}>
                    Helpful ({review.helpfulCount})<span className="sr-only">{review.viewerHasVoted ? ", marked by you" : ""}</span>
                  </button> :
                  <span className="text-sm text-muted">{countLabel(review.helpfulCount, "person", "people")} found this helpful</span>}
              </div>
            </article>
          </li>)}
        </ol>}
      {totalPages > 1 && <nav className="review-pagination" aria-label="Review pages">
        <button type="button" className="store-cta" disabled={pending || pageIndex <= 0}
          onClick={() => refresh(state.sort, pageIndex - 1, true)}>Previous</button>
        <span className="text-sm text-muted" aria-live="polite">Page {pageIndex + 1} of {totalPages}</span>
        <button type="button" className="store-cta" disabled={pending || pageIndex + 1 >= totalPages}
          onClick={() => refresh(state.sort, pageIndex + 1, true)}>Next</button>
      </nav>}
    </>}
  </section>;
}

function OwnReviewForm({ productId, mine, onChanged }: { productId: string; mine: OwnReview | null; onChanged: (text: string) => void }) {
  const id = useId();
  const [stars, setStars] = useState(mine?.stars ?? 0);
  const [title, setTitle] = useState(mine?.title ?? "");
  const [body, setBody] = useState(mine?.body ?? "");
  const [errors, setErrors] = useState<ReviewErrors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const written = Boolean(mine?.title);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setFailure(null);
    const checked = validateReview({ stars, title, body });
    if (!checked.ok) { setErrors(checked.errors); return; }
    setErrors({});
    setBusy(true);
    const outcome = await saveReview(productId, checked.value);
    setBusy(false);
    if (!outcome.ok) { setErrors(outcome.errors ?? {}); setFailure(outcome.message); return; }
    onChanged(written ? "Your review was updated." : "Thanks — your review was published.");
  }

  async function remove() {
    setBusy(true);
    setFailure(null);
    const outcome = await removeReview(productId);
    setBusy(false);
    setConfirming(false);
    if (!outcome.ok) { setFailure(outcome.message); return; }
    onChanged("Your review was deleted.");
  }

  const describe = (field: keyof ReviewErrors, help: string) => errors[field] ? `${help} ${id}-${field}-error` : help;
  return <form className="review-form auth-form" onSubmit={(event) => void submit(event)} noValidate aria-busy={busy}
    aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`} className="text-lg font-semibold tracking-tight">{written ? "Edit your review" : "Write a review"}</h3>
    {mine?.status === "HIDDEN" && <p className="text-sm text-muted" role="note">Your review is hidden by a moderator and isn&apos;t shown publicly.</p>}
    {mine && !written && <p className="text-sm text-muted">You rated this product {ratingLabel(mine.stars)}. Add a title and review to share details.</p>}
    <fieldset className="review-stars" aria-describedby={errors.stars ? `${id}-stars-error` : undefined} aria-invalid={Boolean(errors.stars)}>
      <legend className="text-sm">Rating</legend>
      {[1, 2, 3, 4, 5].map((n) => <label key={n} className="review-star-option">
        <input type="radio" name={`${id}-stars`} value={n} checked={stars === n} onChange={() => setStars(n)} />
        <span>{n} {n === 1 ? "star" : "stars"}</span>
      </label>)}
      {errors.stars && <p id={`${id}-stars-error`} className="review-error">{errors.stars}</p>}
    </fieldset>
    <div className="auth-field" data-invalid={Boolean(errors.title)}>
      <label htmlFor={`${id}-title`} className="text-sm">Title</label>
      <input id={`${id}-title`} className="auth-input" value={title} maxLength={TITLE_MAX + 20} autoComplete="off"
        aria-invalid={Boolean(errors.title)} aria-describedby={describe("title", `${id}-title-count`)}
        onChange={(event) => setTitle(event.target.value)} />
      <p id={`${id}-title-count`} className="text-xs text-muted">{characterCount(title.trim())} / {TITLE_MAX} characters</p>
      {errors.title && <p id={`${id}-title-error`} className="review-error">{errors.title}</p>}
    </div>
    <div className="auth-field" data-invalid={Boolean(errors.body)}>
      <label htmlFor={`${id}-body`} className="text-sm">Review</label>
      <textarea id={`${id}-body`} className="auth-input review-textarea" value={body} rows={5}
        aria-invalid={Boolean(errors.body)} aria-describedby={describe("body", `${id}-body-count`)}
        onChange={(event) => setBody(event.target.value)} />
      <p id={`${id}-body-count`} className="text-xs text-muted">
        {characterCount(body.trim())} / {BODY_MAX} characters (minimum {BODY_MIN}). Plain text only.
      </p>
      {errors.body && <p id={`${id}-body-error`} className="review-error">{errors.body}</p>}
    </div>
    {failure && <p className="cart-notice" data-tone="error" role="alert">{failure}</p>}
    <div className="review-actions">
      <button type="submit" className="store-cta" disabled={busy}>{busy ? "Saving…" : written ? "Update review" : "Submit review"}</button>
      {mine && (confirming ? <>
        <span className="text-sm" id={`${id}-confirm`}>Delete your review and rating?</span>
        <button type="button" className="store-cta" disabled={busy} aria-describedby={`${id}-confirm`} onClick={() => void remove()}>Yes, delete</button>
        <button type="button" className="store-cta" disabled={busy} onClick={() => setConfirming(false)}>Cancel</button>
      </> : <button type="button" className="store-cta" disabled={busy} onClick={() => setConfirming(true)}>Delete review</button>)}
    </div>
  </form>;
}
