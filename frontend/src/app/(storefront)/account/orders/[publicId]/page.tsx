import type { Metadata } from "next";
import Link from "next/link";
import { AccountHeading } from "@/components/account/account-gate";
import { AuthProblem } from "@/components/auth-page";
import { OrderActionForm } from "@/components/orders/order-action-form";
import {
  OrderDelivery, OrderItems, OrderTimeline, PaymentState, StatusBadge, StatusMessages,
} from "@/components/orders/order-view";
import { cancelMyOrder, requestMyRefund } from "@/lib/orders/actions";
import { customerOrderQuery, type OrderDetail } from "@/lib/orders/documents";
import {
  cancellationEligibilityText, dateTime, isOrderReference, refundEligibilityText,
} from "@/lib/orders/model";
import { customerMessages, customerSession, OrderApiError, orderGraphql } from "@/lib/orders/server";

export const metadata: Metadata = { title: "Order details", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

function Unavailable({ text }: { text: string }) {
  return (
    <div className="account-main">
      <AccountHeading title="Order details" />
      <p role="alert" className="account-card">{text}</p>
      <Link prefetch={false} className="store-link" href="/account/orders">Back to your orders</Link>
    </div>
  );
}

export default async function AccountOrder({ params, searchParams }: {
  params: Promise<{ publicId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { publicId } = await params;
  const query = await searchParams;
  if (!isOrderReference(publicId)) return <Unavailable text="This order could not be found." />;
  const self = `/account/orders/${publicId}`;
  const session = await customerSession();
  if (session.kind === "anonymous") return (
    <div className="account-main">
      <AccountHeading title="Order details" description="Sign in to see this order." />
      { }
      <a className="store-cta justify-self-start" href={`/login?returnTo=${encodeURIComponent(self)}`}>Sign in</a>
    </div>
  );
  if (session.kind === "problem") return <AuthProblem code={session.code} returnTo={self} />;
  let order: OrderDetail | null;
  try {
    order = (await orderGraphql<{ order: OrderDetail | null }>(session.accessToken, customerOrderQuery, { publicId })).order;
  } catch (error) {
    // Foreign and missing orders look the same so references can't be probed.
    if (error instanceof OrderApiError && (error.code === "PERMISSION_DENIED" || error.code === "NOT_FOUND")) {
      return <Unavailable text="This order could not be found." />;
    }
    return <Unavailable text="This order could not be loaded right now. Try again shortly." />;
  }
  if (!order) return <Unavailable text="This order could not be found." />;
  return (
    <div className="account-main">
      <div>
        <Link prefetch={false} className="store-link" href="/account/orders">← Your orders</Link>
        <AccountHeading title={`Order ${order.publicId.slice(0, 8).toUpperCase()}`} description={`Placed ${dateTime(order.createdAt)}`} />
      </div>
      <StatusMessages query={query} messages={customerMessages} />
      <section className="account-card account-summary" aria-label="Order status">
        <div className="flex flex-wrap items-center gap-2"><span>Status</span><StatusBadge value={order.status} /></div>
        <span className="text-sm text-muted break-all">Reference {order.publicId}</span>
      </section>
      <OrderItems order={order} />
      <OrderDelivery order={order} />
      <PaymentState order={order} retryHref={order.checkout && isOrderReference(order.checkout.requestId) ? `/order-confirmation/${order.checkout.requestId}` : undefined} />
      <OrderTimeline order={order} />
      <section className="account-card" aria-labelledby="help-heading">
        <h2 id="help-heading" className="text-lg font-semibold">Need to change something?</h2>
        {order.cancellationEligibility.allowed ? (
          <OrderActionForm action={cancelMyOrder} fields={{ publicId: order.publicId }} label="Cancel order" pendingLabel="Cancelling…" variant="danger"
            confirm={{ title: "Cancel this order?", action: "Cancel order", dismiss: "Keep order",
              body: order.payment.status === "SUCCEEDED" ? "Reserved stock is released and your payment is refunded to the original method." :
                "Reserved stock is released. If a payment is still completing it will be refunded automatically." }} />
        ) : <p className="text-sm text-muted">{cancellationEligibilityText(order.cancellationEligibility.reason)}</p>}
        {order.refundEligibility.allowed ? (
          <OrderActionForm action={requestMyRefund} fields={{ publicId: order.publicId }} label="Request refund" pendingLabel="Sending…" variant="secondary">
            <label className="auth-field">
              <span className="text-sm">Why are you requesting a refund?</span>
              <textarea className="auth-input" name="reason" required minLength={3} maxLength={1000} rows={4} />
            </label>
          </OrderActionForm>
        ) : order.status !== "PENDING" && order.status !== "CANCELLED" ? (
          <p className="text-sm text-muted">{refundEligibilityText(order.refundEligibility.reason)}</p>
        ) : null}
      </section>
    </div>
  );
}
