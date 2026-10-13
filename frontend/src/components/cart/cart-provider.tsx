"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { requestGuest } from "@/lib/graphql/browser";
import { guestCartAdd, guestCartMerge, guestCartRemove, guestCartUpdate, guestCartView } from "@/lib/graphql/operations";
import { loadCart, mutateUserCart, type CartMutation, type UserCartResult } from "@/lib/cart/actions";
import { cartErrorMessage, toCartView, type CartMessage, type CartSnapshot, type CartView } from "@/lib/cart/model";

type Mode = "unknown" | "guest" | "user";
type Outcome = { ok: boolean; message: CartMessage };

type CartContextValue = {
  loading: boolean;
  mode: Mode;
  snapshot: CartSnapshot;
  pending: boolean;
  notice: CartMessage | null;
  add(variantId: string, quantity: number, label: string): Promise<Outcome>;
  update(id: string, quantity: number): Promise<Outcome>;
  remove(id: string): Promise<Outcome>;
  reload(): Promise<void>;
  dismiss(): void;
};

const CartContext = createContext<CartContextValue | null>(null);
const MERGE_KEY = "bytecore.cart.merge-request";

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) throw new Error("useCart requires CartProvider");
  return value;
}

function mergeRequestId(): string {
  try {
    const existing = sessionStorage.getItem(MERGE_KEY);
    if (existing) return existing;
    const created = crypto.randomUUID();
    sessionStorage.setItem(MERGE_KEY, created);
    return created;
  } catch {
    return crypto.randomUUID();
  }
}

const clearMergeRequest = () => { try { sessionStorage.removeItem(MERGE_KEY); } catch { /* storage unavailable */ } };

export function CartProvider({ children }: { children: React.ReactNode }) {
  const [mode, setMode] = useState<Mode>("unknown");
  const [snapshot, setSnapshot] = useState<CartSnapshot>({ status: "ok", cart: null });
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState<CartMessage | null>(null);
  const busy = useRef(false);

  const applyUser = useCallback((result: Extract<UserCartResult, { mode: "user" }>) => {
    setMode("user");
    setSnapshot(result.snapshot);
    return result.snapshot;
  }, []);

  const readGuest = useCallback(async (): Promise<CartSnapshot> => {
    const result = await requestGuest(guestCartView, {});
    if (result.status !== "success") {
      // An expired or consumed guest credential just means there is no cart.
      if (result.status === "failure" && result.error.codes?.includes("GUEST_CART_UNAVAILABLE")) return { status: "ok", cart: null };
      return { status: "error", message: cartErrorMessage(result.status === "failure" ? result.error.kind : "graphql") };
    }
    return { status: "ok", cart: result.data.guestCart ? toCartView(result.data.guestCart) : null };
  }, []);

  const reload = useCallback(async () => {
    const user = await loadCart().catch(() => null);
    if (user === null) { setSnapshot({ status: "error", message: cartErrorMessage("unavailable") }); setLoading(false); return; }
    if (user.mode === "user") {
      applyUser(user);
      // A guest cart that survived sign-in is merged all-or-nothing; conflicts leave it untouched.
      const guest = await readGuest();
      if (guest.status === "ok" && guest.cart && guest.cart.lines.length > 0) {
        const merge = await requestGuest(guestCartMerge, { requestId: mergeRequestId() as never });
        if (merge.status === "success" && merge.data.mergeGuestCart.status !== "BLOCKED") {
          clearMergeRequest();
          const merged = await loadCart().catch(() => null);
          if (merged?.mode === "user") applyUser(merged);
          setNotice({ tone: "success", text: "Your guest cart was added to your account cart." });
        } else if (merge.status === "success") {
          clearMergeRequest();
          const first = merge.data.mergeGuestCart.conflicts[0];
          setNotice({ tone: "error", text: `Your guest cart could not be merged${first ? `: ${first.message}` : ""}. Nothing was changed; your guest items are still saved.` });
        }
      }
    } else {
      setMode("guest");
      setSnapshot(await readGuest());
    }
    setLoading(false);
  }, [applyUser, readGuest]);

  useEffect(() => {
    // Initial hydration of server-owned cart state.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void reload();
  }, [reload]);

  const run = useCallback(async (mutation: CartMutation, success: string): Promise<Outcome> => {
    if (busy.current) return { ok: false, message: { tone: "info", text: "Please wait for the previous cart change to finish." } };
    busy.current = true;
    setPending(true);
    try {
      let outcome: Outcome;
      if (mode === "user") {
        const result = await mutateUserCart(mutation);
        if (result.mode === "user") {
          applyUser(result);
          outcome = result.error ? { ok: false, message: { tone: "error", text: result.error } } : { ok: true, message: { tone: "success", text: success } };
        } else {
          outcome = { ok: false, message: { tone: "error", text: cartErrorMessage("unauthenticated") } };
        }
      } else {
        const result = mutation.kind === "add" ?
          await requestGuest(guestCartAdd, { input: { productVariantId: mutation.variantId, quantity: mutation.quantity } }) :
          mutation.kind === "update" ?
            await requestGuest(guestCartUpdate, { id: mutation.id, input: { quantity: mutation.quantity } }) :
            await requestGuest(guestCartRemove, { id: mutation.id });
        if (result.status === "success") {
          const data = result.data as Record<string, Parameters<typeof toCartView>[0]>;
          const cart = Object.values(data)[0];
          setSnapshot({ status: "ok", cart: toCartView(cart) });
          setMode("guest");
          outcome = { ok: true, message: { tone: "success", text: success } };
        } else {
          // Re-read so the visible cart reflects what the server actually holds.
          setSnapshot(await readGuest());
          outcome = { ok: false, message: { tone: "error", text: cartErrorMessage(result.status === "failure" ? result.error.kind : "graphql") } };
        }
      }
      setNotice(outcome.ok ? null : outcome.message);
      return outcome;
    } finally {
      busy.current = false;
      setPending(false);
    }
  }, [applyUser, mode, readGuest]);

  const value = useMemo<CartContextValue>(() => ({
    loading, mode, snapshot, pending, notice, reload,
    add: (variantId, quantity, label) => run({ kind: "add", variantId, quantity }, `${label} added to your cart.`),
    update: (id, quantity) => run({ kind: "update", id, quantity }, "Cart updated."),
    remove: (id) => run({ kind: "remove", id }, "Item removed."),
    dismiss: () => setNotice(null),
  }), [loading, mode, snapshot, pending, notice, reload, run]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function cartCount(snapshot: CartSnapshot): number {
  return snapshot.status === "ok" && snapshot.cart ? snapshot.cart.count : 0;
}

export type { CartView };
