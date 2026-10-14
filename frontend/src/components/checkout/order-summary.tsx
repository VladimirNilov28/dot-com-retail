import { formatPrice } from "@/lib/catalog/model";
import type { CheckoutOrder, CheckoutPreview } from "@/lib/graphql/checkout-operations";

export function OrderSummary({ order }: { order: CheckoutOrder | CheckoutPreview }) {
  return <section className="checkout-section">
    <h2 className="text-lg font-semibold">Order summary</h2>
    <ul className="checkout-order-lines">
      {order.lines.map((line) => <li key={line.productVariantId}>
        <div><strong>{line.productName}</strong><p className="text-sm text-muted">SKU {line.sku} · quantity {line.quantity}</p>
          <p className="text-sm text-muted">{Object.entries(line.attributes ?? {}).map(([key, value]) => `${key}: ${String(value)}`).join(" · ")}</p>
          <p className="text-sm">{formatPrice(line.unitPrice)} each</p></div>
        <strong>{formatPrice(line.subtotal)}</strong>
      </li>)}
    </ul>
    <dl className="checkout-totals">
      <div><dt>Items</dt><dd>{formatPrice(order.totals.merchandiseSubtotal)}</dd></div>
      <div><dt>Shipping</dt><dd>{formatPrice(order.totals.shippingCharge)}</dd></div>
      <div className="checkout-total"><dt>Total</dt><dd>{formatPrice(order.totals.total)}</dd></div>
    </dl>
    <p className="text-sm text-muted">EUR. No separate tax calculation is provided by the current checkout.</p>
  </section>;
}
