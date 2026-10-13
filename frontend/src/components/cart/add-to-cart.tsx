"use client";

import { useId, useState } from "react";
import { Button } from "@heroui/react";
import { maxQuantity, type CartMessage } from "@/lib/cart/model";
import { useCart } from "./cart-provider";

export type AddToCartProps = {
  variantId: string;
  label: string;
  active: boolean;
  availability: "AVAILABLE" | "OUT_OF_STOCK" | null;
  limit: number | null;
};

export function AddToCart({ variantId, label, active, availability, limit }: AddToCartProps) {
  const { add, pending, loading } = useCart();
  const id = useId();
  const [quantity, setQuantity] = useState("1");
  const [message, setMessage] = useState<CartMessage | null>(null);
  const ceiling = limit !== null && limit < 10 ? Math.max(limit, 1) : maxQuantity;
  const parsed = /^[0-9]{1,3}$/.test(quantity) ? Number(quantity) : NaN;
  const invalid = !Number.isInteger(parsed) || parsed < 1 || parsed > ceiling;
  const blocked = !active || availability === "OUT_OF_STOCK";
  const reason = !active ? "This variant is unavailable." : availability === "OUT_OF_STOCK" ? "Out of stock." : null;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (invalid || blocked || pending) return;
    const outcome = await add(variantId, parsed, label);
    setMessage(outcome.message);
    if (outcome.ok) setQuantity("1");
  }

  return (
    <form className="add-to-cart" onSubmit={(event) => void submit(event)} noValidate aria-busy={pending}>
      <div className="add-to-cart-row">
        <div className="add-to-cart-field">
          <label htmlFor={id} className="text-sm text-muted">Quantity</label>
          <input id={id} inputMode="numeric" autoComplete="off" value={quantity} disabled={blocked}
            aria-invalid={invalid && !blocked} aria-describedby={`${id}-help`}
            onChange={(event) => { setQuantity(event.target.value); setMessage(null); }} />
        </div>
        <Button type="submit" variant="primary" isDisabled={blocked || pending || loading || invalid}>
          {pending ? "Adding…" : "Add to cart"}
        </Button>
      </div>
      <p id={`${id}-help`} className="text-sm text-muted">
        {reason ?? (invalid ? `Enter a quantity from 1 to ${ceiling}.` : limit !== null && limit < 10 ? `Only ${limit} available.` : "\u00a0")}
      </p>
      {message ? <p role={message.tone === "error" ? "alert" : "status"} className="cart-notice" data-tone={message.tone}>{message.text}</p> : null}
    </form>
  );
}
