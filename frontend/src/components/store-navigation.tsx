"use client";

import { Button, Drawer, Popover } from "@heroui/react";
import { ChevronDown, Grid2X2, Menu, Search, ShoppingCart, UserRound, X } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";

const destinations = [
  { href: "/", label: "Home" },
  { href: "/catalog", label: "Catalog" },
  { href: "/search", label: "Search", icon: Search },
  { href: "/account", label: "Account", icon: UserRound },
  { href: "/cart", label: "Cart", icon: ShoppingCart },
] as const;

type Overlay = "mobile" | "categories" | "account" | null;

export function StoreNavigation({
  quickSearch,
  cartPreview,
}: {
  quickSearch?: ReactNode;
  cartPreview?: ReactNode;
}) {
  const pathname = usePathname();
  const [state, setState] = useState<{ pathname: string; overlay: Overlay }>({
    pathname, overlay: null,
  });
  const mobileTrigger = useRef<HTMLButtonElement>(null);
  const desktop = useRef<HTMLDivElement>(null);
  const lastFocused = useRef<HTMLElement | null>(null);
  const drawerId = useId();

  if (state.pathname !== pathname) setState({ pathname, overlay: null });

  function setOverlay(overlay: Overlay) {
    setState({ pathname, overlay });
  }

  useEffect(() => {
    const breakpoint = window.matchMedia("(min-width: 48rem)");
    let frame = 0;
    function rememberFocus(event: FocusEvent) {
      if (event.target instanceof HTMLElement) lastFocused.current = event.target;
    }
    function onBreakpointChange() {
      const focused = document.activeElement === document.body
        ? lastFocused.current : document.activeElement;
      const fromMobile = focused === mobileTrigger.current || document.getElementById(drawerId)?.contains(focused);
      const fromDesktop = desktop.current?.contains(focused) ||
        (focused instanceof HTMLElement && focused.closest(".store-popover"));
      setState({ pathname, overlay: null });
      // Let the overlay restore/unhide the document before moving breakpoint focus.
      frame = requestAnimationFrame(() => {
        if (breakpoint.matches && fromMobile) {
          const href = focused?.getAttribute("href") ?? pathname;
          const link = Array.from(desktop.current?.querySelectorAll<HTMLAnchorElement>("a") ?? [])
            .find((candidate) => candidate.getAttribute("href") === href);
          const search = href === "/search"
            ? document.querySelector<HTMLAnchorElement>(".store-search-slot a")
            : null;
          (link ?? search ?? desktop.current?.querySelector<HTMLButtonElement>("button"))?.focus();
        } else if (!breakpoint.matches && fromDesktop) {
          mobileTrigger.current?.focus();
        }
      });
    }
    document.addEventListener("focusin", rememberFocus);
    breakpoint.addEventListener("change", onBreakpointChange);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("focusin", rememberFocus);
      breakpoint.removeEventListener("change", onBreakpointChange);
    };
  }, [pathname, drawerId]);

  function current(href: string) {
    return href === "/" ? pathname === href : pathname === href || pathname.startsWith(`${href}/`);
  }

  function navigationLink(destination: typeof destinations[number], prominent = false) {
    const Icon = "icon" in destination ? destination.icon : null;
    return (
      <Link
        key={destination.href}
        href={destination.href}
        className={`store-nav-link${prominent ? " store-search-link" : ""}`}
        aria-current={current(destination.href) ? "page" : undefined}
        onClick={(event) => {
          if (event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
            setOverlay(null);
          }
        }}
      >
        {Icon ? <Icon className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" /> : null}
        <span className="min-w-0">{destination.label}</span>
      </Link>
    );
  }

  function desktopPopover(kind: "categories" | "account") {
    const category = kind === "categories";
    const Icon = category ? Grid2X2 : UserRound;
    return (
      <Popover isOpen={state.overlay === kind} onOpenChange={(open) => setOverlay(open ? kind : null)}>
        <Button variant="ghost" className="store-panel-trigger" aria-haspopup="dialog"
          aria-current={!category && current("/account") ? "page" : undefined}>
          <Icon className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
          {category ? "Categories" : "Account"}
          <ChevronDown className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
        </Button>
        <Popover.Content isNonModal placement="bottom" className="store-popover">
          <Popover.Dialog aria-label={category ? "Category navigation" : "Account navigation"}>
            <Popover.Heading className="font-semibold">{category ? "Explore the catalog" : "Your account"}</Popover.Heading>
            <p className="my-3 text-sm text-muted">
              {category ? "Category links will appear when catalog integration is available." : "Account features are not available yet."}
            </p>
            {navigationLink(destinations[category ? 1 : 3])}
          </Popover.Dialog>
        </Popover.Content>
      </Popover>
    );
  }

  return (
    <nav aria-label="Primary" className="store-navigation">
      <div className="store-search-slot">{quickSearch ?? navigationLink(destinations[2], true)}</div>
      <div ref={desktop} className="store-desktop-nav">
        <div className="store-desktop-top">
          <div className="store-actions">
            {desktopPopover("account")}
            {cartPreview ?? navigationLink(destinations[4])}
          </div>
        </div>
        <div className="store-catalog-row">
          {desktopPopover("categories")}
          {navigationLink(destinations[0])}
          {navigationLink(destinations[1])}
        </div>
      </div>
      <div className="store-mobile-nav">
        <Drawer isOpen={state.overlay === "mobile"} onOpenChange={(open) => setOverlay(open ? "mobile" : null)}>
          <Button ref={mobileTrigger} variant="secondary" className="store-mobile-trigger" aria-haspopup="dialog">
            <Menu className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
            Menu
          </Button>
          <Drawer.Backdrop className="store-drawer-backdrop">
            <Drawer.Content placement="left" className="store-drawer-content">
              <Drawer.Dialog id={drawerId} className="store-drawer-dialog">
                <Drawer.Header className="store-drawer-header">
                  <Drawer.Heading className="text-lg font-semibold">ByteCore navigation</Drawer.Heading>
                  <Drawer.CloseTrigger aria-label="Close navigation" className="store-close">
                    <X className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
                  </Drawer.CloseTrigger>
                </Drawer.Header>
                <Drawer.Body className="store-drawer-body">
                  <nav aria-label="Primary" className="store-mobile-panel">
                    {destinations.map((destination) => navigationLink(destination, destination.href === "/search"))}
                  </nav>
                  <p className="mt-6 text-sm text-muted">Browse the catalog to discover electronics. Category links are not available yet.</p>
                </Drawer.Body>
              </Drawer.Dialog>
            </Drawer.Content>
          </Drawer.Backdrop>
        </Drawer>
      </div>
    </nav>
  );
}
