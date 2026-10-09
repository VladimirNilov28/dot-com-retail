"use client";

import { Button, Drawer, Popover } from "@heroui/react";
import { ChevronDown, Grid2X2, Menu, Search, ShoppingCart, UserRound, X } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { catalogHref, isResultsRoute, type Category } from "@/lib/catalog/model";
import { CatalogRetry } from "./catalog-retry";
import { CategoryNavigation, isPlainClick } from "./category-navigation";
import { useProductSuggestions } from "./search-form";
import { LogoutForm } from "./logout-form";
import { safeReturnTo } from "@/lib/auth/model";

const destinations = [
  { href: "/catalog", label: "Catalog" },
  { href: "/search", label: "Search", icon: Search },
  { href: "/account", label: "Account", icon: UserRound },
  { href: "/cart", label: "Cart", icon: ShoppingCart },
] as const;

const CATALOG = 0;
const ACCOUNT = 2;
const CART = 3;

const DESKTOP_ONLY = ".store-catalog-slot, .store-actions";
// Selector lists cannot take a shared descendant combinator, so spell these out.
const DESKTOP_LINKS = ".store-catalog-slot a, .store-actions a";
const DESKTOP_BUTTONS = ".store-catalog-slot button, .store-actions button";

type Overlay = "mobile" | "catalog" | "account" | "search" | null;
type HeaderAccount = { kind: "checking" | "anonymous" | "expired" | "unavailable" } |
  { kind: "authenticated"; username: string; csrf: string };

export function StoreNavigation({
  cartPreview,
  categories,
  categoryError,
}: {
  cartPreview?: ReactNode;
  categories: Category[];
  categoryError: boolean;
}) {
  const pathname = usePathname();
  const [state, setState] = useState<{ pathname: string; overlay: Overlay }>({
    pathname, overlay: null,
  });
  const mobileTrigger = useRef<HTMLButtonElement>(null);
  const lastFocused = useRef<HTMLElement | null>(null);
  const drawerId = useId();
  const [account, setAccount] = useState<HeaderAccount>({ kind: "checking" });
  const [returnTo, setReturnTo] = useState("/");

  if (state.pathname !== pathname) setState({ pathname, overlay: null });

  useEffect(() => {
    const controller = new AbortController();
    async function updateAccount() {
      const route = ["/login", "/register", "/verify", "/auth/error", "/dev/foundation"].includes(pathname) ? "/account" :
        window.location.pathname + window.location.search;
      try {
        setReturnTo(safeReturnTo(route));
        const response = await fetch("/auth/session", {
          credentials: "same-origin", cache: "no-store", signal: controller.signal, headers: { Accept: "application/json" },
        });
        const body: unknown = await response.json();
        if (!body || typeof body !== "object") throw new TypeError("Invalid account response");
        if ("kind" in body && body.kind === "anonymous" && response.ok) setAccount({ kind: "anonymous" });
        else if ("kind" in body && body.kind === "authenticated" && response.ok &&
          "user" in body && body.user && typeof body.user === "object" && "username" in body.user &&
          typeof body.user.username === "string" && "csrf" in body && typeof body.csrf === "string" &&
          /^[A-Za-z0-9_-]{43}$/.test(body.csrf)) setAccount({ kind: "authenticated", username: body.user.username, csrf: body.csrf });
        else if (response.status === 401) setAccount({ kind: "expired" });
        else if (response.status === 503) setAccount({ kind: "unavailable" });
        else throw new TypeError("Invalid account response");
      } catch (error) {
        if (controller.signal.aborted) return;
        console.error("Account status could not be loaded", error instanceof TypeError ? "protocol" : "unavailable");
        setAccount({ kind: "unavailable" });
      }
    }
    void updateAccount();
    return () => controller.abort();
  }, [pathname]);

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
      const fromDesktop = focused instanceof HTMLElement &&
        focused.closest(`${DESKTOP_ONLY}, .store-popover`) !== null;
      setState({ pathname, overlay: null });
      // Let the overlay restore/unhide the document before moving breakpoint focus.
      frame = requestAnimationFrame(() => {
        if (breakpoint.matches && fromMobile) {
          const href = focused?.getAttribute("href") ?? pathname;
          const link = Array.from(document.querySelectorAll<HTMLAnchorElement>(DESKTOP_LINKS))
            .find((candidate) => candidate.getAttribute("href") === href);
          const search = href === "/search"
            ? document.querySelector<HTMLAnchorElement>(".store-search-slot a")
            : null;
          (link ?? search ?? document.querySelector<HTMLButtonElement>(DESKTOP_BUTTONS))?.focus();
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

  useEffect(() => {
    // The expanded search takes over the header's whole row (§9.6): hide the
    // brand/catalog/account/cart/menu controls from layout and assistive
    // tech while it is open instead of squeezing them, so the only way to
    // overflow at 390px would be the search field itself, which is bounded.
    document.body.classList.toggle("store-search-expanded", state.overlay === "search");
    return () => { document.body.classList.remove("store-search-expanded"); };
  }, [state.overlay]);

  function current(href: string) {
    return pathname === href || pathname.startsWith(`${href}/`);
  }

  function navigationLink(destination: typeof destinations[number], compact = false) {
    const Icon = "icon" in destination ? destination.icon : null;
    return (
      <Link
        key={destination.href}
        href={destination.href}
        className={compact ? "store-action" : "store-nav-link"}
        aria-current={current(destination.href) ? "page" : undefined}
        onClick={(event) => {
          if (isPlainClick(event)) setOverlay(null);
        }}
      >
        {Icon ? <Icon className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" /> : null}
        <span className={compact ? "store-action-label" : "min-w-0"}>{destination.label}</span>
      </Link>
    );
  }

  // "All products" is the one explicit, secondary route into the bounded
  // listing (§9.1): distinct from the grouped category links below it, and
  // no longer aliased to the removed /catalog discovery page.
  function allProductsLink() {
    const href = catalogHref(undefined);
    return (
      <Link
        href={href}
        className="store-nav-link"
        aria-current={current(href) ? "page" : undefined}
        onClick={(event) => {
          if (isPlainClick(event)) setOverlay(null);
        }}
      >
        All products
      </Link>
    );
  }

  function categoryNavigation(variant: "panel" | "drawer") {
    return (
      <CategoryNavigation
        categories={categories}
        variant={variant}
        pathname={pathname}
        onNavigate={() => setOverlay(null)}
      />
    );
  }

  function categoryStatus() {
    if (categoryError) return "Categories could not be loaded.";
    return categories.length ? null : "No categories yet.";
  }

  function desktopPopover(kind: "catalog" | "account") {
    const catalog = kind === "catalog";
    const Icon = catalog ? Grid2X2 : UserRound;
    const destination = destinations[catalog ? CATALOG : ACCOUNT];
    const status = catalog ? categoryStatus() : account.kind === "checking" ? "Checking account..." :
      account.kind === "authenticated" ? `Signed in as ${account.username}.` :
        account.kind === "expired" ? "Your session has expired. Sign in again." :
          account.kind === "unavailable" ? "Account status is unavailable. Try again." : "Sign in to your account.";
    return (
      <Popover isOpen={state.overlay === kind} onOpenChange={(open) => setOverlay(open ? kind : null)}>
        <Button
          variant={catalog ? "primary" : "ghost"}
          className={catalog ? "store-catalog-trigger" : "store-panel-trigger"}
          aria-haspopup="dialog"
          aria-current={current(destination.href) ? "page" : undefined}
        >
          <Icon className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
          {destination.label}
          <ChevronDown className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
        </Button>
        <Popover.Content
          isNonModal
          placement="bottom start"
          className={catalog ? "store-popover store-catalog-popover" : "store-popover"}
        >
          <Popover.Dialog aria-label={catalog ? "Category navigation" : "Account navigation"}>
            {catalog ? null : (
              <Popover.Heading className="font-semibold">Your account</Popover.Heading>
            )}
            {status ? <p className={catalog ? "mb-3 text-sm text-muted" : "my-3 text-sm text-muted"}>{status}</p> : null}
            <div className={catalog ? "store-category-entry" : undefined}>{catalog ? allProductsLink() : navigationLink(destination)}</div>
            {!catalog && account.kind === "authenticated" ? <div className="mt-3"><LogoutForm csrf={account.csrf} /></div> : null}
            {!catalog && (account.kind === "anonymous" || account.kind === "expired") ? <div className="mt-3 grid gap-2">
              <Link prefetch={false} className="store-cta" href={`/login?returnTo=${encodeURIComponent(returnTo)}`} onClick={() => setOverlay(null)}>Sign in</Link>
              <Link prefetch={false} className="store-link" href={`/register?returnTo=${encodeURIComponent(returnTo)}`} onClick={() => setOverlay(null)}>Create an account</Link>
            </div> : null}
            {catalog && categoryNavigation("panel")}
            {catalog && categoryError && <CatalogRetry />}
          </Popover.Dialog>
        </Popover.Content>
      </Popover>
    );
  }

  // The results surface (category pages, "All products", search) already
  // renders its own visible query input (§9.6); showing the header's quick
  // action there too would be a second, duplicate search field.
  const showHeaderSearch = !isResultsRoute(pathname);

  return (
    <nav aria-label="Primary" className="store-navigation">
      <div className="store-catalog-slot">{desktopPopover("catalog")}</div>
      <div className="store-search-slot">
        {showHeaderSearch && (
          <SearchControl
            expanded={state.overlay === "search"}
            onExpand={() => setOverlay("search")}
            onCollapse={() => setOverlay(null)}
          />
        )}
      </div>
      <div className="store-actions">
        {desktopPopover("account")}
        {cartPreview ?? navigationLink(destinations[CART], true)}
      </div>
      <div className="store-mobile-nav">
        <Drawer isOpen={state.overlay === "mobile"} onOpenChange={(open) => setOverlay(open ? "mobile" : null)}>
          <Button ref={mobileTrigger} variant="ghost" className="store-mobile-trigger" aria-haspopup="dialog">
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
                    {destinations.filter((_, index) => index !== CATALOG).map((destination) => navigationLink(destination))}
                  </nav>
                  {categoryStatus()
                    ? <p className="mt-6 mb-2 text-sm text-muted">{categoryStatus()}</p>
                    : null}
                  <div className="store-category-entry">{allProductsLink()}</div>
                  {categoryNavigation("drawer")}
                  {categoryError && <CatalogRetry />}
                </Drawer.Body>
              </Drawer.Dialog>
            </Drawer.Content>
          </Drawer.Backdrop>
        </Drawer>
      </div>
    </nav>
  );
}

/** The header's expandable search (§9.6), used on every storefront page
 * except the results surfaces (which render their own visible input
 * instead). A real `<a href="/search">` is the no-JS fallback: with
 * JavaScript, a plain click intercepts and smoothly expands the same spot
 * into a search field instead of navigating away immediately. Reuses the
 * shared suggestion hook — no second transport, no second search service. */
function SearchControl({ expanded, onExpand, onCollapse }: {
  expanded: boolean; onExpand: () => void; onCollapse: () => void;
}) {
  const router = useRouter();
  const [value, setValue] = useState("");
  const { suggestions, status, open, setOpen, active, setActive, schedule, reset } = useProductSuggestions();
  const triggerRef = useRef<HTMLAnchorElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const inputId = useId();
  const listboxId = useId();

  useEffect(() => {
    if (expanded) inputRef.current?.focus();
  }, [expanded]);

  function collapse() {
    onCollapse();
    reset();
    setValue("");
    // The overlay unmounts this frame; wait one so the trigger is focusable again.
    requestAnimationFrame(() => triggerRef.current?.focus());
  }

  function submit(query: string) {
    const trimmed = query.trim();
    setOpen(false);
    onCollapse();
    // Global header search always searches the whole catalog, with no
    // category/filter state carried over from wherever it was opened.
    router.push(trimmed ? `/search?q=${encodeURIComponent(trimmed)}` : "/search");
  }

  function goToProduct(slug: string) {
    setOpen(false);
    onCollapse();
    router.push(`/products/${encodeURIComponent(slug)}`);
  }

  return (
    <div className="store-search-control">
      <Link
        ref={triggerRef}
        href="/search"
        className="store-action store-search-trigger"
        aria-expanded={expanded}
        onClick={(event) => {
          if (!isPlainClick(event)) return;
          event.preventDefault();
          onExpand();
        }}
      >
        <Search className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
        <span className="store-action-label">Search</span>
      </Link>
      {expanded && (
        <div className="store-search-overlay" role="search">
          <form
            className="store-search-overlay-form"
            onSubmit={(event) => { event.preventDefault(); submit(value); }}
          >
            <Search size={18} strokeWidth={2} aria-hidden="true" focusable="false" className="results-search-icon" />
            <label htmlFor={inputId} className="sr-only">Search the catalog</label>
            <div className="results-search-field">
              <input
                ref={inputRef}
                id={inputId}
                type="search"
                value={value}
                autoComplete="off"
                role="combobox"
                aria-expanded={open && suggestions.length > 0}
                aria-controls={listboxId}
                aria-activedescendant={active >= 0 ? `${listboxId}-${active}` : undefined}
                className="results-search-input"
                placeholder="Search the catalog"
                onChange={(event) => { setValue(event.target.value); schedule(event.target.value); }}
                onFocus={() => { if (suggestions.length) setOpen(true); }}
                onKeyDown={(event) => {
                  if (event.key === "Escape") {
                    event.preventDefault();
                    event.stopPropagation();
                    if (open) { setOpen(false); return; }
                    collapse();
                    return;
                  }
                  if (!open || suggestions.length === 0) return;
                  if (event.key === "ArrowDown") { event.preventDefault(); setActive((index) => (index + 1) % suggestions.length); }
                  else if (event.key === "ArrowUp") { event.preventDefault(); setActive((index) => (index - 1 + suggestions.length) % suggestions.length); }
                  else if (event.key === "Enter" && active >= 0) { event.preventDefault(); goToProduct(suggestions[active].slug); }
                }}
              />
              {open && (
                <ul id={listboxId} role="listbox" aria-label="Search suggestions" className="results-suggestions">
                  {status === "error" && <li className="results-suggestion-status">Suggestions unavailable</li>}
                  {status !== "error" && suggestions.length === 0 && <li className="results-suggestion-status">No suggestions</li>}
                  {suggestions.map((suggestion, index) => (
                    <li key={suggestion.productId} id={`${listboxId}-${index}`} role="option" aria-selected={index === active}
                      className={index === active ? "results-suggestion results-suggestion-active" : "results-suggestion"}>
                      <button type="button" className="results-suggestion-button" onClick={() => goToProduct(suggestion.slug)}>{suggestion.name}</button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <button type="submit" className="store-search-overlay-submit" aria-label="Search">
              <Search className="store-icon" size={18} strokeWidth={2} aria-hidden="true" focusable="false" />
            </button>
            <button type="button" className="store-search-overlay-close" aria-label="Close search" onClick={collapse}>
              <X className="store-icon" size={18} strokeWidth={2} aria-hidden="true" focusable="false" />
            </button>
          </form>
        </div>
      )}
    </div>
  );
}
