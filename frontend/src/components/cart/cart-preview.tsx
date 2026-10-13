"use client";

import Link from "next/link";
import { useState } from "react";
import { Button, Drawer } from "@heroui/react";
import { ShoppingCart, X } from "lucide-react";
import { formatPrice } from "@/lib/catalog/model";
import { CartLines } from "./cart-lines";
import { cartCount, useCart } from "./cart-provider";

export function CartPreview() {
  const { snapshot, loading, notice, reload } = useCart();
  const [open, setOpen] = useState(false);
  const count = cartCount(snapshot);
  const cart = snapshot.status === "ok" ? snapshot.cart : null;
  return (
    <Drawer isOpen={open} onOpenChange={setOpen}>
      <Button variant="ghost" className="store-action cart-trigger" aria-haspopup="dialog"
        aria-label={count ? `Cart, ${count} ${count === 1 ? "item" : "items"}` : "Cart"}>
        <ShoppingCart className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
        <span className="store-action-label">Cart</span>
        {count > 0 ? <span className="cart-badge" aria-hidden="true">{count > 99 ? "99+" : count}</span> : null}
      </Button>
      <Drawer.Backdrop className="store-drawer-backdrop">
        <Drawer.Content placement="right" className="store-drawer-content cart-drawer-content">
          <Drawer.Dialog className="store-drawer-dialog">
            <Drawer.Header className="store-drawer-header">
              <Drawer.Heading className="text-lg font-semibold">Your cart</Drawer.Heading>
              <Drawer.CloseTrigger aria-label="Close cart" className="store-close">
                <X className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
              </Drawer.CloseTrigger>
            </Drawer.Header>
            <Drawer.Body className="store-drawer-body cart-drawer-body">
              {notice ? <p role={notice.tone === "error" ? "alert" : "status"} className="cart-notice" data-tone={notice.tone}>{notice.text}</p> : null}
              {loading ? <p role="status" className="text-muted">Loading your cart…</p> :
                snapshot.status === "error" ? (
                  <div role="alert" className="cart-notice" data-tone="error">
                    <p>{snapshot.message}</p>
                    <Button variant="secondary" onPress={() => void reload()}>Try again</Button>
                  </div>
                ) : !cart || cart.lines.length === 0 ? (
                  <p className="text-muted">Your cart is empty.</p>
                ) : (
                  <>
                    <CartLines lines={cart.lines} onNavigate={() => setOpen(false)} />
                    <p className="cart-subtotal"><span>Subtotal</span><strong>{formatPrice(cart.subtotal)}</strong></p>
                  </>
                )}
              <Link href="/cart" className="store-cta cart-view-link" onClick={() => setOpen(false)}>View cart</Link>
            </Drawer.Body>
          </Drawer.Dialog>
        </Drawer.Content>
      </Drawer.Backdrop>
    </Drawer>
  );
}
