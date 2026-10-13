"use client";

import Link from "next/link";
import { Button } from "@heroui/react";
import { formatPrice } from "@/lib/catalog/model";
import { CartLines } from "./cart-lines";
import { useCart } from "./cart-provider";

export function CartPageView() {
  const { snapshot, loading, notice, reload, mode } = useCart();
  const cart = snapshot.status === "ok" ? snapshot.cart : null;
  const issues = cart?.lines.filter((line) => line.issue) ?? [];
  return (
    <div className="cart-page" aria-busy={loading}>
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Cart</h1>
      {notice ? <p role={notice.tone === "error" ? "alert" : "status"} className="cart-notice" data-tone={notice.tone}>{notice.text}</p> : null}
      {loading ? <p role="status" className="text-muted">Loading your cart…</p> :
        snapshot.status === "error" ? (
          <div role="alert" className="cart-notice" data-tone="error">
            <p>{snapshot.message}</p>
            <Button variant="secondary" onPress={() => void reload()}>Try again</Button>
          </div>
        ) : !cart || cart.lines.length === 0 ? (
          <section className="cart-empty">
            <p className="text-muted">Your cart is empty.</p>
            <Link href="/catalog/all-products" className="store-cta">Continue shopping</Link>
          </section>
        ) : (
          <div className="cart-layout">
            <CartLines lines={cart.lines} />
            <aside className="cart-summary" aria-label="Order summary">
              <h2 className="text-lg font-semibold">Summary</h2>
              <p className="cart-subtotal"><span>Subtotal</span><strong>{formatPrice(cart.subtotal)}</strong></p>
              <p className="text-sm text-muted">Prices, shipping and stock are confirmed at checkout.</p>
              {issues.length > 0 ? <p role="status" className="cart-line-issue">{issues.length} {issues.length === 1 ? "item needs" : "items need"} attention before checkout.</p> : null}
              {mode === "guest" ? <p className="text-sm text-muted"><Link href="/login?returnTo=%2Fcart" className="store-link">Sign in</Link> to keep this cart with your account.</p> : null}
              <Link href="/checkout" className="store-cta">Continue to checkout</Link>
              <Link href="/catalog/all-products" className="store-link">Continue shopping</Link>
            </aside>
          </div>
        )}
    </div>
  );
}
