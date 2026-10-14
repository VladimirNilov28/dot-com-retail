/** Pure order-list/detail helpers shared by the customer and staff order pages. The API re-validates everything. */

export const orderStatuses = ["PENDING", "PAID", "PROCESSING", "SHIPPING", "COMPLETED", "CANCELLED"] as const;
export type OrderStatusValue = (typeof orderStatuses)[number];

export const pageSize = 20;
const maxPage = 10_000;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type SearchParams = Record<string, string | string[] | undefined>;
export type OrderSearch = {
  status: OrderStatusValue | null;
  from: string | null;
  to: string | null;
  page: number;
  query: string | null;
  error: string | null;
};

const one = (value: string | string[] | undefined) => (typeof value === "string" ? value.trim() : "");

function isoDate(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? null : value;
}

/** Reads URL search params into a bounded filter; invalid parts are dropped and reported, never forwarded. */
export function parseOrderSearch(params: SearchParams, options: { staff?: boolean } = {}): OrderSearch {
  const errors: string[] = [];
  const rawStatus = one(params.status);
  const status = (orderStatuses as readonly string[]).includes(rawStatus) ? (rawStatus as OrderStatusValue) : null;
  if (rawStatus && !status) errors.push("Unknown status ignored.");
  let from = one(params.from) ? isoDate(one(params.from)) : null;
  let to = one(params.to) ? isoDate(one(params.to)) : null;
  if ((one(params.from) && !from) || (one(params.to) && !to)) errors.push("Dates must use YYYY-MM-DD.");
  if (from && to && from > to) {
    errors.push("The start date must not be after the end date.");
    from = null;
    to = null;
  }
  const rawPage = one(params.page);
  const page = /^[1-9][0-9]{0,4}$/.test(rawPage) ? Math.min(Number(rawPage), maxPage) : 1;
  const rawQuery = options.staff ? one(params.q).replace(/[\u0000-\u001f\u007f]/g, "") : "";
  const query = rawQuery ? rawQuery.slice(0, 254) : null;
  return { status, from, to, page, query, error: errors.length ? errors.join(" ") : null };
}

function nextDay(date: string): string {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString();
}

/** GraphQL variables; `to` is inclusive in the UI and exclusive (next midnight UTC) in the API. */
export function orderVariables(search: OrderSearch) {
  const filter: Record<string, string> = {};
  if (search.status) filter.status = search.status;
  if (search.from) filter.from = `${search.from}T00:00:00.000Z`;
  if (search.to) filter.to = nextDay(search.to);
  if (search.query) filter.query = search.query;
  return { filter: Object.keys(filter).length ? filter : null, page: search.page - 1, size: pageSize };
}

export function searchHref(base: string, search: OrderSearch, page = search.page): string {
  const params = new URLSearchParams();
  if (search.status) params.set("status", search.status);
  if (search.from) params.set("from", search.from);
  if (search.to) params.set("to", search.to);
  if (search.query) params.set("q", search.query);
  if (page > 1) params.set("page", String(page));
  const text = params.toString();
  return text ? `${base}?${text}` : base;
}

export function pageCount(totalCount: number, size: number): number {
  return Math.max(1, Math.ceil(Math.max(0, totalCount) / Math.max(1, size)));
}

export function isOrderReference(value: string): boolean {
  return uuidPattern.test(value);
}

/** Only absolute http(s) URLs without credentials may become links. */
export function safeTrackingUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (!["http:", "https:"].includes(url.protocol) || !url.hostname || url.username || url.password) return null;
    return url.href;
  } catch {
    return null;
  }
}

export function statusLabel(status: string): string {
  const text = status.toLowerCase().replace(/_/g, " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function formField(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === "string" ? value.trim() : "";
}

/** Customer/staff free text; mirrors the API's 3..1000 character rule. */
export function noteText(value: string, label: string, required: boolean): string | null {
  const text = value.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "").trim();
  if (!text) {
    if (required) throw new Error(`${label} is required.`);
    return null;
  }
  if (text.length < 3 || text.length > 1000) throw new Error(`${label} must be 3 to 1000 characters.`);
  return text;
}

export function trackingInput(form: FormData) {
  const carrier = formField(form, "carrier");
  const trackingNumber = formField(form, "trackingNumber");
  const rawUrl = formField(form, "trackingUrl");
  if (!/^[\p{L}\p{N} .&'-]{2,80}$/u.test(carrier)) throw new Error("Carrier must be 2 to 80 letters, digits or . & ' -");
  if (!/^[A-Za-z0-9-]{4,64}$/.test(trackingNumber)) throw new Error("Tracking number must be 4 to 64 letters, digits or dashes.");
  const trackingUrl = rawUrl ? safeTrackingUrl(rawUrl) : null;
  if (rawUrl && (!trackingUrl || rawUrl.length > 500)) throw new Error("Tracking URL must be an http(s) link of at most 500 characters.");
  return { carrier, trackingNumber, trackingUrl };
}

export const fulfillmentTargets = new Set<string>(["PROCESSING", "SHIPPING", "COMPLETED"]);

export function money(amount: string | number | null | undefined, currency: string | null = "EUR"): string {
  const value = typeof amount === "string" ? Number(amount) : amount;
  if (value === null || value === undefined || !Number.isFinite(value)) return "—";
  return new Intl.NumberFormat("en-IE", { style: "currency", currency: currency || "EUR" }).format(value);
}

export function dateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return `${new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" }).format(date)} UTC`;
}

const refundReasons: Record<string, string> = {
  ORDER_CANCELLED: "This order was cancelled.",
  NOT_PAID: "Refunds are available once payment is confirmed.",
  ALREADY_REFUNDED: "A refund has already been issued for this order.",
  REQUEST_PENDING: "A refund request is already being reviewed.",
};
const cancellationReasons: Record<string, string> = {
  PROCESSING_BEGUN: "Fulfillment has started, so this order can no longer be cancelled. You can request a refund instead.",
  COMPLETED: "This order is complete. You can request a refund instead.",
  ALREADY_CANCELLED: "This order is already cancelled.",
};

export function refundEligibilityText(reason: string | null | undefined): string {
  return (reason && refundReasons[reason]) || "A refund cannot be requested for this order.";
}

export function cancellationEligibilityText(reason: string | null | undefined): string {
  return (reason && cancellationReasons[reason]) || "This order cannot be cancelled online.";
}
