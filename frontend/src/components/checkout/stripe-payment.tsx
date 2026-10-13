"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@heroui/react";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe } from "@stripe/stripe-js";
import { requestCustomer, requestGuest } from "@/lib/graphql/browser";
import { customerPayment, guestPayment, type PaymentSession } from "@/lib/graphql/checkout-operations";
import type { UUID } from "@/lib/graphql/scalars";
import { confirmationHref } from "@/lib/checkout/model";

function HostedPayment({ requestId, mode, onUpdated }: { requestId: UUID; mode: "guest" | "user"; onUpdated(): void }) {
  const stripe = useStripe(), elements = useElements();
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [complete, setComplete] = useState(false);
  return <form className="checkout-payment" onSubmit={async (event) => {
    event.preventDefault();
    if (!stripe || !elements || busy.current) return;
    busy.current = true; setPending(true); setError("");
    try {
      const result = await stripe.confirmPayment({
        elements, confirmParams: { return_url: new URL(confirmationHref(requestId, mode), window.location.origin).href },
        redirect: "if_required",
      });
      if (result.error) setError(result.error.message ?? "Payment could not complete. Check your payment details or try another test card.");
      else onUpdated();
    } catch {
      setError("The payment provider could not be reached. Check this order's status before retrying the same payment.");
    } finally {
      busy.current = false; setPending(false);
    }
  }}>
    <PaymentElement onChange={(event) => setComplete(event.complete)} options={{ layout: "tabs" }} />
    {error ? <p role="alert" className="cart-line-issue">{error}</p> : null}
    <Button type="submit" isDisabled={!stripe || !elements || !complete || pending} variant="primary">
      {pending ? "Confirming payment…" : "Pay securely"}
    </Button>
    <p className="text-sm text-muted">Stripe sandbox — use test payment methods only. Card details go directly to Stripe.</p>
  </form>;
}

function HostedElements({ session, requestId, mode, onUpdated }: { session: PaymentSession; requestId: UUID; mode: "guest" | "user"; onUpdated(): void }) {
  const stripe = useMemo(() => loadStripe(session.publishableKey), [session.publishableKey]);
  if (!session.clientSecret) return null;
  return <Elements stripe={stripe} options={{ clientSecret: session.clientSecret, appearance: { theme: "night" } }}>
    <HostedPayment requestId={requestId} mode={mode} onUpdated={onUpdated} />
  </Elements>;
}

export function StripePayment({ requestId, mode, onUpdated }: { requestId: UUID; mode: "guest" | "user"; onUpdated(): void }) {
  const [session, setSession] = useState<PaymentSession | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let reads = 0;
    async function read() {
      const options = { signal: controller.signal };
      const result = mode === "guest" ? await requestGuest(guestPayment, { requestId }, options) :
        await requestCustomer(customerPayment, { requestId }, options);
      if (controller.signal.aborted) return;
      if (result.status !== "success") {
        setError("Payment is temporarily unavailable. Your order is retained; refresh to retry safely.");
        return;
      }
      const value = "guestPaymentSession" in result.data ? result.data.guestPaymentSession : result.data.checkoutPaymentSession;
      setSession(value); setError("");
      if (value.status === "QUEUED" && ++reads < 12) timer = setTimeout(() => void read(), 2000);
    }
    void read();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [requestId, mode, revision]);
  const refresh = () => setRevision((value) => value + 1);
  const failure = session?.failureCode === "insufficient_funds" ? "The test card has insufficient funds. Use another test payment method." :
    session?.failureCode ? "The payment was declined. Check your details or use another test payment method." : "";
  return <section className="checkout-section" aria-label="Secure payment">
    <h2 className="text-lg font-semibold">Payment</h2>
    {error ? <p role="alert" className="cart-line-issue">{error}</p> : null}
    {!session && !error ? <p role="status" className="text-muted">Loading secure payment…</p> : null}
    {session?.status === "NOT_CONFIGURED" ? <p role="alert">Stripe sandbox is not configured. Your pending order has not been paid.</p> : null}
    {session?.status === "QUEUED" ? <p role="status">Preparing your payment. Your order is pending; refresh if preparation takes longer.</p> : null}
    {failure ? <p role="alert" className="cart-line-issue">{failure} Retrying uses this same order and payment.</p> : null}
    {session?.clientSecret ? <HostedElements key={session.clientSecret} session={session} requestId={requestId} mode={mode} onUpdated={onUpdated} /> : null}
    {session && !session.clientSecret && !["QUEUED", "NOT_CONFIGURED"].includes(session.status) ?
      <p role="status">{session.status === "succeeded" || session.status === "SUCCEEDED" ? "Stripe received the payment. Waiting for the store's verified order update." :
        session.status === "canceled" || session.status === "FAILED" ? "This payment is cancelled; it cannot be retried." :
          "Payment is processing or requires a status check. Do not place another order."}</p> : null}
    {!session?.clientSecret ? <Button variant="secondary" onPress={refresh}>Refresh payment status</Button> : null}
  </section>;
}
