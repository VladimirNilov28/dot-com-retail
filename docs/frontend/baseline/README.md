# Baseline screenshots — manifest

Persistent visual documentation of the storefront, captured from the **running
production build** with Playwright. These are documentation assets: they stay
outside `frontend/public/`, are never served at runtime, and are not
product-image assets.

Read [`../DESIGN.md`](../DESIGN.md) for the design these images record, and its
*Screenshot lifecycle* section for the rules this manifest implements.

Not to be confused with [`docs/references/`](../../references/),
which holds third-party electronics-store captures used as a **pattern brief**.
Those are not ByteCore, are never a baseline, carry no approval status, and do
not follow the naming or recapture rules below — their filenames are the
owner's.

## Status vocabulary

| Status | Meaning |
|---|---|
| **Owner-approved baseline** | The owner explicitly approved this appearance. Treat as the reference. Never overwrite it with an uninspected or known-broken capture. |
| **Model-reviewed — awaiting owner approval** | Captured and visually inspected by the implementer; no owner sign-off. Usable as evidence, not as an authority. |
| **Stale** | Known not to match current `dev`. The reason is recorded in the entry. Must not be presented as current. |

Passing tests, or the mere existence of a capture, **never** establishes owner
approval.

## Naming

`issue-<ticket>-<route>-<viewport>-<state>.png`

- `<ticket>` — the **owning** ticket: the one whose implementation created the
  view. It stays the same when the image is later refreshed; the refreshing
  ticket goes in the *Last updated by* column.
- `<route>` — route slug (`home`, `catalog`, `product`, `cart`, `checkout`).
- `<viewport>` — `desktop-1440`, `mobile-390`, `narrow-320`, …
- `<state>` — `default`, `catalog-popover-open`, `drawer-open`, `loading`,
  `empty`, `error`, `unavailable`, …

Names are **stable**. Replace the file in place. No timestamps, no `-v2`, no
`-final`, no numbered copies.

## Current set

Source revision for every entry below: **`aef4673`** on `dev` (the shell as
corrected in #61). Shared capture settings: Chromium 153 (Playwright 1.63),
`deviceScaleFactor: 1`, `colorScheme: "light"` — deliberate, since the
storefront is fixed-dark via `data-theme="dark"`, so a light OS preference
proves the theme is not preference-driven. `prefers-reduced-motion` unset.

**Data prerequisites: none.** No backend, database or authentication is
required — every route currently renders static or honest-unavailable content.
This changes from #63 onward: catalog, product and cart captures will need
seeded data, and their entries must record exactly what.

| File | Owns | Route | Viewport | State | Covers | Status | Last updated by |
|---|---|---|---|---|---|---|---|
| `issue-61-home-desktop-1440-default.png` | #61 | `/` | 1440×900, full page | Default | `StoreHeader`, `StoreBrand`, `StoreNavigation`, `PageContainer`, `StoreFooter`, temporary home page (#86 replaces it) | Owner-approved baseline | #61 |
| `issue-61-home-mobile-390-default.png` | #61 | `/` | 390×844, full page | Default | Mobile shell (wordmark, Search, Menu), stacked home composition, footer | Owner-approved baseline | #61 |
| `issue-61-home-desktop-1440-catalog-popover-open.png` | #61 | `/` | 1440×900 | Catalog popover open | `StoreNavigation` desktop popover, `.store-popover`, accent catalog trigger | Owner-approved baseline | #61 |
| `issue-61-home-desktop-1440-account-popover-open.png` | #61 | `/` | 1440×900 | Account popover open | `StoreNavigation` ghost trigger + popover, quiet secondary-action treatment | Owner-approved baseline | #61 |
| `issue-61-home-mobile-390-drawer-open.png` | #61 | `/` | 390×844 | Navigation drawer open | `StoreNavigation` mobile `Drawer`, `.store-drawer-*`, `.store-close` | Owner-approved baseline | #61 |
| `issue-61-catalog-desktop-1440-unavailable.png` | #61 | `/catalog` | 1440×900, full page | Unavailable | `UnavailablePage` — the shared not-yet-built route pattern | Owner-approved baseline | #61 |
| `issue-61-home-narrow-320-default.png` | #61 | `/` | 320×640 | Default | Narrow-width header rules: below 22rem the Search label is visually hidden while keeping its accessible name | Owner-approved baseline | #61 |

No console, runtime or hydration errors occurred during capture.

## Capture procedure

Playwright is **not** a project dependency — installing it would change
`package.json`/`bun.lock`. Install it outside the repository:

```bash
mkdir -p /tmp/bytecore-pw && cd /tmp/bytecore-pw
npm install playwright          # browsers cache in ~/.cache/ms-playwright
```

Serve the production build — not `next dev`, so the capture matches what ships:

```bash
cd frontend
bun run build
PORT=3100 bun start
```

Capture into a **temporary** directory first, never straight into this one:

```bash
OUT=/tmp/shots FRONTEND_URL=http://127.0.0.1:3100 node /tmp/bytecore-pw/capture.mjs
```

Use accessible-name selectors so the script does not depend on markup details:

```js
page.getByRole("button", { name: /^Catalog/ })   // catalog popover
page.getByRole("button", { name: /^Account/ })   // account popover
page.getByRole("button", { name: /^Menu/ })      // mobile drawer
page.getByRole("banner")                          // header — never page.locator("header")
```

Use `fullPage: true` for whole-page views and the default viewport clip for
overlay states, so the overlay is not dwarfed by an empty page.

Then **inspect every file**, alongside the working interaction in a real
browser. Only after inspection, copy the accepted files over their existing
names here and update the affected rows above in the same commit. Intermediate,
before and debug captures stay in the temporary directory unless an issue report
genuinely needs them as before/after evidence.

## Before editing UI — consult this manifest

A ticket ID marks **ownership, not exclusive coverage**. Changing the shell, a
shared token or a shared component invalidates captures owned by several
tickets. Check the *Covers* column, recapture every affected route and state,
update those rows, and delete any file the change supersedes.

If a capture cannot be reproduced — missing data, a removed route, a broken
dependency — mark the row **Stale** with the reason. Do not leave it presented
as current.

Backend-only changes need a recapture only when they change visible UI behavior
or invalidate the data a capture depends on.

Keep the set small and representative: add an image only for a genuinely new
state worth preserving, not for every page or every breakpoint.
