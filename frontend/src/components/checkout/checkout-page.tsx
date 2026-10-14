"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Button } from "@heroui/react";
import { CartLines } from "@/components/cart/cart-lines";
import { useCart } from "@/components/cart/cart-provider";
import { formatPrice } from "@/lib/catalog/model";
import { requestGuest } from "@/lib/graphql/browser";
import { shippingOptions } from "@/lib/graphql/operations";
import {
  checkoutConfiguration, checkoutPrefill, customerConfirmation, guestConfirmation,
  customerPlace, guestPlace, customerPreview, guestPreview, type CheckoutOrder, type CheckoutPreview,
} from "@/lib/graphql/checkout-operations";
import type { CheckoutAddressInput, CheckoutInput, CheckoutPrefillQuery } from "@/lib/graphql/generated";
import { uuid } from "@/lib/graphql/scalars";
import { addressFields, checkoutError, clearAttempt, clearLegacyAttempt, readLegacyAttempt, confirmationHref, emptyAddress, readAttempt, requestSelection, saveAttempt, validation, type Attempt } from "@/lib/checkout/model";
import { requestCheckout } from "@/lib/checkout/identity";
import { useCheckoutIdentity } from "./identity-boundary";
import { OrderSummary } from "./order-summary";
import { StripePayment } from "./stripe-payment";

function AddressFields({ prefix, address, countries, errors, change }: {
  prefix: string; address: CheckoutAddressInput; countries: string[]; errors: Record<string, string>;
  change(value: CheckoutAddressInput): void;
}) {
  return <div className="checkout-address-grid">
    {addressFields.map(([key, label, max, autocomplete]) => <label key={key} className={key.startsWith("address") ? "checkout-wide" : ""}>
      <span>{label}{key === "addressLine2" ? " (optional)" : ""}</span>
      <input value={address[key] ?? ""} required={key !== "addressLine2"} maxLength={max}
        autoComplete={`${prefix} ${autocomplete}`} aria-invalid={!!errors[`${prefix}.${key}`]}
        aria-describedby={errors[`${prefix}.${key}`] ? `${prefix}-${key}-error` : undefined}
        onChange={(event) => change({ ...address, [key]: event.target.value })} />
      {errors[`${prefix}.${key}`] ? <small id={`${prefix}-${key}-error`} className="cart-line-issue">{errors[`${prefix}.${key}`]}</small> : null}
    </label>)}
    <label><span>Country</span><select required value={address.countryCode} autoComplete={`${prefix} country`}
      aria-invalid={!!errors[`${prefix}.countryCode`]} onChange={(event) => change({ ...address, countryCode: event.target.value })}>
      <option value="">Select country</option>{countries.map((code) => <option key={code} value={code}>{code}</option>)}
    </select>{errors[`${prefix}.countryCode`] ? <small className="cart-line-issue">{errors[`${prefix}.countryCode`]}</small> : null}</label>
  </div>;
}

const initialInput = (): CheckoutInput => ({
  contactEmail: "", contactPhone: "", shippingMethod: "STANDARD", shippingAddress: emptyAddress(),
  billingAddress: null, billingSameAsShipping: true, paymentSelection: "STRIPE",
});

export function CheckoutPageView() {
  const owner = useCheckoutIdentity();
  const mode = owner.mode;
  const { mode: cartMode, customerId, snapshot, loading, pending: cartPending, reload, notice } = useCart();
  const cartOwned = cartMode === mode && (mode === "guest" || owner.scope === `user:${customerId}`);
  const [recovering, setRecovering] = useState(true);
  const [input, setInput] = useState<CheckoutInput>(initialInput);
  const [shipping, setShipping] = useState<{ method: "STANDARD" | "EXPRESS" | "PICKUP"; charge: Parameters<typeof formatPrice>[0]; estimate: string; supportedCountries: string[] }[]>([]);
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [saved, setSaved] = useState<CheckoutPrefillQuery["me"]["addresses"]>([]);
  const [preview, setPreview] = useState<{ key: string; value: CheckoutPreview } | null>(null);
  const [accepted, setAccepted] = useState<string | null>(null);
  const [quoting, setQuoting] = useState(false);
  const [error, setError] = useState("");
  const [placing, setPlacing] = useState(false);
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [placed, setPlaced] = useState<CheckoutOrder | null>(null);
  const [placedMode, setPlacedMode] = useState<"guest" | "user">("guest");
  const [retryQuote, setRetryQuote] = useState(0);
  const busy = useRef(false);
  const form = useRef<HTMLFormElement>(null);
  const cart = snapshot.status === "ok" ? snapshot.cart : null;
  const countries = [...new Set(shipping.flatMap((option) => option.supportedCountries))];
  const fieldErrors = validation(input, countries);
  const valid = Object.keys(fieldErrors).length === 0;
  const key = JSON.stringify({ input, cart, cartPending });
  const current = preview?.key === key && !cartPending ? preview.value : null;
  const currentAccepted = !!current && accepted === current.quoteVersion;

  useEffect(() => {
    if (!cartOwned) void reload();
  }, [cartOwned, reload]);

  useEffect(() => {
    const controller = new AbortController();
    async function setup() {
      const [methods, config] = await Promise.all([
        requestGuest(shippingOptions, {}, { signal: controller.signal }),
        requestGuest(checkoutConfiguration, {}, { signal: controller.signal }),
      ]);
      if (controller.signal.aborted) return;
      if (methods.status === "success") setShipping(methods.data.checkoutShippingOptions);
      else setError("Shipping options could not be loaded. Refresh before continuing.");
      if (config.status === "success") setConfigured(config.data.checkoutPaymentConfiguration.available);
      else { setConfigured(false); setError("Payment configuration could not be loaded. Refresh before continuing."); }
    }
    void setup();
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (mode !== "user") return;
    const controller = new AbortController();
    async function prefill() {
      const result = await requestCheckout(owner, checkoutPrefill, {}, { signal: controller.signal });
      if (controller.signal.aborted) return;
      if (result.status === "success") {
        setSaved(result.data.me.addresses);
        setInput((value) => {
          const known = result.data.me.addresses.find((a) => /^[A-Z]{2}$/.test(a.country ?? ""));
          return { ...value, contactEmail: value.contactEmail || result.data.me.email,
            ...(known && !value.shippingAddress?.firstName && value.shippingMethod !== "PICKUP" ? {
              shippingAddressId: known.id, shippingAddress: {
                firstName: known.firstName ?? "", lastName: known.lastName ?? "", city: known.city ?? "",
                countryCode: known.country ?? "", postalCode: known.postalCode ?? "",
                addressLine1: known.addressLine1 ?? "", addressLine2: known.addressLine2, phone: known.mobile,
              },
            } : {}),
          };
        });
      } else setError("Known account details could not be loaded. You can enter them manually; sign in again if your order scopes need refreshing.");
    }
    void prefill();
    return () => controller.abort();
  }, [mode, owner]);

  useEffect(() => {
    const controller = new AbortController();
    async function recover() {
      let stored: Attempt | null;
      let legacy = false;
      try {
        stored = readAttempt(owner);
        if (!stored) {
          try { stored = readLegacyAttempt(); }
          catch { console.error("Unscoped legacy checkout recovery is invalid and has been retained without reuse."); }
          legacy = true;
          if (stored?.mode !== owner.mode) stored = null;
        }
      } catch { setError("Saved checkout recovery is unavailable. Do not place another order if a previous submission was uncertain."); return; }
      if (!stored) { setRecovering(false); return; }
      if (!legacy) { setAttempt(stored); setInput(stored.input.checkout); setPlacedMode(stored.mode); }
      const result = stored.mode === "guest" ?
        await requestCheckout(owner, guestConfirmation, { requestId: stored.input.requestId }, { signal: controller.signal }) :
        await requestCheckout(owner, customerConfirmation, { requestId: stored.input.requestId }, { signal: controller.signal });
      if (controller.signal.aborted) return;
      const order = result.status === "success" ? ("guestOrder" in result.data ? result.data.guestOrder : result.data.checkoutOrder) : null;
      if (order) {
        try {
          if (order.status !== "PENDING") {
            clearAttempt(owner, stored.input.requestId);
            if (legacy) clearLegacyAttempt(stored.input.requestId);
            setAttempt(null);
          } else {
            saveAttempt(stored, owner);
            if (legacy) clearLegacyAttempt(stored.input.requestId);
            setAttempt(stored); setInput(stored.input.checkout); setPlacedMode(stored.mode); setPlaced(order);
          }
        } catch { setError("Order state is known, but checkout recovery storage could not be updated. Do not submit a replacement request."); return; }
      } else if (!legacy) setError("A saved placement request was found, but its outcome is not confirmed. Check its status or explicitly retry the same saved request.");
      setRecovering(false);
    }
    void recover();
    return () => controller.abort();
  }, [owner]);

  useEffect(() => {
    if (!valid || !cart?.lines.length || cartPending || placed || attempt || recovering || !cartOwned) return;
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      setQuoting(true);
      const selection = requestSelection(input);
      const result = mode === "guest" ? await requestCheckout(owner, guestPreview, { input: selection }, { signal: controller.signal }) :
        await requestCheckout(owner, customerPreview, { input: selection }, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setQuoting(false);
      if (result.status === "success") {
        const value = "guestCheckoutPreview" in result.data ? result.data.guestCheckoutPreview : result.data.checkoutPreview;
        setPreview({ key, value }); setError("");
      } else { setPreview(null); setError(checkoutError(result.error)); }
    }, 450);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [valid, key, cartPending, placed, attempt, mode, retryQuote, input, cart?.lines.length, owner, recovering, cartOwned]);

  async function place(retry?: Attempt) {
    if (busy.current || recovering || !cartOwned || (!retry && (!current?.placeable || !currentAccepted || !valid || cartPending))) return;
    const next: Attempt = retry ?? { mode: mode === "user" ? "user" : "guest", input: {
      requestId: uuid(crypto.randomUUID()), acceptedQuoteVersion: current!.quoteVersion, checkout: requestSelection(input),
    } };
    try { saveAttempt(next, owner); }
    catch { setError("This browser cannot save the request for safe recovery. Enable session storage before placing an order."); return; }
    busy.current = true; setPlacing(true); setAttempt(next); setError("");
    try {
      const result = next.mode === "guest" ? await requestCheckout(owner, guestPlace, { input: next.input }) :
        await requestCheckout(owner, customerPlace, { input: next.input });
      if (result.status === "success") {
        const order = "createGuestOrder" in result.data ? result.data.createGuestOrder : result.data.createOrder.checkout;
        if (!order) { setError("The order response was incomplete. Recover the same request before proceeding."); return; }
        setPlaced(order); setPlacedMode(next.mode); void reload();
      } else {
        setError(checkoutError(result.error));
        if (result.error.codes?.some((code) => ["CHECKOUT_QUOTE_CHANGED", "CHECKOUT_INVALID", "PAYMENT_NOT_CONFIGURED"].includes(code))) {
          clearAttempt(owner, next.input.requestId); setAttempt(null); setAccepted(null); setPreview(null); setRetryQuote((value) => value + 1);
        }
      }
    } catch {
      setError("The placement response was lost. Check the saved request or retry it; do not place a new order.");
    } finally { busy.current = false; setPlacing(false); }
  }

  if (recovering || !cartOwned) return <p role="status">{error || "Recovering checkout for your verified identity…"}</p>;
  if (placed) return <div className="checkout-page">
    <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Complete your order</h1>
    <p role="status">Order placed · {placed.publicId}. Payment is {placed.payment.status.toLowerCase()}.</p>
    <div className="checkout-layout">
      {placed.status === "PENDING" ? <StripePayment requestId={placed.requestId} mode={placedMode}
        onUpdated={() => window.location.assign(confirmationHref(placed.requestId, placedMode))} /> :
        <section className="checkout-section"><p>This order is {placed.status.toLowerCase()}.</p></section>}
      <OrderSummary order={placed} />
    </div>
    <Link href={confirmationHref(placed.requestId, placedMode)} className="store-link">View authoritative order confirmation</Link>
  </div>;
  return <div className="checkout-page">
    <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Checkout</h1>
    <p className="text-sm text-muted">Guest checkout is welcome. Payment uses Stripe sandbox; no real money.</p>
    {error ? <p role="alert" className="cart-notice" data-tone="error">{error}</p> : null}
    {notice ? <p role="status" className="cart-notice">{notice.text}</p> : null}
    {attempt ? <section className="checkout-section">
      <p>Saved request: {attempt.input.requestId}. Retrying reuses this request and cannot create a second order.</p>
      <Button isDisabled={placing} onPress={() => void place(attempt)}>Retry saved placement</Button>
      <Link className="store-link" href={confirmationHref(attempt.input.requestId, attempt.mode)}>Check order status</Link>
    </section> : loading ? <p role="status" className="checkout-skeleton">Loading your cart…</p> :
      snapshot.status === "error" ? <p role="alert">Your cart could not be loaded. <Button variant="secondary" onPress={() => void reload()}>Retry</Button></p> :
      !cart?.lines.length ? <p>Your cart is empty. <Link className="store-link" href="/catalog/all-products">Continue shopping</Link></p> :
      <div className="checkout-layout">
        <form ref={form} className="checkout-form" onSubmit={(event) => { event.preventDefault(); void place(); }}>
          <fieldset disabled={placing} className="checkout-section">
            <legend className="text-lg font-semibold">Contact information</legend>
            <label><span>Email</span><input type="email" autoComplete="email" required maxLength={255}
              value={input.contactEmail ?? ""} aria-invalid={!!fieldErrors.contactEmail}
              onChange={(event) => setInput({ ...input, contactEmail: event.target.value })} />
              {fieldErrors.contactEmail ? <small className="cart-line-issue">{fieldErrors.contactEmail}</small> : null}</label>
            <label><span>Phone (optional)</span><input type="tel" autoComplete="tel" maxLength={20}
              value={input.contactPhone ?? ""} onChange={(event) => setInput({ ...input, contactPhone: event.target.value })} /></label>
          </fieldset>
          <fieldset disabled={placing} className="checkout-section">
            <legend className="text-lg font-semibold">Shipping</legend>
            {saved.length && input.shippingMethod !== "PICKUP" ? <label><span>Saved shipping address</span><select
              value={input.shippingAddressId ?? ""} onChange={(event) => {
                const address = saved.find((a) => a.id === event.target.value);
                setInput({ ...input, shippingAddressId: address?.id ?? null, shippingAddress: address ? {
                  firstName: address.firstName ?? "", lastName: address.lastName ?? "", city: address.city ?? "",
                  countryCode: /^[A-Z]{2}$/.test(address.country ?? "") ? address.country! : "",
                  postalCode: address.postalCode ?? "", addressLine1: address.addressLine1 ?? "",
                  addressLine2: address.addressLine2, phone: address.mobile,
                } : input.shippingAddress });
              }}><option value="">Enter manually</option>{saved.map((a) => <option key={a.id} value={a.id}>{a.addressLine1}, {a.city}</option>)}</select></label> : null}
            {shipping.length ? <div className="checkout-shipping-options">{shipping.map((option) => <label key={option.method}>
              <input type="radio" name="shipping" checked={input.shippingMethod === option.method} onChange={() => setInput({
                ...input, shippingMethod: option.method, shippingAddressId: null,
                shippingAddress: option.method === "PICKUP" ? null : input.shippingAddress ?? emptyAddress(),
                billingSameAsShipping: option.method === "PICKUP" ? false : input.billingSameAsShipping,
                billingAddress: option.method === "PICKUP" ? input.billingAddress ?? emptyAddress() : input.billingAddress,
              })} /><span><strong>{option.method} · {formatPrice(option.charge)}</strong><small className="text-muted">{option.estimate}</small></span>
            </label>)}</div> : <p role="status">Loading shipping options…</p>}
            <p className="text-sm text-muted">Configured development shipping; not a live carrier or dispatch promise.</p>
            {input.shippingMethod !== "PICKUP" ? <AddressFields prefix="shipping" address={input.shippingAddress ?? emptyAddress()}
              countries={countries} errors={fieldErrors} change={(value) => setInput({ ...input, shippingAddressId: null, shippingAddress: value })} /> : null}
          </fieldset>
          <fieldset disabled={placing} className="checkout-section">
            <legend className="text-lg font-semibold">Billing address</legend>
            {input.shippingMethod !== "PICKUP" ? <label className="checkout-check"><input type="checkbox"
              checked={input.billingSameAsShipping ?? false} onChange={(event) => setInput({ ...input,
                billingSameAsShipping: event.target.checked, billingAddress: event.target.checked ? null : input.billingAddress ?? emptyAddress(),
              })} /><span>Same as shipping</span></label> : null}
            {!input.billingSameAsShipping ? <AddressFields prefix="billing" address={input.billingAddress ?? emptyAddress()}
              countries={countries} errors={fieldErrors} change={(value) => setInput({ ...input, billingAddress: value })} /> : null}
          </fieldset>
          <section className="checkout-section"><h2 className="text-lg font-semibold">Payment selection</h2>
            <p>Card · Stripe sandbox</p><p className="text-sm text-muted">Secure hosted fields appear here after the store places your pending order. No card details enter this form.</p>
            {configured === false ? <p role="alert" className="cart-line-issue">Stripe sandbox is unavailable. Your cart remains saved; order placement is disabled.</p> : null}
          </section>
        </form>
        <aside className="checkout-summary">
          <h2 className="text-lg font-semibold">Your items</h2>
          <CartLines lines={cart.lines} />
          {quoting || (valid && !current) ? <p role="status">Refreshing authoritative totals…</p> : null}
          {current ? <><dl className="checkout-totals">
            <div><dt>Items</dt><dd>{formatPrice(current.totals.merchandiseSubtotal)}</dd></div>
            <div><dt>Shipping</dt><dd>{formatPrice(current.totals.shippingCharge)}</dd></div>
            <div className="checkout-total"><dt>Total</dt><dd>{formatPrice(current.totals.total)}</dd></div>
          </dl>{current.issues.map((issue) => <p role="alert" key={`${issue.code}-${issue.productVariantId}`} className="cart-line-issue">{issue.message}</p>)}
            <label className="checkout-check"><input type="checkbox" checked={currentAccepted} disabled={!current.placeable || cartPending}
              onChange={(event) => setAccepted(event.target.checked ? current.quoteVersion : null)} /><span>I accept this current item and shipping total.</span></label>
          </> : <p className="text-sm text-muted">Complete your contact and address details to calculate shipping and the final total.</p>}
          <Button variant="primary" isDisabled={!valid || !current?.placeable || !currentAccepted || cartPending || placing || !configured}
            onPress={() => { if (form.current?.reportValidity()) void place(); }}>
            {placing ? "Placing order…" : current ? `Place order · ${formatPrice(current.totals.total)}` : "Place order"}
          </Button>
          <p className="text-sm text-muted">Placement reserves stock. Payment follows securely on this same page. No separate tax calculation is provided.</p>
          <Link href="/cart" className="store-link">Back to cart</Link>
        </aside>
      </div>}
  </div>;
}
