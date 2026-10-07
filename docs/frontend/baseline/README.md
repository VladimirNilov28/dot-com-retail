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
| **Owner-approved baseline** | The owner explicitly approved this appearance. Treat as the reference. Never overwrite it with an uninspected or known-broken capture. **One file holds this status** — see "Owner approval — desktop megamenu, 2026-10-07" below; every other file is Model-reviewed or Owner-rejected. |
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
| **Owner-approved baselines** | This directory, status *Owner-approved baseline* | The standard a change is judged against. `issue-61-home-desktop-1440-catalog-popover-open.png` holds this status, scoped to the desktop open-menu state only — see "Owner approval — desktop megamenu, 2026-10-07" below. |
| **Model-reviewed work** | This directory, status *Model-reviewed* or *Owner-rejected* | Evidence produced by an implementer. Proves what was rendered, not that it was accepted. |

## Owner review outcome — 2026-10-06

The owner **rejected the catalog composition** captured here: category
navigation reduced to a row of filter-like buttons, unrelated products shown
together as the default browsing entry, oversized image placeholders, a long
narrow category dropdown, and a Search control that offers no input.

Consequences recorded in this manifest:

- The `issue-63-*` catalog and category captures and the `issue-61-*` catalog
  popover and drawer captures were **Owner-rejected composition**. The two
  `issue-61-*` ones have since been **superseded by §9.8 step 1** (grouped
  catalog navigation) and are now *Model-reviewed — awaiting owner approval*;
  the rejected images remain in git history at `1d9477c`. The seventeen
  `issue-63-*` rows are still rejected before-evidence.
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
on `dev` (#63 listing and refreshed #61 shell), **except** the three captures
refreshed by §9.8 step 1 (grouped catalog navigation) and the files refreshed by
the subsequent megamenu-only discovery correction (owner reversal of the
standalone `/catalog` discovery page) — see each subsection below.
Shared capture settings:
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
`issue-61-catalog-desktop-1440-unavailable.png` is removed as superseded, as are
`issue-63-catalog-desktop-1440-default.png` and `issue-63-catalog-mobile-390-default.png`
(the standalone `/catalog` discovery page the owner reversed before review —
see "Megamenu-only discovery correction" below).

**Data prerequisites:** an isolated PostgreSQL/Flyway database, current Spring
backend and real Hive, with the verification-only fixtures below created through
supported `createCategory`, `createProduct`, `createProductVariant` and
`updateProductVariant` mutations. A setup-only dev/admin credential stays outside
the storefront/browser and public cache. No owner database was seeded or changed.
Names and counts in these captures are isolated test data, not a production
catalog or production fallback. The owner's live endpoint had no products/categories.

### Step 1 refresh — grouped catalog navigation (#61)

Three captures were replaced in place on top of the working tree that implements
§9.8 step 1, local commits on `dev` after
`ebd726c5d8a204d2161253d3f0bc2d2b032bd990`:

| File | Why refreshed |
|---|---|
| `issue-61-home-desktop-1440-catalog-popover-open.png` | The rejected narrow single-column list is replaced by the grouped multi-column panel |
| `issue-61-home-mobile-390-drawer-open.png` | The flat category list is replaced by grouped roots with separate link and expand controls |
| `issue-61-home-desktop-1440-account-popover-open.png` | Shared header trigger changed; refreshed to stay a truthful capture of the same revision |

`issue-61-home-desktop-1440-default.png`, `issue-61-home-mobile-390-default.png`
and `issue-61-home-narrow-320-default.png` were recaptured and verified
**byte-identical**, so they were deliberately not churned.

**Data source for these three captures only:** the local development stack
(Postgres + Spring backend + Hive on `:4002`) with its development taxonomy of
22 categories, 10 roots, maximum depth 2, Estonian names. This is development
data, **not** the nine-category fixture recipe used by the rows above and not a
production catalog. It was read, never modified.

Larger and degraded trees could not be represented in those live captures, so
they were verified separately against an **isolated verification-only GraphQL
stub** (150 categories: 6 roots × 6 children × 3 grandchildren, with deliberately
long labels) plus `small`, `empty`, `error` and transport-`down` modes. That stub
exists only for verification; it is not in the repository and never served the
owner's catalog. Its results are reported on #61, not retained as images.

**No owner visual approval** is claimed for any of these three files. They are
*Model-reviewed — awaiting owner approval*. The rejected predecessors remain in
git history at `1d9477c` as before-evidence.

### Step 2 refresh — catalog root discovery (#63) — superseded, see below

This subsection recorded the now-reversed standalone `/catalog` discovery page
(local commits `748cb01`…`248a2ff`). The owner's later decision moved category
discovery into the header megamenu exclusively and turned `/catalog` into a
compatibility redirect — see **"Megamenu-only discovery correction"** below for
the current state. Kept for history; do not treat the bullets in this
subsection as current:

| File | Why refreshed (historical, no longer applicable) |
|---|---|
| ~~`issue-63-catalog-desktop-1440-default.png`~~ | Removed — the standalone discovery page it depicted no longer exists |
| ~~`issue-63-catalog-mobile-390-default.png`~~ | Removed — as above, mobile |
| `issue-63-catalog-desktop-1440-list-loading.png` | Still applies; recaptured again below for the breadcrumb change |

**Data source:** the same local development stack as the step 1 refresh above
(Postgres + Spring backend + Hive on `:4002`, 22 categories/10 roots, maximum
depth 2, Estonian names) — development data, read-only, not the nine-category
fixture recipe and not a production catalog. The real taxonomy has no
grandchildren, so it cannot exercise the `<details>` disclosure; that was
verified separately against an isolated verification-only GraphQL stub (not
committed, not served to the owner) with deep (6×4×3, long labels), large
(40×10), empty and error category trees, reported on #63 rather than retained
as images. The list-loading capture's transient skeleton state was produced by
delaying the browser's navigation request to `/catalog/all-products` in
Playwright (`page.route`) — a verification-only technique, not a backend or
application change, and distinct from the fixture stack's `CATALOG_TEST_CONTROL`
`delay` mode used by `catalog-smoke.mjs`'s own (not run this session, see below)
captures.

**Verification gap, reported honestly:** `catalog-smoke.mjs` was updated for
the new route contract (breadcrumb → discovery, All products CTA, legacy/invalid
URL redirect, relocated list-loading capture) but could not be *run* end-to-end
this session, because it requires the dedicated isolated Postgres/Spring/Hive
stack seeded with the documented nine-category/thirty-product fixture recipe
(§ below), which was not standing up in this environment and was not rebuilt.
The same route-split behavior it covers was independently verified with a
standalone Playwright script against both the live development stack and the
categories-only stub described above (all passed; see the #63 report for the
full scenario list). The fixture-specific assertions that script makes
(exact prices, pagination counts) are unchanged code paths and are covered by
unit tests (`model.test.mjs`) and the unmodified `CatalogPageContent`/
`CatalogResults` components it reuses, not by a fresh end-to-end run.

**No owner visual approval** is claimed for any of these three files. They are
*Model-reviewed — awaiting owner approval*.

### Megamenu-only discovery correction — `/catalog` becomes a compatibility redirect (#61, #63)

The owner rejected the standalone `/catalog` discovery page above before ever
reviewing it: category/subcategory discovery now lives **only** in the header's
catalog megamenu (#61), refined this round with an explicit "All products"
entry inside the panel/drawer itself. `/catalog` is now a thin compatibility
route — see [`../DESIGN.md`](../DESIGN.md) §9.3.

Consequences for this manifest:

- `issue-63-catalog-desktop-1440-default.png` and
  `issue-63-catalog-mobile-390-default.png` are **removed**, not merely marked
  stale. They captured a composition that no longer exists at any route and
  was never shown to the owner, so unlike the owner-rejected `issue-63-category-*`
  rows, they carry no before-evidence value worth retaining — the git history
  at `748cb01`/`98c3ee6` still has them if ever needed.
- `issue-61-home-desktop-1440-catalog-popover-open.png` and
  `issue-61-home-mobile-390-drawer-open.png` are **replaced in place**: the
  panel/drawer composition is otherwise unchanged from the step 1 grouped-navigation
  correction, but both now show the dedicated "All products" entry above the
  grouped categories, where the previous captures showed only the grouped
  panel/drawer without it.
- `issue-61-home-desktop-1440-account-popover-open.png` and the three
  `issue-61-home-*-default.png` rows were re-captured and diffed **byte-identical**
  to what is already in the manifest, so they were deliberately not churned.
- `issue-63-catalog-desktop-1440-list-loading.png` is **replaced in place**: the
  route (`/catalog/all-products`) and loading-skeleton coverage are unchanged,
  but the breadcrumb's "Catalog" crumb is now plain text instead of a link
  (§9.3), which is a real, if small, visible difference at that capture's clip.

**Data source:** the same local development stack as the step 1/step 2
refreshes above (Postgres + Spring backend + Hive on `:4002`) — development
data, read-only. No isolated fixture stack was stood up this round (same
verification gap as the step 2 refresh above); `catalog-smoke.mjs` was updated
for the new breadcrumb/redirect assertions but not run end-to-end. The
equivalent behavior — megamenu "All products" entry, no literal "Catalog" link
inside the dialog/drawer, breadcrumb crumb non-linked, `/catalog` redirects —
was independently verified with a standalone Playwright script against the
live development stack (all checks passed, zero console/runtime/hydration
errors) and with the full `storefront-smoke.mjs` suite (11 scenarios passed,
including real Chromium 200% zoom and JavaScript-disabled SSR).

**No owner visual approval** is claimed for any of these files. They remain
*Model-reviewed — awaiting owner approval*.

### Owner approval — desktop megamenu, 2026-10-07

The owner approved the desktop megamenu composition shown in the attachment
`image(20261007-202618).png` on 2026-10-07: "compact wide panel, grouped
columns, prominent main categories and quieter children." That description
matches `issue-61-home-desktop-1440-catalog-popover-open.png` exactly — the
only desktop open-menu capture in this manifest, showing the wide panel, a
dedicated "All products" entry, bold root-category headings as the prominent
elements and lighter-weight child links beneath them, with empty space left
empty rather than filled with filler panels.

This file's status changes to **Owner-approved baseline**, scoped **only** to
that desktop open-menu state. This does **not** extend to:

- `issue-61-home-mobile-390-drawer-open.png` (mobile drawer) — still
  *Model-reviewed*;
- any `issue-63-*`/`issue-64-*` results/search capture — still
  *Model-reviewed*;
- any future homepage capture — not yet built.

Per the owner's instruction, the approved menu composition itself is
**preserved unchanged** in this pass — no redesign of `catalog-sidebar.tsx`'s
desktop megamenu or `store-navigation.tsx` was made; only the shared
results/search surface described below was added underneath it.

### #63 shared results + #64 search/facets pass (this round)

Builds the compact shared results surface (desktop sidebar, mobile drawer,
compact cards, sorting, bounded pagination) used by category pages and
`/catalog/all-products` (#63), and the real query input, URL-backed
query/filter/sort/page state and debounced suggestions on `/search` sharing
that same surface (#64). The approved megamenu above is unchanged; this round
only concerns the page content beneath it.

Consequences for this manifest:

- `issue-63-category-desktop-1440-default.png` and
  `issue-63-category-mobile-390-default.png` are **replaced in place**: the
  owner-rejected button-row composition is gone, superseding their
  *Owner-rejected composition* status with a new *Model-reviewed* sidebar
  (desktop) / drawer-trigger (mobile) composition, per the planned invalidation
  recorded below. They still own #63; "Last updated by" becomes `#63, #64`.
- `issue-63-catalog-desktop-1440-list-loading.png` is **replaced in place**
  again: same route and loading-skeleton intent, now showing the sidebar
  layout's skeleton instead of the previous button-row page's skeleton.
- `issue-63-category-mobile-390-filters-open.png` is **new**: the mobile
  categories-and-filters drawer, open, showing the current category
  highlighted, the up-link, sibling categories and the real `minPrice`/
  `maxPrice` + attribute facets.
- `issue-64-search-desktop-1440-default.png` and
  `issue-64-search-desktop-1440-suggestions-open.png` are **new**: `/search`
  rendering the exact same sidebar/grid/toolbar components as category pages,
  with a real visible query input, and the debounced name/slug-only suggestion
  popup open with keyboard-navigable results.
- `issue-63-category-desktop-1440-empty.png` and
  `issue-63-category-mobile-390-empty.png` are **replaced in place** with a
  genuine empty category from the live dev dataset (`/catalog/tarkvara`, a
  leaf category with 0 direct products) — the old route (`/catalog/monitorid`-
  equivalent) no longer has zero products in the current dev data either, so
  this recapture fixes two problems (sidebar layout, stale product count) at
  once.
- `issue-63-category-desktop-1440-list.png`, `issue-63-category-mobile-390-list.png`,
  `issue-63-category-desktop-1440-loading.png`, `issue-63-category-desktop-1440-parent-empty.png`,
  `issue-63-category-mobile-390-parent-empty.png` and
  `issue-63-category-desktop-1440-error.png` are **honestly left stale this
  round, not falsely marked unchanged**: `CatalogPageContent` is one shared
  component, so the sidebar now wraps every one of these states too — they do
  **not** show the current composition. They were not recaptured because the
  live dev dataset cannot currently reproduce what they need: every live
  category now fits on one page (≤20 items, so no real second-page list/loading
  state), no live parent category has zero direct products while still having
  children (every parent in the current dev data carries its own direct
  products alongside its children), and an out-of-range page number now
  redirects to a valid page instead of surfacing a backend error. The original
  captures for these four states most likely came from the isolated fixture
  recipe below (`laptops`/`monitors`/`gaming`, which doesn't exist in live dev
  data), the same stack this manifest has repeatedly reported as unavailable
  here. They need the fixture stack (or a live dataset with >20 products in one
  category, a parent-without-direct-products, and a reproducible backend
  failure) to recapture honestly — fabricating them from the current dataset
  was deliberately avoided.
- `issue-63-category-desktop-1440-not-found.png` and
  `issue-63-category-mobile-390-not-found.png` are **genuinely unaffected**:
  `catalog/not-found.tsx` is a standalone page with no sidebar, untouched by
  this change — confirmed by reading its source, not assumed.

**Data source:** the same local development stack as prior rounds (Postgres +
Spring backend + Hive on `:4002`) — development data, read-only. The isolated
fixture stack required by `catalog-smoke.mjs` was not available this round
either (same documented gap); the script was statically updated for the new
sidebar/drawer DOM but not executed end-to-end. The equivalent behavior —
sidebar/drawer category and filter navigation, search submission, suggestions,
URL-state restoration on reload/back-forward, missing-category 404, rapid
pagination without debouncing, drawer dismissal and focus restoration — was
independently verified with a standalone Playwright script against the live
development stack (all checks passed, zero unexpected console/runtime/
hydration errors; the one logged console entry is the deliberate
missing-category 404 resource response) and with the full
`storefront-smoke.mjs` suite (11 scenarios passed, including real Chromium
200% zoom and JavaScript-disabled SSR).

**No owner visual approval** is claimed for any of the five #63/#64 files
above. They remain *Model-reviewed — awaiting owner approval*.

| File | Owns | Route | Viewport | State | Covers | Status | Last updated by |
|---|---|---|---|---|---|---|---|
| `issue-61-home-desktop-1440-default.png` | #61 | `/` | 1440×900, full page | Default | Header, PageContainer, corrected home/footer availability copy | Model-reviewed — awaiting owner approval | #63 |
| `issue-61-home-mobile-390-default.png` | #61 | `/` | 390×844, full page | Default | Mobile shell, stacked home, footer | Model-reviewed — awaiting owner approval | #63 |
| `issue-61-home-desktop-1440-catalog-popover-open.png` | #61 | `/` | 1440×900 | Catalog popover open | Nonmodal popover, grouped multi-column panel with a dedicated "All products" entry above the groups, root headings that are themselves links, children beneath them, empty space left empty | **Owner-approved baseline** (2026-10-07, desktop open-menu state only — see note above) | #61, #63 |
| `issue-61-home-desktop-1440-account-popover-open.png` | #61 | `/` | 1440×900 | Account popover open | Existing account overlay, unchanged by the grouped-navigation correction except the shared header trigger | Model-reviewed — awaiting owner approval | #61 |
| `issue-61-home-mobile-390-drawer-open.png` | #61 | `/` | 390×844 | Drawer open | Modal drawer with a dedicated "All products" entry above the grouped roots, each root with separate link and expand controls, all roots visible without scrolling | Model-reviewed — awaiting owner approval | #61, #63 |
| `issue-61-home-narrow-320-default.png` | #61 | `/` | 320×640 | Default | Narrow header/Search accessible-name behavior, corrected copy | Model-reviewed — awaiting owner approval | #63 |
| `issue-63-category-desktop-1440-default.png` | #63 | `/catalog/sulearvutid` | 1440×900, full page | Grid | Sidebar with current-category highlight, up-link, related categories, real min/max + attribute facets; compact three-column grid with reduced placeholder | Model-reviewed — awaiting owner approval | #63, #64 |
| `issue-63-category-mobile-390-default.png` | #63 | `/catalog/sulearvutid` | 390×844, full page | Grid | Mobile toolbar wrapping, "Categories & filters" drawer trigger replacing the rejected button row | Model-reviewed — awaiting owner approval | #63, #64 |
| `issue-63-category-mobile-390-filters-open.png` | #63 | `/catalog/sulearvutid` | 390×844 | Filters drawer open | Accessible modal drawer: current category highlighted, up-link to All categories, sibling categories, min/max price fields, attribute facets | Model-reviewed — awaiting owner approval | #63, #64 |
| `issue-63-category-desktop-1440-list.png` | #63 | `/catalog/laptops?page=2&view=list` | 1440×900, full page | List | URL-backed selected view, three remaining items, previous/next | Stale — the sidebar now wraps this state too; not re-shown. Needs a real category with >20 products or the isolated fixture stack to recapture honestly (current dev data: every category fits on one page) | #63 |
| `issue-63-category-mobile-390-list.png` | #63 | `/catalog/laptops?page=2&view=list` | 390×844, full page | List | Compact image/text rows, long names, unknown stock | Stale — as above | #63 |
| `issue-63-category-desktop-1440-loading.png` | #63 | `/catalog/laptops` → page 2 | 1440×900 | Loading grid | Retained category context, matching grid skeleton | Stale — as above | #63 |
| `issue-63-catalog-desktop-1440-list-loading.png` | #63 | `/catalog/all-products?view=list` → page 2 | 1440×900 | Loading list | "All products" listing at its stable route, sidebar-aware list skeleton matching the new layout | Model-reviewed — awaiting owner approval | #61, #63, #64 |
| `issue-64-search-desktop-1440-default.png` | #64 | `/search?q=Dell` | 1440×900, full page | Query results | Real visible query input, the same sidebar/grid/toolbar components as category pages, result count, sort, facets | Model-reviewed — awaiting owner approval | #64 |
| `issue-64-search-desktop-1440-suggestions-open.png` | #64 | `/search` | 1440×900 | Suggestions open | Debounced name/slug-only suggestion popup with keyboard-navigable results beneath the query input | Model-reviewed — awaiting owner approval | #64 |
| `issue-63-category-desktop-1440-empty.png` | #63 | `/catalog/tarkvara` | 1440×900, full page | Empty | Zero result count, honest "No products yet" copy, all-products recovery, sidebar with the empty category highlighted | Model-reviewed — awaiting owner approval | #63, #64 |
| `issue-63-category-mobile-390-empty.png` | #63 | `/catalog/tarkvara` | 390×844, full page | Empty | Mobile empty state and wrapping, "Categories & filters" drawer trigger | Model-reviewed — awaiting owner approval | #63, #64 |
| `issue-63-category-desktop-1440-parent-empty.png` | #63 | `/catalog/gaming` | 1440×900, full page | Parent empty | Honest direct-membership scope and child destination | Stale — the sidebar now wraps this state too; not re-shown. No live category currently has children while itself having zero direct products, so this needs the isolated fixture stack to recapture honestly | #63 |
| `issue-63-category-mobile-390-parent-empty.png` | #63 | `/catalog/gaming` | 390×844, full page | Parent empty | Mobile scope explanation and child link | Stale — as above | #63 |
| `issue-63-category-desktop-1440-error.png` | #63 | `/catalog/laptops?page=777` | 1440×900, full page | Controlled error | Safe localized GraphQL failure, working explicit Retry | Stale — the sidebar now wraps this state too; not re-shown. An out-of-range page now redirects to a valid page against the live backend instead of erroring, so this needs the isolated fixture stack (or its `CATALOG_TEST_CONTROL` error mode) to recapture honestly | #63 |
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
| `issue-61-home-desktop-1440-catalog-popover-open.png` | **1** grouped navigation | The rejected narrow single-column list becomes a wide grouped panel | Nonmodal popover semantics retained — **done, replaced in place**; refined again (megamenu-only correction) with a dedicated "All products" entry |
| `issue-61-home-mobile-390-drawer-open.png` | **1** grouped navigation | Flat category list becomes grouped sections in the drawer | Modal drawer semantics retained — **done, replaced in place**; refined again (megamenu-only correction) with a dedicated "All products" entry |
| ~~`issue-63-catalog-desktop-1440-default.png`~~ | **2** catalog discovery | `/catalog` stopped being a flat all-products grid, then the owner reversed the standalone discovery page entirely | **Removed** — the composition it showed no longer exists at any route; see "Megamenu-only discovery correction" above |
| ~~`issue-63-catalog-mobile-390-default.png`~~ | **2** catalog discovery | As above, mobile | **Removed** — as above |
| `issue-63-catalog-desktop-1440-list-loading.png` | **2**, **3** | The all-products listing moves to `/catalog/all-products`; skeleton must match the new layout | Loading-state coverage retained — **done, replaced in place again** for the sidebar-aware list skeleton |
| `issue-63-category-desktop-1440-default.png` | **3** sidebar + compact results | Button row replaced by the sidebar; placeholder reduced; three columns | Replaced — **done, replaced in place** |
| `issue-63-category-mobile-390-default.png` | **3** | Filter/category drawer replaces the wrapped button row | Replaced — **done, replaced in place**; drawer-open capture added separately |
| `issue-63-category-desktop-1440-list.png` | **3** | List view inside the new two-region layout | View-toggle + pagination semantics retained, but the capture itself is now **stale** (sidebar not shown) — needs >20 products in one category or the fixture stack to recapture |
| `issue-63-category-mobile-390-list.png` | **3** | As above, mobile | Stale, same reason |
| `issue-63-category-desktop-1440-loading.png` | **3** | Skeleton must match the sidebar layout | Stale, same reason |
| `issue-63-category-desktop-1440-empty.png` | **3** | Layout only | **Done, replaced in place** at `/catalog/tarkvara` (the old route is no longer empty in live dev data either) |
| `issue-63-category-mobile-390-empty.png` | **3** | Layout only | **Done, replaced in place**, same route |
| `issue-63-category-desktop-1440-parent-empty.png` | **3** | Layout only | Honest direct-membership semantics still required, but the capture is **stale** — no live category currently has children with zero direct products; needs the fixture stack |
| `issue-63-category-mobile-390-parent-empty.png` | **3** | Layout only | Stale, same reason |
| `issue-63-category-desktop-1440-error.png` | **3** | Layout only | Stale — an out-of-range page now redirects instead of erroring against the live backend; needs the fixture stack's `CATALOG_TEST_CONTROL` error mode |
| `issue-63-category-desktop-1440-not-found.png` | **3** (shell), **4** (header) | Shell/header only | **Yes** — HTTP 404 + noindex behaviour unchanged; confirmed unaffected by reading `catalog/not-found.tsx` (no sidebar) |
| `issue-63-category-mobile-390-not-found.png` | **3**, **4** | Shell/header only | **Yes**, same confirmation |
| `issue-61-home-desktop-1440-default.png` | **4** header search input, **7** home replacement | Header gains a real field; the holding home composition is replaced outright | Replaced |
| `issue-61-home-mobile-390-default.png` | **4**, **7** | As above, mobile | Replaced |
| `issue-61-home-narrow-320-default.png` | **4** | The <22rem Search-label rule and the <16rem stacking rule must be re-proven against a real input | Narrow-width coverage retained |
| `issue-61-home-desktop-1440-account-popover-open.png` | **4**, **7** | Header geometry and the page behind it change | Account overlay semantics retained |

### Captures the correction should add

New states worth preserving once the owning step lands — keep the set small, and
add an image only where it proves something the rows above do not:

| Planned file | Step | Proves |
|---|---|---|
| `issue-61-home-desktop-1440-catalog-popover-open.png` *(replaced in place)* | 1, megamenu-only correction | Grouped multi-column navigation, headings as links, empty space left empty, dedicated "All products" entry — **done**; subsequently **owner-approved**, 2026-10-07 |
| ~~`issue-63-catalog-desktop-1440-default.png`~~ *(removed)* | 2 (superseded) | Was category discovery at a standalone `/catalog` page; the owner reversed this before review — the megamenu is the sole discovery surface instead |
| `issue-63-category-desktop-1440-default.png` *(replaced in place)* | 3 | Sidebar with in-context category navigation beside a compact product area — **done** |
| `issue-63-category-mobile-390-filters-open.png` *(new)* | 3 | The mobile filter/navigation drawer with its active-filter count — **done** |
| `issue-64-search-desktop-1440-default.png` *(new)* | 5 | The shared results surface under a search URL, with real facets — **done** |
| `issue-64-search-desktop-1440-suggestions-open.png` *(new)* | 6 | Suggestion popup built only from `name`/`slug`, with keyboard selection — **done** |
| `issue-86-home-desktop-1440-default.png` *(new)* | 7 | Category entry points and accurately labelled discovery sections |

Still outstanding from this round (not fabricated; needs either a live-data
change or the isolated fixture stack): a second-page list/loading capture, a
parent-with-children-but-no-direct-products capture, and a controlled
backend-error capture, all under the new sidebar layout — see the "Stale"
entries in the invalidation table above.

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
