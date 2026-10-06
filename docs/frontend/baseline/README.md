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
| **Owner-approved baseline** | The owner explicitly approved this appearance. Treat as the reference. Never overwrite it with an uninspected or known-broken capture. **No file in this directory currently holds this status.** |
| **Model-reviewed — awaiting owner approval** | Captured and visually inspected by the implementer; no owner sign-off. Usable as evidence, not as an authority. |
| **Owner-rejected composition — before-evidence only** | The owner explicitly turned this layout down. The image still matches what `dev` renders, so it is retained as before-evidence for the correction, but it is **never** a consistency standard and must not be cited to argue a new design is inconsistent. |
| **Stale** | Known not to match current `dev`. The reason is recorded in the entry. Must not be presented as current. |

Passing tests, or the mere existence of a capture, **never** establishes owner
approval. **Approval is not inherited.** When an approved capture is replaced
because the UI changed, the replacement starts at *Model-reviewed*, even under
the same filename and the same owning ticket.

### Provenance — three different kinds of image, do not conflate them

| Kind | Where | Authority |
|---|---|---|
| **Reference images** | [`docs/references/`](../../references/) — arvutitark, iDeal, 1a | Third-party pattern brief curated by the owner. Never a baseline, no approval status, not ByteCore, not a before-state. |
| **Owner-approved baselines** | This directory, status *Owner-approved baseline* | The standard a change is judged against. Currently **none** — see below. |
| **Model-reviewed work** | This directory, status *Model-reviewed* or *Owner-rejected* | Evidence produced by an implementer. Proves what was rendered, not that it was accepted. |

## Owner review outcome — 2026-10-06

The owner **rejected the catalog composition** captured here: category
navigation reduced to a row of filter-like buttons, unrelated products shown
together as the default browsing entry, oversized image placeholders, a long
narrow category dropdown, and a Search control that offers no input.

Consequences recorded in this manifest:

- The `issue-63-*` catalog and category captures and the `issue-61-*` catalog
  popover and drawer captures are **Owner-rejected composition**.
- The remaining `issue-61-*` captures stay *Model-reviewed*. They were not
  themselves rejected, but they are invalidated by the planned header search
  input and the home replacement.
- The **conventions** these captures happen to demonstrate and that the owner
  did approve at `aef4673` — the compact one-row header, the wordmark as home
  link, a single accent catalog entry, restrained borders over filled panels,
  honest "not available yet" copy, the dark theme, Lucide, and the code-rendered
  image placeholder policy — **remain in force**. Rejecting the composition does
  not reopen them. [`../DESIGN.md`](../DESIGN.md) §9 draws that line explicitly.
- Error, not-found, empty and parent-empty captures record **behaviour the owner
  did not reject**. Their semantics survive; only their layout is superseded, so
  they are recaptured rather than discarded.

Nothing here is promoted or demoted by a model. Only the owner approves.

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

Source revision for every entry below: **`5ffceb0b6d21437a778491422cf4eeeec665325f`**
on `dev` (#63 listing and refreshed #61 shell). Shared capture settings:
Chromium 153.0.8010.12 (Playwright 1.63),
`deviceScaleFactor: 1`, `colorScheme: "light"` — deliberate, since the
storefront is fixed-dark via `data-theme="dark"`, so a light OS preference
proves the theme is not preference-driven. `prefers-reduced-motion` unset.

**No entry below is an owner-approved baseline.** The previous #61 captures were
owner-approved at `aef4673`; changed shell/category content replaced every file,
and approval is not inherited by a replacement. Seventeen entries are now
**Owner-rejected composition** following the 2026-10-06 review above — they
remain accurate before-evidence of what `dev` renders and must not be used as a
standard. The former
`issue-61-catalog-desktop-1440-unavailable.png` is removed as superseded.

**Data prerequisites:** an isolated PostgreSQL/Flyway database, current Spring
backend and real Hive, with the verification-only fixtures below created through
supported `createCategory`, `createProduct`, `createProductVariant` and
`updateProductVariant` mutations. A setup-only dev/admin credential stays outside
the storefront/browser and public cache. No owner database was seeded or changed.
Names and counts in these captures are isolated test data, not a production
catalog or production fallback. The owner's live endpoint had no products/categories.

| File | Owns | Route | Viewport | State | Covers | Status | Last updated by |
|---|---|---|---|---|---|---|---|
| `issue-61-home-desktop-1440-default.png` | #61 | `/` | 1440×900, full page | Default | Header, PageContainer, corrected home/footer availability copy | Model-reviewed — awaiting owner approval | #63 |
| `issue-61-home-mobile-390-default.png` | #61 | `/` | 390×844, full page | Default | Mobile shell, stacked home, footer | Model-reviewed — awaiting owner approval | #63 |
| `issue-61-home-desktop-1440-catalog-popover-open.png` | #61 | `/` | 1440×900 | Catalog popover open | Nonmodal popover, genuine root/child links | Owner-rejected composition — before-evidence only | #63 |
| `issue-61-home-desktop-1440-account-popover-open.png` | #61 | `/` | 1440×900 | Account popover open | Existing account overlay with refreshed home/footer | Model-reviewed — awaiting owner approval | #63 |
| `issue-61-home-mobile-390-drawer-open.png` | #61 | `/` | 390×844 | Drawer open | Modal drawer, genuine category hierarchy, close control | Owner-rejected composition — before-evidence only | #63 |
| `issue-61-home-narrow-320-default.png` | #61 | `/` | 320×640 | Default | Narrow header/Search accessible-name behavior, corrected copy | Model-reviewed — awaiting owner approval | #63 |
| `issue-63-catalog-desktop-1440-default.png` | #63 | `/catalog` | 1440×900, full page | Grid | Categories, count, four-column cards, exact prices, pagination/footer | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-catalog-mobile-390-default.png` | #63 | `/catalog` | 390×844, full page | Grid | Two-column cards, wrapped toolbar/categories, pagination/footer | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-desktop-1440-default.png` | #63 | `/catalog/laptops` | 1440×900, full page | Grid | Ancestor breadcrumbs, sibling links, 20 of 23 products | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-mobile-390-default.png` | #63 | `/catalog/laptops` | 390×844, full page | Grid | Mobile names/price alignment and category context | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-desktop-1440-list.png` | #63 | `/catalog/laptops?page=2&view=list` | 1440×900, full page | List | URL-backed selected view, three remaining items, previous/next | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-mobile-390-list.png` | #63 | `/catalog/laptops?page=2&view=list` | 390×844, full page | List | Compact image/text rows, long names, unknown stock | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-desktop-1440-loading.png` | #63 | `/catalog/laptops` → page 2 | 1440×900 | Loading grid | Retained category context, matching grid skeleton | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-catalog-desktop-1440-list-loading.png` | #63 | `/catalog?view=list` → page 2 | 1440×900 | Loading list | Retained context, matching list skeleton | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-desktop-1440-empty.png` | #63 | `/catalog/monitors` | 1440×900, full page | Empty | Zero result count, alternate categories, all-products recovery | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-mobile-390-empty.png` | #63 | `/catalog/monitors` | 390×844, full page | Empty | Mobile empty state and wrapping | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-desktop-1440-parent-empty.png` | #63 | `/catalog/gaming` | 1440×900, full page | Parent empty | Honest direct-membership scope and child destination | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-mobile-390-parent-empty.png` | #63 | `/catalog/gaming` | 390×844, full page | Parent empty | Mobile scope explanation and child link | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-desktop-1440-error.png` | #63 | `/catalog/laptops?page=777` | 1440×900, full page | Controlled error | Safe localized GraphQL failure, working explicit Retry | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-desktop-1440-not-found.png` | #63 | `/catalog/missing-category` | 1440×900, full page | HTTP 404 | Category-specific dark not-found, catalog recovery/noindex | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-mobile-390-not-found.png` | #63 | `/catalog/missing-category` | 390×844, full page | HTTP 404 | Mobile missing-category recovery/footer | Owner-rejected composition — before-evidence only | #63 |

No unexpected console, runtime or hydration errors occurred. Controlled
HTTP-200 GraphQL failures and the deliberate missing-category HTTP 404 are expected.

## Planned invalidation — which correction step supersedes which capture

Required reading before starting any step in [`../DESIGN.md`](../DESIGN.md) §9.8.
Each step recaptures the rows it invalidates and updates them in the **same
commit**. A ticket ID marks ownership, not exclusive coverage: step 1 and step 4
both change the shell and therefore touch every capture in the set.

| Capture | Invalidated by (§9.8 step) | What changes | Semantics retained? |
|---|---|---|---|
| `issue-61-home-desktop-1440-catalog-popover-open.png` | **1** grouped navigation | The rejected narrow single-column list becomes a wide grouped panel | Nonmodal popover semantics retained |
| `issue-61-home-mobile-390-drawer-open.png` | **1** grouped navigation | Flat category list becomes grouped sections in the drawer | Modal drawer semantics retained |
| `issue-63-catalog-desktop-1440-default.png` | **2** catalog discovery | `/catalog` stops being a flat all-products grid | Replaced; recapture as a discovery capture |
| `issue-63-catalog-mobile-390-default.png` | **2** catalog discovery | As above, mobile | Replaced |
| `issue-63-catalog-desktop-1440-list-loading.png` | **2**, **3** | The all-products listing moves to the shared results surface; skeleton must match the new layout | Loading-state coverage retained at the new route |
| `issue-63-category-desktop-1440-default.png` | **3** sidebar + compact results | Button row replaced by the sidebar; placeholder reduced; three columns | Replaced |
| `issue-63-category-mobile-390-default.png` | **3** | Filter/category drawer replaces the wrapped button row | Replaced; add a drawer-open capture |
| `issue-63-category-desktop-1440-list.png` | **3** | List view inside the new two-region layout | View-toggle + pagination coverage retained |
| `issue-63-category-mobile-390-list.png` | **3** | As above, mobile | Retained |
| `issue-63-category-desktop-1440-loading.png` | **3** | Skeleton must match the sidebar layout | Retained |
| `issue-63-category-desktop-1440-empty.png` | **3** | Layout only | **Yes** — zero-count copy and recovery links were not rejected |
| `issue-63-category-mobile-390-empty.png` | **3** | Layout only | **Yes** |
| `issue-63-category-desktop-1440-parent-empty.png` | **3** | Layout only | **Yes** — the honest direct-membership explanation is still required |
| `issue-63-category-mobile-390-parent-empty.png` | **3** | Layout only | **Yes** |
| `issue-63-category-desktop-1440-error.png` | **3** | Layout only | **Yes** — controlled failure + working Retry still required |
| `issue-63-category-desktop-1440-not-found.png` | **3** (shell), **4** (header) | Shell/header only | **Yes** — HTTP 404 + noindex behaviour unchanged |
| `issue-63-category-mobile-390-not-found.png` | **3**, **4** | Shell/header only | **Yes** |
| `issue-61-home-desktop-1440-default.png` | **4** header search input, **7** home replacement | Header gains a real field; the holding home composition is replaced outright | Replaced |
| `issue-61-home-mobile-390-default.png` | **4**, **7** | As above, mobile | Replaced |
| `issue-61-home-narrow-320-default.png` | **4** | The <22rem Search-label rule and the <16rem stacking rule must be re-proven against a real input | Narrow-width coverage retained |
| `issue-61-home-desktop-1440-account-popover-open.png` | **4**, **7** | Header geometry and the page behind it change | Account overlay semantics retained |

### Captures the correction should add

New states worth preserving once the owning step lands — keep the set small, and
add an image only where it proves something the rows above do not:

| Planned file | Step | Proves |
|---|---|---|
| `issue-61-home-desktop-1440-catalog-popover-open.png` *(replaced in place)* | 1 | Grouped multi-column navigation, headings as links, empty space left empty |
| `issue-63-catalog-desktop-1440-default.png` *(replaced in place)* | 2 | Category discovery with an explicit, secondary "All products" |
| `issue-63-category-desktop-1440-default.png` *(replaced in place)* | 3 | Sidebar with in-context category navigation beside a compact product area |
| `issue-63-category-mobile-390-filters-open.png` *(new)* | 3 | The mobile filter/navigation drawer with its active-filter count |
| `issue-64-search-desktop-1440-default.png` *(new)* | 5 | The shared results surface under a search URL, with real facets |
| `issue-64-search-desktop-1440-suggestions-open.png` *(new)* | 6 | Suggestion popup built only from `name`/`slug`, with keyboard selection |
| `issue-86-home-desktop-1440-default.png` *(new)* | 7 | Category entry points and accurately labelled discovery sections |

Captures still owned by #61 and #63 keep those IDs when refreshed; the
refreshing ticket goes in *Last updated by*.

### Isolated fixture recipe

Create roots in this order: Computers (`computers`), Accessories (`accessories`),
Monitors (`monitors`), Audio (`audio`), Gaming (`gaming`); attach Laptops
(`laptops`) and Desktop PCs (`desktops`) to Computers, Keyboards (`keyboards`)
to Accessories, Components (`components`) to Gaming. Nine categories total.

Create `laptop-1` through `laptop-23`, all direct Laptops members. At zero-based
index `i`, cycle names NovaBook 14, Atlas 15 Creator Laptop, Pulse 13 Everyday
Laptop, Vector 16 Workstation, TerraBook Pro 14, Arc 15 Performance Laptop.
For `i >= 6`, append ` — Edition ${floor(i/6)+1}`. Active minimum price is
`499 + 35*i` EUR, formatted `.00`; when `i % 3 === 0`, add an active variant
at `649 + 35*i`. Otherwise one active variant. This produces the 23-item
pagination and differing-price evidence.

| Additional product/slug | Direct membership | Variants |
|---|---|---|
| Nova Compact Desktop / `compact-desktop` | Desktop PCs | Active 799.00 |
| QuietKey Mechanical Keyboard / `quietkey` | Keyboards | Two active 79.90 (equal-price case) |
| Travel USB-C Adapter / `usb-c-adapter` | Accessories | Active 24.95 |
| Modular Computer Kit / `computer-kit` | Computers | None |
| Upcoming Portable Display / `portable-display` | None | None |
| Precision Game Controller / `game-controller` | Components | Active 49.95 |
| Retired Studio Laptop / `retired-laptop` | None | 9.99, then `updateProductVariant` to inactive |

Thirty products total. Monitors/Audio are empty; Gaming has only child products.
Computers has one direct no-variant product. SKUs must be unique; setup mutation
authorization is separate from completely anonymous storefront verification.
Never run this recipe on the owner's database.

### #63 reproduction and controlled states

Captures used the production build, isolated Hive `localhost:4063`, a
verification-only forwarding bridge at `localhost:4064`, and frontend
`localhost:3164`. The bridge forwards normal requests to real Hive and counts
operation names; `/control?mode=delay` delays only CatalogListing by 2500ms,
`mode=error` returns a selected-operation HTTP-200 GraphQL INTERNAL error,
`mode=categories-error` does so only for CatalogCategories, and `mode=normal`
restores real forwarding. It returns `{ mode, counts }`. This bridge/fixture
setup is temporary verification tooling, not deployed application code.

Use a **fresh frontend origin/cache identity** for each controlled-state run.
Rebuilding alone need not clear Next's persisted public data cache. A warm valid
success can legitimately bypass the injected delay/failure; do not disable
production caching to make a test pass. Category-outage/build recovery was also
verified independently with a cold origin and a build made during that outage:
error-rendered routes stayed dynamic and recovered after normal service returned.

```bash
cd frontend
HIVE_GRAPHQL_URL=http://localhost:4064/graphql \
  STOREFRONT_ORIGIN=http://localhost:3164 bun run build
HIVE_GRAPHQL_URL=http://localhost:4064/graphql \
  STOREFRONT_ORIGIN=http://localhost:3164 bun start --hostname 127.0.0.1 --port 3164
# In another terminal, after supplying the isolated fixtures/control bridge:
PLAYWRIGHT_MODULE=/tmp/bytecore-pw/node_modules/playwright/index.mjs \
  FRONTEND_URL=http://localhost:3164 CATALOG_TEST_CONTROL=http://localhost:4064/control \
  BROWSER_ARTIFACTS=/tmp/issue63-shots node scripts/catalog-smoke.mjs
```

The script captures after content is ready, resets scroll/focus for layout
evidence, and captures loading while navigation is pending. Error evidence uses
an uncached out-of-range page so an existing valid cache cannot conceal failure;
Retry recovers and redirects to page 2. Shell captures use the same populated
taxonomy: visit `/` at the recorded viewport and open Catalog/Account/Menu.
Real 200% Chromium zoom, keyboard focus, history/reload, category failure,
inactive-only/no-variant prices and malformed URLs were additionally verified;
the retained set is representative, not every assertion's own screenshot.

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
