import type { ReviewSort } from "@/lib/graphql/generated";

// Mirrors ProductRatingService on the backend; the server remains authoritative.
export const TITLE_MAX = 120;
export const BODY_MIN = 10;
export const BODY_MAX = 4000;
export const REASON_MIN = 3;
export const REASON_MAX = 500;
export const PAGE_SIZE = 5;

export const reviewSorts: readonly { value: ReviewSort; label: string }[] = [
  { value: "NEWEST", label: "Newest" },
  { value: "HELPFUL", label: "Most helpful" },
  { value: "RATING_HIGH", label: "Highest rating" },
  { value: "RATING_LOW", label: "Lowest rating" },
];

export function reviewSort(value: unknown): ReviewSort {
  return reviewSorts.find((sort) => sort.value === value)?.value ?? "NEWEST";
}

export const reviewIdPattern = /^[1-9][0-9]{0,18}$/;

export type ReviewInput = { stars: number; title: string; body: string };
export type ReviewErrors = Partial<Record<"stars" | "title" | "body", string>>;

const markup = /<\s*[/!?]?\s*[A-Za-z]|<!--|<\?/;
const control = /[\u0000-\u0008\u000B-\u001F\u007F-\u009F]/;

/** Counts length the way the backend does (Unicode code points). */
export function characterCount(value: string): number {
  return [...value].length;
}

export function normalizeText(value: string): string {
  return value.replace(/\r\n?/g, "\n").trim();
}

function plainTextError(value: string, label: string, min: number, max: number, multiline: boolean): string | undefined {
  if (!multiline && value.includes("\n")) return `${label} must be a single line.`;
  if (control.test(value)) return `${label} contains unsupported characters.`;
  if (markup.test(value)) return `${label} must be plain text without HTML.`;
  const length = characterCount(value);
  if (length < min || length > max) {
    return min <= 1 ? `${label} is required and can be at most ${max} characters.` :
      `${label} must be between ${min} and ${max} characters.`;
  }
  return undefined;
}

export function validateReview(input: { stars: unknown; title: unknown; body: unknown }):
  { ok: true; value: ReviewInput } | { ok: false; errors: ReviewErrors } {
  const errors: ReviewErrors = {};
  const stars = typeof input.stars === "number" ? input.stars : Number(input.stars);
  if (!Number.isInteger(stars) || stars < 1 || stars > 5) errors.stars = "Choose a rating from 1 to 5 stars.";
  const title = normalizeText(typeof input.title === "string" ? input.title : "");
  const body = normalizeText(typeof input.body === "string" ? input.body : "");
  const titleError = plainTextError(title, "Title", 1, TITLE_MAX, false);
  const bodyError = plainTextError(body, "Review", BODY_MIN, BODY_MAX, true);
  if (titleError) errors.title = titleError;
  if (bodyError) errors.body = bodyError;
  return Object.keys(errors).length ? { ok: false, errors } : { ok: true, value: { stars, title, body } };
}

export function validateReason(value: unknown): { ok: true; value: string } | { ok: false; error: string } {
  const reason = normalizeText(typeof value === "string" ? value : "");
  const error = plainTextError(reason, "Reason", REASON_MIN, REASON_MAX, false);
  return error ? { ok: false, error } : { ok: true, value: reason };
}

export type Distribution = { stars: number; count: number; percent: number }[];

/** Always five buckets, 5 → 1 stars, with whole-number percentages of the total. */
export function distribution(buckets: readonly { stars: number; count: number }[]): Distribution {
  const counts = new Map(buckets.map((bucket) => [bucket.stars, Math.max(0, bucket.count)]));
  const total = [...counts.values()].reduce((sum, count) => sum + count, 0);
  return [5, 4, 3, 2, 1].map((stars) => {
    const count = counts.get(stars) ?? 0;
    return { stars, count, percent: total ? Math.round((count / total) * 100) : 0 };
  });
}

export function ratingLabel(stars: number): string {
  return `${stars} out of 5 stars`;
}

export function countLabel(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

export function reviewDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" :
    new Intl.DateTimeFormat("en", { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" }).format(date);
}
