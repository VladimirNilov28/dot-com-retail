"use client";

import { createContext, useContext, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { checkoutIdentity, identityChanged } from "@/lib/checkout/identity";
import type { CheckoutIdentity } from "@/lib/checkout/model";

const Identity = createContext<CheckoutIdentity | null>(null);
export function useCheckoutIdentity() {
  const owner = useContext(Identity);
  if (!owner) throw new Error("Verified checkout identity is required");
  return owner;
}

export function CheckoutIdentityBoundary({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const [owner, setOwner] = useState<CheckoutIdentity | null>(null);
  const [checking, setChecking] = useState(true);
  const active = useRef<CheckoutIdentity | null>(null);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const url = new URL(window.location.href);
    const transient = ["payment_intent_client_secret", "payment_intent", "redirect_status"];
    if (transient.some((name) => url.searchParams.has(name))) {
      for (const name of transient) url.searchParams.delete(name);
      window.history.replaceState(null, "", url.pathname + url.search);
    }
    let controller: AbortController | undefined;
    let generation = 0;
    function invalidate() {
      ++generation; controller?.abort();
      active.current = null;
      setChecking(true);
      setOwner(null); setError("Your checkout session changed. Check your current identity before continuing.");
    }
    async function refresh() {
      controller?.abort();
      const current = ++generation;
      controller = new AbortController();
      setError("");
      try {
        const value = await checkoutIdentity(controller.signal);
        if (current === generation) {
          active.current = value;
          setOwner((previous) => previous?.scope === value.scope ? previous : value);
          setChecking(false);
        }
      } catch {
        if (current === generation) {
          active.current = null; setOwner(null);
          setError("Checkout identity could not be verified. Your saved requests have not been erased.");
        }
      }
    }
    function visibility() {
      if (document.hidden) { ++generation; controller?.abort(); setChecking(true); }
      else void refresh();
    }
    function restored() { setChecking(true); void refresh(); }
    const channel = new BroadcastChannel(identityChanged);
    channel.onmessage = (event) => {
      if (event.data?.scope && event.data.scope === active.current?.scope) return;
      invalidate();
      if (event.data?.scope) void refresh();
    };
    window.addEventListener(identityChanged, invalidate);
    window.addEventListener("focus", refresh);
    window.addEventListener("pageshow", restored);
    document.addEventListener("visibilitychange", visibility);
    invalidate();
    void refresh();
    return () => {
      ++generation; controller?.abort(); channel.close();
      window.removeEventListener(identityChanged, invalidate);
      window.removeEventListener("focus", refresh);
      window.removeEventListener("pageshow", restored);
      document.removeEventListener("visibilitychange", visibility);
    };
  }, [pathname, revision]);
  if (!owner) return <section className="checkout-section" role="status">
    <p>{error || "Verifying your checkout session…"}</p>
    {error ? <button className="store-link" onClick={() => setRevision((value) => value + 1)}>Check checkout session</button> : null}
  </section>;
  return <Identity.Provider key={owner.scope} value={owner}>
    {checking ? <p role="status">Verifying your checkout session…</p> : null}
    <div hidden={checking}>{children}</div>
  </Identity.Provider>;
}
