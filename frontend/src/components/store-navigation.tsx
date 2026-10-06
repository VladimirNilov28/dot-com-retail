"use client";

import { Button } from "@heroui/react";
import { Menu, Search, ShoppingCart, UserRound, X } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";

const destinations = [
  { href: "/", label: "Home" },
  { href: "/catalog", label: "Catalog" },
  { href: "/search", label: "Search", icon: Search },
  { href: "/account", label: "Account", icon: UserRound },
  { href: "/cart", label: "Cart", icon: ShoppingCart },
] as const;

export function StoreNavigation() {
  const pathname = usePathname();
  const [menu, setMenu] = useState({ pathname, isOpen: false });
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLElement>(null);
  const desktop = useRef<HTMLElement>(null);
  const navigation = useRef<HTMLDivElement>(null);
  const lastFocused = useRef<HTMLElement | null>(null);
  const panelId = useId();

  if (menu.pathname !== pathname) {
    setMenu({ pathname, isOpen: false });
  }

  useEffect(() => {
    const breakpoint = window.matchMedia("(min-width: 48rem)");
    function rememberFocus(event: FocusEvent) {
      lastFocused.current =
        event.target instanceof HTMLElement && navigation.current?.contains(event.target)
          ? event.target
          : null;
    }
    function clearOutsideFocus(event: PointerEvent) {
      if (event.target instanceof Node && !navigation.current?.contains(event.target)) {
        lastFocused.current = null;
      }
    }
    function onBreakpointChange() {
      // Hiding a focused control can move focus to body before this event runs.
      const focused =
        document.activeElement === document.body
          ? lastFocused.current
          : document.activeElement;
      if (breakpoint.matches) {
        if (panel.current?.contains(focused) || focused === trigger.current) {
          const href = focused?.getAttribute("href");
          const links = desktop.current?.querySelectorAll<HTMLAnchorElement>("a");
          const destination = Array.from(links ?? []).find(
            (link) => link.getAttribute("href") === (href ?? pathname),
          );
          destination?.focus();
        }
        setMenu({ pathname, isOpen: false });
      } else if (desktop.current?.contains(focused)) {
        trigger.current?.focus();
      }
    }
    document.addEventListener("focusin", rememberFocus);
    document.addEventListener("pointerdown", clearOutsideFocus);
    breakpoint.addEventListener("change", onBreakpointChange);
    return () => {
      document.removeEventListener("focusin", rememberFocus);
      document.removeEventListener("pointerdown", clearOutsideFocus);
      breakpoint.removeEventListener("change", onBreakpointChange);
    };
  }, [pathname]);

  function closeMenu() {
    trigger.current?.focus();
    setMenu({ pathname, isOpen: false });
  }

  function links(isMobile: boolean) {
    return destinations.map((destination) => {
      const { href, label } = destination;
      const Icon = "icon" in destination ? destination.icon : null;
      const isCurrent =
        href === "/"
          ? pathname === href
          : pathname === href || pathname.startsWith(`${href}/`);
      return (
        <li key={href}>
          <Link
            href={href}
            className="store-nav-link"
            aria-current={isCurrent ? "page" : undefined}
            onClick={
              isMobile
                ? (event) => {
                    if (
                      event.button === 0 &&
                      !event.metaKey &&
                      !event.ctrlKey &&
                      !event.shiftKey &&
                      !event.altKey
                    ) {
                      closeMenu();
                    }
                  }
                : undefined
            }
          >
            {Icon ? (
              <Icon
                className="store-icon"
                size={20}
                strokeWidth={2}
                aria-hidden="true"
                focusable="false"
              />
            ) : null}
            <span className="min-w-0">{label}</span>
          </Link>
        </li>
      );
    });
  }

  return (
    <div
      ref={navigation}
      className="store-navigation"
      onKeyDown={(event) => {
        if (event.key === "Escape" && menu.isOpen) {
          event.preventDefault();
          event.stopPropagation();
          closeMenu();
        }
      }}
    >
      <nav ref={desktop} aria-label="Primary" className="store-desktop-nav">
        <ul className="flex flex-wrap items-center justify-end gap-1">
          {links(false)}
        </ul>
      </nav>
      <div className="store-mobile-nav">
        <div className="flex justify-end">
          <Button
            ref={trigger}
            variant="secondary"
            aria-expanded={menu.isOpen}
            aria-controls={panelId}
            onPress={() => {
              if (menu.isOpen) closeMenu();
              else setMenu({ pathname, isOpen: true });
            }}
          >
            {menu.isOpen ? (
              <X className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
            ) : (
              <Menu className="store-icon" size={20} strokeWidth={2} aria-hidden="true" focusable="false" />
            )}
            Menu
          </Button>
        </div>
        <nav
          ref={panel}
          id={panelId}
          aria-label="Primary"
          hidden={!menu.isOpen}
          className="store-mobile-panel"
        >
          <ul className="grid gap-1">{links(true)}</ul>
        </nav>
      </div>
    </div>
  );
}
