import type { Metadata } from "next";
import Link from "next/link";
import { CircleAlert } from "lucide-react";
import { AdminDenied } from "@/components/admin-denied";
import { AdminPageHeader } from "@/components/admin-page-header";
import { AdminLoadError } from "@/components/admin-status";
import { OrderActionForm } from "@/components/orders/order-action-form";
import {
  OrderDelivery, OrderItems, OrderTimeline, PaymentState, StatusBadge, StatusMessages,
} from "@/components/orders/order-view";
import { adminAccess } from "@/lib/admin/gate";
import {
  staffApproveRefund, staffCancel, staffRejectRefund, staffSetTracking, staffUpdateStatus,
} from "@/lib/admin/order-actions";
import { orderRoles } from "@/lib/admin/policy";
import { staffOrderQuery, type OrderDetail } from "@/lib/orders/documents";
import {
  dateTime, fulfillmentTargets, isOrderReference, statusLabel,
} from "@/lib/orders/model";
import { OrderApiError, orderGraphql } from "@/lib/orders/server";

export const metadata: Metadata = { title: "Admin · Order", robots: { index: false, follow: false } };
export const dynamic = "force-dynamic";

function Problem({ text }: { text: string }) {
  return (
    <div className="account-main">
      <AdminPageHeader title="Order" back={{ href: "/admin/orders", label: "Orders" }} />
      <AdminLoadError>{text}</AdminLoadError>
    </div>
  );
}

export default async function AdminOrder({ params, searchParams }: {
  params: Promise<{ publicId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { publicId } = await params;
  const query = await searchParams;
  if (!isOrderReference(publicId)) return <Problem text="This order could not be found." />;
  const access = await adminAccess(orderRoles);
  if (access.kind !== "ok") return <AdminDenied access={access} next={`/admin/orders/${publicId}`} area="Order management" />;
  let order: OrderDetail | null;
  try {
    order = (await orderGraphql<{ order: OrderDetail | null }>(access.session.accessToken, staffOrderQuery, { publicId })).order;
  } catch (error) {
    return <Problem text={error instanceof OrderApiError && error.code === "PERMISSION_DENIED" ?
      "The order API denied this session." : "The order could not be loaded. Retry shortly."} />;
  }
  if (!order) return <Problem text="This order could not be found." />;
  const fields = { publicId: order.publicId };
  const transitions = (order.allowedStatusTransitions ?? []).filter((status) => fulfillmentTargets.has(status));
  const pickup = order.checkout?.details.shipping.method === "PICKUP";
  const openRequests = order.refundRequests.filter((request) => request.status === "REQUESTED");
  return (
    <div className="account-main">
      <AdminPageHeader title={`Order ${order.publicId.slice(0, 8).toUpperCase()}`} back={{ href: "/admin/orders", label: "Orders" }}
        meta={<><StatusBadge value={order.status} /><span className="admin-code break-all">{order.publicId}</span></>}
        description={`Placed ${dateTime(order.createdAt)} · ${order.user ? `${order.user.username} <${order.user.email}>` : `Guest ${order.checkout?.details.contactEmail ?? ""}`}`} />
      <StatusMessages query={query} />
      {openRequests.length ? <p role="status" className="admin-alert" data-tone="warning"><CircleAlert size={18} aria-hidden="true" />
        <span>{openRequests.length} refund request{openRequests.length === 1 ? " is" : "s are"} waiting for a decision.</span></p> : null}

      <div className="admin-split">
      <div className="admin-split-main">
        <OrderItems order={order} />
        <OrderDelivery order={order} />
        <PaymentState order={order} />
        <OrderTimeline order={order} staff />
      </div>
      <div className="admin-split-side">
      <section className="account-card" aria-labelledby="fulfillment-heading">
        <div className="flex flex-wrap items-center gap-2">
          <h2 id="fulfillment-heading" className="text-lg font-semibold">Fulfillment</h2><StatusBadge value={order.status} />
        </div>
        {transitions.length ? transitions.map((status) => (
          <OrderActionForm key={status} action={staffUpdateStatus} className="admin-inline"
            fields={{ ...fields, orderId: order.id, status }} label={`Mark ${statusLabel(status).toLowerCase()}`} pendingLabel="Updating…">
            <label className="auth-field">
              <span className="text-sm">Note (optional)</span>
              <input className="auth-input" name="note" maxLength={1000} />
            </label>
          </OrderActionForm>
        )) : <p className="text-sm text-muted">No further fulfillment step is available for this order.</p>}
        {transitions.includes("SHIPPING") && !pickup && !order.tracking ?
          <p className="text-sm text-muted">Add tracking before marking the order as shipping.</p> : null}
      </section>

      <section className="account-card" aria-labelledby="tracking-heading">
        <h2 id="tracking-heading" className="text-lg font-semibold">Tracking</h2>
        {["PAID", "PROCESSING", "SHIPPING", "COMPLETED"].includes(order.status) ? (
          <OrderActionForm action={staffSetTracking} fields={fields} className="admin-inline" label="Save tracking">
            <label className="auth-field"><span className="text-sm">Carrier</span>
              <input className="auth-input" name="carrier" required minLength={2} maxLength={80} defaultValue={order.tracking?.carrier ?? ""} /></label>
            <label className="auth-field"><span className="text-sm">Tracking number</span>
              <input className="auth-input" name="trackingNumber" required minLength={4} maxLength={64} pattern="[A-Za-z0-9\-]{4,64}"
                defaultValue={order.tracking?.trackingNumber ?? ""} /></label>
            <label className="auth-field"><span className="text-sm">Tracking URL (http/https)</span>
              <input className="auth-input" name="trackingUrl" type="url" maxLength={500} defaultValue={order.tracking?.trackingUrl ?? ""} /></label>
          </OrderActionForm>
        ) : <p className="text-sm text-muted">Tracking can be added once the order is paid.</p>}
      </section>

      <section className="account-card" aria-labelledby="refund-requests-heading">
        <h2 id="refund-requests-heading" className="text-lg font-semibold">Refund requests</h2>
        {openRequests.length ? openRequests.map((request) => (
          <div key={request.id} className="grid gap-2">
            <p className="text-sm">Requested {dateTime(request.createdAt)}: “{request.reason}”</p>
            <OrderActionForm action={staffApproveRefund} fields={{ ...fields, requestId: request.id }} className="admin-inline"
              label="Approve refund" pendingLabel="Approving…"
              confirm={{ title: "Approve this refund?", body: "The full captured amount is refunded through the payment provider. Stock is not restocked.", action: "Approve refund" }}>
              <label className="auth-field"><span className="text-sm">Note (optional)</span>
                <input className="auth-input" name="note" maxLength={1000} /></label>
            </OrderActionForm>
            <OrderActionForm action={staffRejectRefund} fields={{ ...fields, requestId: request.id }} className="admin-inline"
              label="Reject refund" pendingLabel="Rejecting…" variant="secondary">
              <label className="auth-field"><span className="text-sm">Reason shown to the customer</span>
                <input className="auth-input" name="note" required minLength={3} maxLength={1000} /></label>
            </OrderActionForm>
          </div>
        )) : <p className="text-sm text-muted">No refund requests are waiting for a decision.</p>}
      </section>

      <section className="account-card" data-tone="danger" aria-labelledby="cancel-heading">
        <h2 id="cancel-heading" className="text-lg font-semibold">Cancellation</h2>
        {order.cancellationEligibility.allowed ? (
          <OrderActionForm action={staffCancel} fields={fields} label="Cancel order" pendingLabel="Cancelling…" variant="danger"
            confirm={{ title: "Cancel this order?", body: "Reserved stock is released and any captured payment is refunded.", action: "Cancel order", dismiss: "Keep order" }} />
        ) : <p className="text-sm text-muted">Not cancellable: {statusLabel(order.cancellationEligibility.reason ?? "not allowed").toLowerCase()}.</p>}
      </section>

      </div>
      </div>
    </div>
  );
}
