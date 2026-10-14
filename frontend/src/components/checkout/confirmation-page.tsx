"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@heroui/react";
import { customerConfirmation, guestConfirmation, customerCancel, guestCancel, type CheckoutOrder } from "@/lib/graphql/checkout-operations";
import { uuid, type UUID } from "@/lib/graphql/scalars";
import { checkoutError, clearAttempt } from "@/lib/checkout/model";
import { OrderSummary } from "./order-summary";
import { StripePayment } from "./stripe-payment";
import { useCheckoutIdentity } from "./identity-boundary";
import { requestCheckout } from "@/lib/checkout/identity";
import { declineReasonText, paymentStatusLabel } from "@/lib/orders/model";

function Address({ title, address }: { title: string; address: CheckoutOrder["details"]["shippingAddress"] }) {
  if (!address) return null;
  return <div><h3 className="font-semibold">{title}</h3>
    <p>{address.firstName} {address.lastName}<br />{address.addressLine1}
      {address.addressLine2 ? <><br />{address.addressLine2}</> : null}<br />
      {address.postalCode} {address.city}, {address.countryCode}</p>
  </div>;
}

export function ConfirmationPageView({ reference, guest }: { reference: string; guest: boolean }) {
  const owner = useCheckoutIdentity();
  const [order, setOrder] = useState<CheckoutOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [stopped, setStopped] = useState(false);
  const [revision, setRevision] = useState(0);
  const [cancelling, setCancelling] = useState(false);
  const cancelRequest = useRef<UUID | null>(null);
  const cancelBusy = useRef(false);
  const mode = guest ? "guest" : "user";
  const refresh = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    const started = Date.now();
    let reads = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function read() {
      let requestId: UUID;
      try { requestId = uuid(reference); }
      catch { setError("Invalid order reference."); setLoading(false); return; }
      if (reads === 0) setStopped(false);
      if (Date.now() - started > 120_000 || reads >= 24) { setStopped(true); return; }
      if (document.visibilityState === "hidden") { timer = setTimeout(() => void read(), 2500); return; }
      ++reads;
      if (guest !== (owner.mode === "guest")) { setError("This order belongs to a different checkout identity."); setLoading(false); return; }
      const result = guest ? await requestCheckout(owner, guestConfirmation, { requestId }, { signal: controller.signal }) :
        await requestCheckout(owner, customerConfirmation, { requestId }, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setLoading(false);
      if (result.status !== "success") { setError(checkoutError(result.error)); setStopped(true); return; }
      const value = "guestOrder" in result.data ? result.data.guestOrder : result.data.checkoutOrder;
      if (!value) { setError("No order was found for this authorized checkout request. If placement was uncertain, return to checkout and retry its saved request."); setStopped(true); return; }
      setOrder(value); setError("");
      if (value.status !== "PENDING") {
        try { clearAttempt(owner, value.requestId); }
        catch { setError("Order state is confirmed, but saved recovery cleanup failed. Revisit checkout before starting another purchase."); }
      }
      if (value.status === "PENDING" || (value.status === "CANCELLED" && !["NONE", "SUCCEEDED", "FAILED"].includes(value.refund.status))) {
        timer = setTimeout(() => void read(), 2500);
      }
    }
    void read();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [reference, guest, revision, owner]);

  const paid = order?.payment.status === "SUCCEEDED";
  const failed = order?.payment.status === "FAILED";
  const declined = order?.status === "PENDING" && order.payment.status === "DECLINED";
  const title = order?.status === "CANCELLED" ? "Order cancelled" :
    paid ? "Payment confirmed" : failed ? "Payment failed" : declined ? "Payment declined" :
      order?.payment.status === "UNRESOLVED" ? "Payment being confirmed" : "Payment pending";
  return <div className="checkout-page">
    <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Order confirmation</h1>
    {loading ? <p className="checkout-skeleton" role="status">Loading your protected order…</p> : null}
    {error ? <section className="cart-notice" data-tone="error" role="alert"><p>{error}</p>
      <Button variant="secondary" onPress={refresh}>Check again</Button><Link href="/checkout" className="store-link">Return to saved checkout</Link>
    </section> : null}
    {order ? <>
      <section className="checkout-section" aria-live="polite">
        <h2 className="text-xl font-semibold">{title}</h2>
        <p className="checkout-reference">Order reference: {order.publicId}</p>
        <p>Order: {order.status.toLowerCase()} · {paymentStatusLabel(order.payment.status)}</p>
        {declined ? <div className="cart-notice" data-tone="error" role="alert">
          <p>{declineReasonText(order.payment.declineReason)} Your order and reserved items are kept — retry below with the same or another card. No new order is needed.</p>
        </div> : null}
        {order.status === "PENDING" && order.payment.status === "UNRESOLVED" ? <p className="text-sm text-muted">The payment provider has not confirmed the outcome yet. We keep checking automatically; do not place another order.</p> : null}
        {!paid && !declined && order.status === "PENDING" ? <p className="text-sm text-muted">Placement is not payment confirmation. Verified provider events and the queue update this order; do not place another order while waiting.</p> : null}
        {order.refund.status !== "NONE" ? <p>Refund: {order.refund.status.toLowerCase()}{order.refund.failureReason ? " — contact support if this remains unresolved." : ""}</p> : null}
        {stopped ? <p role="status">Automatic refresh has paused. You can check again without placing an order or starting a charge.</p> : null}
        <Button variant="secondary" onPress={refresh}>Refresh order status</Button>
        {order.status === "PENDING" && order.cancellationEligibility.allowed ? <Button variant="secondary" isDisabled={cancelling}
          onPress={async () => {
            if (cancelBusy.current || !window.confirm("Cancel this pending order and release its stock? An in-flight payment may still settle and require an asynchronous refund.")) return;
            cancelBusy.current = true; setCancelling(true);
            cancelRequest.current ??= uuid(crypto.randomUUID());
            try {
              const input = { requestId: cancelRequest.current, publicId: order.publicId };
              const result = guest ? await requestCheckout(owner, guestCancel, { input }) : await requestCheckout(owner, customerCancel, { input });
              if (result.status !== "success") setError("Cancellation is not confirmed. Refresh this order or retry the same cancellation.");
              refresh();
            } finally { cancelBusy.current = false; setCancelling(false); }
          }}>{cancelling ? "Cancelling…" : "Cancel pending order"}</Button> : null}
      </section>
      <div className="checkout-layout">
        <div className="checkout-form">
          {order.status === "PENDING" && order.details.paymentSelection === "STRIPE" ? <StripePayment requestId={order.requestId} mode={mode} onUpdated={refresh} /> : null}
          <section className="checkout-section">
            <h2 className="text-lg font-semibold">Contact and delivery</h2>
            <p>{order.details.contactEmail}{order.details.contactPhone ? ` · ${order.details.contactPhone}` : ""}</p>
            <Address title="Shipping address" address={order.details.shippingAddress} />
            <Address title="Billing address" address={order.details.billingAddress} />
            <div><h3 className="font-semibold">Shipping option</h3><p>{order.details.shipping.method}</p>
              <p className="text-sm text-muted">{order.details.shipping.estimate}</p></div>
            {order.details.shipping.pickupLocation ? <div><p>{order.details.shipping.pickupLocation.name}</p>
              <Address title="Pickup address" address={order.details.shipping.pickupLocation.address} /></div> : null}
            <p className="text-sm text-muted">Shipping estimates are configured development data, not a carrier delivery commitment.</p>
            <p className="text-sm text-muted">Order notifications use the email above. This page does not assert inbox delivery.</p>
          </section>
        </div>
        <OrderSummary order={order} />
      </div>
      <div className="flex flex-wrap items-center gap-4">
        {!guest ? <Link prefetch={false} href={`/account/orders/${order.publicId}`} className="store-link">View order details</Link> : null}
        {order.status !== "PENDING" ? <Link href="/catalog/all-products" className="store-cta">Continue shopping</Link> : null}
      </div>
    </> : null}
  </div>;
}
