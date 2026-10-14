import Link from "next/link";
import type { OrderAddress, OrderDetail, OrderSummary } from "@/lib/orders/documents";
import {
  dateTime, declineReasonText, money, orderStatuses, pageCount, paymentStatusLabel, safeTrackingUrl, searchHref, statusLabel,
  type OrderSearch,
} from "@/lib/orders/model";

const okStatuses = new Set(["PAID", "PROCESSING", "SHIPPING", "COMPLETED", "SUCCEEDED", "APPROVED"]);

export function StatusBadge({ value }: { value: string }) {
  return <span className="account-badge" data-tone={okStatuses.has(value) ? "ok" : "off"}>{statusLabel(value)}</span>;
}

export function OrderFilters({ base, search, staff = false }: { base: string; search: OrderSearch; staff?: boolean }) {
  return (
    <form method="get" action={base} className="admin-inline account-card" aria-label="Filter orders">
      {staff ? (
        <label className="auth-field">
          <span className="text-sm">Customer email, username or reference</span>
          <input className="auth-input" name="q" type="search" maxLength={254} defaultValue={search.query ?? ""} />
        </label>
      ) : null}
      <label className="auth-field">
        <span className="text-sm">Status</span>
        <select className="auth-input" name="status" defaultValue={search.status ?? ""}>
          <option value="">Any status</option>
          {orderStatuses.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
        </select>
      </label>
      <label className="auth-field">
        <span className="text-sm">From</span>
        <input className="auth-input" name="from" type="date" defaultValue={search.from ?? ""} />
      </label>
      <label className="auth-field">
        <span className="text-sm">To</span>
        <input className="auth-input" name="to" type="date" defaultValue={search.to ?? ""} />
      </label>
      <button type="submit" className="store-cta">Apply</button>
      <Link prefetch={false} className="store-link" href={base}>Clear</Link>
    </form>
  );
}

export function OrderList({ base, items, staff = false }: { base: string; items: OrderSummary[]; staff?: boolean }) {
  return (
    <ul className="account-card" aria-label="Orders">
      {items.map((order) => (
        <li key={order.publicId} className="account-row">
          <div className="grid gap-1 min-w-0">
            <Link prefetch={false} className="store-link" href={`${base}/${order.publicId}`}>
              Order {order.publicId.slice(0, 8).toUpperCase()}
            </Link>
            <span className="text-sm text-muted">
              {dateTime(order.createdAt)} · {money(order.totalAmount)}
              {staff ? ` · ${order.user ? order.user.email : "Guest"}` : ""}
            </span>
          </div>
          <div className="flex flex-wrap gap-2">
            <StatusBadge value={order.status} />
            {order.status === "PENDING" && ["DECLINED", "UNRESOLVED"].includes(order.payment.status) ?
              <span className="account-badge" data-tone="off">{paymentStatusLabel(order.payment.status)}</span> : null}
            {order.refund.status !== "NONE" ? <span className="account-badge">Refund {statusLabel(order.refund.status).toLowerCase()}</span> : null}
          </div>
        </li>
      ))}
    </ul>
  );
}

export function Pagination({ base, search, totalCount, size }: { base: string; search: OrderSearch; totalCount: number; size: number }) {
  const pages = pageCount(totalCount, size);
  if (pages <= 1 && search.page <= 1) return null;
  return (
    <nav aria-label="Order pages" className="flex flex-wrap items-center gap-4">
      {search.page > 1 ? <Link prefetch={false} className="store-link" href={searchHref(base, search, Math.min(search.page - 1, pages))}>← Previous</Link> : null}
      <span className="text-sm text-muted">Page {search.page} of {pages} · {totalCount} order{totalCount === 1 ? "" : "s"}</span>
      {search.page < pages ? <Link prefetch={false} className="store-link" href={searchHref(base, search, search.page + 1)}>Next →</Link> : null}
    </nav>
  );
}

function Address({ title, address }: { title: string; address: OrderAddress | null }) {
  if (!address) return null;
  return (
    <div>
      <h3 className="font-semibold">{title}</h3>
      <p>{address.firstName} {address.lastName}<br />{address.addressLine1}
        {address.addressLine2 ? <><br />{address.addressLine2}</> : null}<br />
        {address.postalCode} {address.city}, {address.countryCode}</p>
    </div>
  );
}

export function OrderItems({ order }: { order: OrderDetail }) {
  const totals = order.checkout?.totals;
  return (
    <section className="account-card" aria-labelledby="items-heading">
      <h2 id="items-heading" className="text-lg font-semibold">Items</h2>
      <ul className="account-rows">
        {order.items.map((item) => (
          <li key={item.id} className="account-row">
            <div className="grid min-w-0">
              <span>{item.snapshot?.productName ?? "Product"}</span>
              {item.snapshot ? <span className="text-sm text-muted">SKU {item.snapshot.sku}</span> : null}
            </div>
            <span className="text-sm">{item.quantity} × {money(item.snapshot?.unitPrice ?? item.priceAtPurchase)}</span>
          </li>
        ))}
      </ul>
      <dl className="grid gap-1 text-sm">
        {totals ? <>
          <div className="flex justify-between"><dt>Merchandise</dt><dd>{money(totals.merchandiseSubtotal, totals.currency)}</dd></div>
          <div className="flex justify-between"><dt>Shipping</dt><dd>{money(totals.shippingCharge, totals.currency)}</dd></div>
          <div className="flex justify-between font-semibold"><dt>Total</dt><dd>{money(totals.total, totals.currency)}</dd></div>
        </> : <div className="flex justify-between font-semibold"><dt>Total</dt><dd>{money(order.totalAmount)}</dd></div>}
      </dl>
    </section>
  );
}

export function OrderDelivery({ order }: { order: OrderDetail }) {
  const details = order.checkout?.details;
  const trackingUrl = safeTrackingUrl(order.tracking?.trackingUrl);
  return (
    <section className="account-card" aria-labelledby="delivery-heading">
      <h2 id="delivery-heading" className="text-lg font-semibold">Delivery</h2>
      {details ? <>
        <p className="text-sm">{details.contactEmail}{details.contactPhone ? ` · ${details.contactPhone}` : ""}</p>
        <div>
          <h3 className="font-semibold">Shipping option</h3>
          <p>{statusLabel(details.shipping.method)} · {money(details.shipping.charge, details.shipping.currency)}</p>
          <p className="text-sm text-muted">{details.shipping.estimate}</p>
        </div>
        <Address title="Shipping address" address={details.shippingAddress} />
        {details.shipping.pickupLocation ? <Address title={`Pickup: ${details.shipping.pickupLocation.name}`} address={details.shipping.pickupLocation.address} /> : null}
        <Address title="Billing address" address={details.billingAddress} />
      </> : <p className="text-sm text-muted">Delivery details are not available for this order.</p>}
      <div>
        <h3 className="font-semibold">Tracking</h3>
        {order.tracking ? (
          <p>
            {order.tracking.carrier} · {order.tracking.trackingNumber}
            {trackingUrl ? <> · <a className="store-link" href={trackingUrl} target="_blank" rel="noopener noreferrer nofollow">Track parcel</a></> : null}
            {order.tracking.shippedAt ? <span className="block text-sm text-muted">Shipped {dateTime(order.tracking.shippedAt)}</span> : null}
          </p>
        ) : <p className="text-sm text-muted">Tracking details appear here once the order ships.</p>}
      </div>
    </section>
  );
}

/** `retryHref` is the customer's original confirmation page, where a declined payment can be retried. */
export function PaymentState({ order, retryHref }: { order: OrderDetail; retryHref?: string }) {
  const declined = order.status === "PENDING" && order.payment.status === "DECLINED";
  return (
    <section className="account-card" aria-labelledby="payment-heading">
      <h2 id="payment-heading" className="text-lg font-semibold">Payment and refunds</h2>
      <div className="flex flex-wrap gap-2 items-center">
        <span className="text-sm">Payment</span>
        <span className="account-badge" data-tone={order.payment.status === "SUCCEEDED" ? "ok" : "off"}>{paymentStatusLabel(order.payment.status)}</span>
        <span className="text-sm">Refund</span><StatusBadge value={order.refund.status} />
        {order.refund.amount ? <span className="text-sm">{money(order.refund.amount, order.refund.currency)}</span> : null}
      </div>
      {declined ? (
        <p className="text-sm" role="status">
          {declineReasonText(order.payment.declineReason)} The order and its reserved items are kept.
          {retryHref ? <> <Link prefetch={false} className="store-link" href={retryHref}>Retry payment</Link></> : null}
        </p>
      ) : null}
      {order.status === "PENDING" && order.payment.status === "UNRESOLVED" ? (
        <p className="text-sm text-muted">The payment provider has not confirmed the outcome yet; this updates automatically.</p>
      ) : null}
      {order.refund.failureReason ? <p className="text-sm text-muted">The refund could not be completed automatically; our team has been notified.</p> : null}
      {order.cancellationReason ? <p className="text-sm text-muted">Cancellation: {statusLabel(order.cancellationReason)}</p> : null}
      {order.refundRequests.length ? (
        <ul className="account-rows" aria-label="Refund requests">
          {order.refundRequests.map((request) => (
            <li key={request.id} className="account-row">
              <div className="grid min-w-0">
                <span className="text-sm">Requested {dateTime(request.createdAt)}</span>
                <span className="text-sm text-muted">“{request.reason}”</span>
                {request.decisionNote ? <span className="text-sm text-muted">Decision note: {request.decisionNote}</span> : null}
              </div>
              <StatusBadge value={request.status} />
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

const actorLabels: Record<string, string> = { SYSTEM: "System", CUSTOMER: "You", GUEST: "Guest checkout", STAFF: "Store team", PAYMENT: "Payment provider" };

export function OrderTimeline({ order, staff = false }: { order: OrderDetail; staff?: boolean }) {
  return (
    <section className="account-card" aria-labelledby="timeline-heading">
      <h2 id="timeline-heading" className="text-lg font-semibold">Status history</h2>
      {order.statusHistory.length ? (
        <ol className="account-rows">
          {order.statusHistory.map((event, index) => (
            <li key={`${event.occurredAt}-${index}`} className="account-row">
              <div className="grid min-w-0">
                <span>{event.fromStatus ? `${statusLabel(event.fromStatus)} → ` : ""}{statusLabel(event.toStatus)}</span>
                <span className="text-sm text-muted">
                  {dateTime(event.occurredAt)} · {staff && event.actor === "CUSTOMER" ? "Customer" : actorLabels[event.actor] ?? event.actor}
                </span>
                {event.note ? <span className="text-sm text-muted">{event.note}</span> : null}
              </div>
            </li>
          ))}
        </ol>
      ) : <p className="text-sm text-muted">No status changes recorded yet.</p>}
    </section>
  );
}

/** `messages` maps fixed codes to text (customer pages); without it the query text is shown as-is (staff pages). */
export function StatusMessages({ query, messages }: { query: Record<string, string | string[] | undefined>; messages?: Record<string, string> }) {
  const pick = (value: string | string[] | undefined) => {
    if (typeof value !== "string") return null;
    return messages ? (Object.hasOwn(messages, value) ? messages[value] : null) : value.slice(0, 300);
  };
  const notice = pick(query.notice);
  const error = pick(query.error);
  return (
    <div id="status" tabIndex={-1}>
      {notice ? <p role="status" className="account-notice" data-tone="success">{notice}</p> : null}
      {error ? <p role="alert" className="account-notice" data-tone="error">{error}</p> : null}
    </div>
  );
}
