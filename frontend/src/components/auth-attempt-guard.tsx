"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { authenticationPath } from "@/lib/auth/navigation";

const changed = "bytecore.auth-attempt";
type Attempt = { flow: string; csrf: string };
let registeredAttempt: Attempt | null = null;

async function requestAttempt(action: "cancel" | "check-attempt", attempt: Attempt, signal?: AbortSignal) {
  const response = await fetch(`/auth/${action}`, {
    method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
    headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: new URLSearchParams({ flow: attempt.flow, _csrf: attempt.csrf }), signal,
  });
  if (!response.ok) throw new Error("Authentication attempt is not active");
}

export function AuthNavigationLifecycle() {
  const pathname = usePathname();
  const active = useRef<Attempt | null>(null);
  useEffect(() => {
    active.current = registeredAttempt;
    function registered(event: Event) {
      if (event instanceof CustomEvent) active.current = event.detail;
    }
    window.addEventListener(changed, registered);
    return () => window.removeEventListener(changed, registered);
  }, []);
  useEffect(() => {
    if (authenticationPath(pathname) || !active.current) return;
    const attempt = active.current;
    active.current = null;
    registeredAttempt = null;
    void requestAttempt("cancel", attempt, AbortSignal.timeout(8000)).catch(() => {
      console.error("Abandoned authentication cleanup could not be confirmed; fresh sign-in will revoke the old attempt");
    });
  }, [pathname]);
  return null;
}

export function AuthAttemptGuard({ flow, csrf, children }: Attempt & { children: React.ReactNode }) {
  const [active, setActive] = useState(true);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let controller: AbortController | undefined;
    let generation = 0;
    const attempt = { flow, csrf };
    registeredAttempt = attempt;
    window.dispatchEvent(new CustomEvent(changed, { detail: attempt }));
    async function check() {
      const current = ++generation;
      controller?.abort();
      controller = new AbortController();
      setActive(false);
      try {
        await requestAttempt("check-attempt", attempt, controller.signal);
        if (current === generation) { setFailed(false); setActive(true); }
      } catch {
        if (current === generation) { setFailed(true); setActive(false); }
      }
    }
    async function navigate(event: MouseEvent) {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return;
      const link = event.target instanceof Element ? event.target.closest("a[href]") : null;
      if (!(link instanceof HTMLAnchorElement) || link.target || link.download) return;
      const destination = new URL(link.href);
      if (destination.origin !== location.origin || authenticationPath(destination.pathname) ||
          destination.pathname === location.pathname && destination.search === location.search) return;
      event.preventDefault(); event.stopPropagation();
      setActive(false);
      ++generation; controller?.abort();
      try {
        await requestAttempt("cancel", attempt, AbortSignal.timeout(8000));
        window.location.assign(destination.href);
      } catch { setFailed(true); }
    }
    void check();
    window.addEventListener("pageshow", check);
    document.addEventListener("click", navigate, true);
    return () => {
      ++generation; controller?.abort();
      window.removeEventListener("pageshow", check);
      document.removeEventListener("click", navigate, true);
    };
  }, [flow, csrf]);
  return <>
    {!active ? <p role="status">{failed ? "This sign-in attempt is no longer available. Start a new sign-in." : "Checking your sign-in attempt…" }
      {failed ? <>
        {/* eslint-disable-next-line @next/next/no-html-link-for-pages -- auth start requires native navigation, never RSC or prefetch */}
        <a href="/auth/start?returnTo=%2F" className="store-link">Start a new sign-in</a>
      </> : null}</p> : null}
    <div hidden={!active}>{children}</div>
  </>;
}
