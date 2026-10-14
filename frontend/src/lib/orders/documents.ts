/** GraphQL documents for the order pages; documents.test.mjs validates them against the composed supergraph. */

const address = "firstName lastName addressLine1 addressLine2 postalCode city countryCode phone";

export const orderDetailFields = `
  id publicId status createdAt updatedAt totalAmount cancellationReason
  payment { status }
  refund { status amount currency failureReason }
  cancellationEligibility { allowed reason }
  refundEligibility { allowed reason }
  refundRequests { id status reason decisionNote createdAt decidedAt }
  statusHistory { fromStatus toStatus actor note occurredAt }
  tracking { carrier trackingNumber trackingUrl shippedAt }
  items { id quantity priceAtPurchase snapshot { productName sku quantity unitPrice subtotal } }
  checkout {
    totals { merchandiseSubtotal shippingCharge total currency }
    details {
      contactEmail contactPhone
      shippingAddress { ${address} }
      billingAddress { ${address} }
      shipping { method estimate charge currency pickupLocation { name address { ${address} } } }
    }
  }`;

const listFields = "items { id publicId status createdAt totalAmount payment { status } refund { status } } totalCount page size";

export const myOrdersQuery = `query MyOrdersPage($filter: OrderFilter, $page: Int, $size: Int) {
  myOrders(filter: $filter, page: $page, size: $size) { ${listFields} }
}`;

export const customerOrderQuery = `query CustomerOrderDetail($publicId: UUID!) {
  order(publicId: $publicId) { ${orderDetailFields} }
}`;

export const staffOrdersQuery = `query StaffOrdersPage($filter: StaffOrderFilter, $page: Int, $size: Int) {
  staffOrders(filter: $filter, page: $page, size: $size) { ${listFields.replace("items {", "items { user { username email }")} }
}`;

export const staffOrderQuery = `query StaffOrderDetail($publicId: UUID!) {
  order(publicId: $publicId) { ${orderDetailFields} allowedStatusTransitions user { username email } }
}`;

export const cancelOrderMutation = `mutation CustomerOrderCancel($input: CancelOrderInput!) {
  cancelOrder(input: $input) { requestId order { publicId status } }
}`;

export const staffCancelMutation = `mutation StaffOrderCancel($input: CancelOrderInput!) {
  cancelOrderAsStaff(input: $input) { requestId order { publicId status } }
}`;

export const requestRefundMutation = `mutation CustomerRefundRequest($input: RequestRefundInput!) {
  requestRefund(input: $input) { id status }
}`;

export const approveRefundMutation = `mutation StaffRefundApprove($input: DecideRefundInput!) {
  approveRefund(input: $input) { id status }
}`;

export const rejectRefundMutation = `mutation StaffRefundReject($input: DecideRefundInput!) {
  rejectRefund(input: $input) { id status }
}`;

export const updateStatusMutation = `mutation StaffOrderStatus($orderId: ID!, $input: UpdateOrderStatusInput!) {
  updateOrderStatus(orderId: $orderId, input: $input) { id status }
}`;

export const setTrackingMutation = `mutation StaffOrderTracking($input: SetOrderTrackingInput!) {
  setOrderTracking(input: $input) { id tracking { carrier trackingNumber } }
}`;

type Address = { firstName: string; lastName: string; addressLine1: string; addressLine2: string | null;
  postalCode: string; city: string; countryCode: string; phone: string | null };

export type OrderDetail = {
  id: string; publicId: string; status: string; createdAt: string; updatedAt: string; totalAmount: string;
  cancellationReason: string | null;
  payment: { status: string };
  refund: { status: string; amount: string | null; currency: string | null; failureReason: string | null };
  cancellationEligibility: { allowed: boolean; reason: string | null };
  refundEligibility: { allowed: boolean; reason: string | null };
  refundRequests: { id: string; status: string; reason: string; decisionNote: string | null; createdAt: string; decidedAt: string | null }[];
  statusHistory: { fromStatus: string | null; toStatus: string; actor: string; note: string | null; occurredAt: string }[];
  tracking: { carrier: string; trackingNumber: string; trackingUrl: string | null; shippedAt: string | null } | null;
  items: { id: string; quantity: number; priceAtPurchase: string;
    snapshot: { productName: string; sku: string; quantity: number; unitPrice: string; subtotal: string } | null }[];
  checkout: {
    totals: { merchandiseSubtotal: string; shippingCharge: string; total: string; currency: string };
    details: { contactEmail: string; contactPhone: string | null; shippingAddress: Address | null; billingAddress: Address | null;
      shipping: { method: string; estimate: string; charge: string; currency: string;
        pickupLocation: { name: string; address: Address } | null } };
  } | null;
  allowedStatusTransitions?: string[];
  user?: { username: string; email: string } | null;
};

export type OrderSummary = {
  id: string; publicId: string; status: string; createdAt: string; totalAmount: string;
  payment: { status: string }; refund: { status: string }; user?: { username: string; email: string } | null;
};
export type OrderPage = { items: OrderSummary[]; totalCount: number; page: number; size: number };
export type { Address as OrderAddress };
