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

### Owner approval -- desktop search compositions, 2026-10-08

The owner approved the visible compositions of these original assets:

| Asset | Original asset revision | Approval scope |
|---|---|---|
| `issue-64-search-desktop-1440-suggestions-open.png` | `d7db51a65dd84f388d2f5a262c7c16f56ec3c9a9` | Desktop search page with suggestions open |
| `issue-61-home-desktop-1440-search-expanded.png` | `d7db51a65dd84f388d2f5a262c7c16f56ec3c9a9` | Expanded desktop header search replacing sibling header contents |
| `issue-61-home-desktop-1440-search-suggestions-open.png` | `d7db51a65dd84f388d2f5a262c7c16f56ec3c9a9` | Expanded desktop header search with suggestions open |

All three repository images were opened and matched to the described states.
Original external attachments were not supplied in this request, so no
pixel comparison against those attachments is claimed. The owner explicitly
confirmed recording approval against these inspected repository compositions.
The asset revision identifies the original image bytes, not a newly established
rendered-source/capture timestamp.

This approval does **not** cover mobile, animation, functional verification or
the temporary homepage body. The approved original homepage images show
historical body content and are not current whole-page evidence for #86. Preserve this original
approval record when refreshing them; replacement images start
**Model-reviewed -- awaiting owner approval**, never inherit approval.
The expanded full-width interaction remains intentional: no inline-header
redesign is authorized. The 2026-10-07 desktop megamenu approval and original
asset at `6a30862e20bffa68140d516511f2fb4291617c38` remain unchanged.

Earlier statements below that no capture is approved, or that only the
megamenu is approved, are historical and superseded by this scoped record.
Current retained images must still be read together with their revision and
stale-state notes; owner approval is not a freshness or test-success claim.

| Status | Meaning |
|---|---|
| **Owner-approved baseline** | The owner explicitly approved this appearance and revision. Treat the original scoped assets above as references; current replacement bytes do not inherit approval. Recover original bytes with `git show <revision>:docs/frontend/baseline/<file>`. |
| **Model-reviewed — awaiting owner approval** | Captured and visually inspected by the implementer; no owner sign-off. Usable as evidence, not as an authority. |
| **Owner-rejected composition — before-evidence only** | The owner explicitly turned this layout down. The image records that historical revision, not necessarily current `dev`; it is retained as before-evidence, **never** a consistency standard. |
| **Stale** | Known not to match current `dev`. The reason is recorded in the entry. Must not be presented as current. |

Passing tests, or the mere existence of a capture, **never** establishes owner
approval. **Approval is not inherited.** When an approved capture is replaced
because the UI changed, the replacement starts at *Model-reviewed*, even under
the same filename and the same owning ticket.

### Provenance — three different kinds of image, do not conflate them

| Kind | Where | Authority |
|---|---|---|
| **Reference images** | [`docs/references/`](../../references/) — arvutitark, iDeal, 1a | Third-party pattern brief curated by the owner. Never a baseline, no approval status, not ByteCore, not a before-state. |
| **Owner-approved baselines** | Immutable original revisions recorded above | Scoped standards for the accepted desktop menu/search compositions, not approval of replacement images or the new homepage. |
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

### #86 compact category tiles and copy refinement, 2026-10-08

**Rendered source: `1c7f4cdfae9cb6f7d4742bb130787b83e4cdeb37`, `dev`.**
This supersedes the current-image provenance in the earlier coordinated refresh
below, not its historical verification or original scoped approvals.
**35 current captures were freshly recaptured in place; two native-disclosure
captures were added.** All 37 captures were inspected before retention and are
**Model-reviewed — awaiting owner approval**. The two rejected not-found images
remain unchanged as deliberate historical before-evidence. Total inventory: 39
files, with 37 fresh current captures and two historical rejected captures.
No old image was simply relabelled current. There are no outstanding **Stale**
current rows: homepage-body/footer invalidations are resolved by fresh captures.
The two desktop search images were freshly recaptured byte-identical to their
predecessors; their preserved appearance was not redesigned.

Home now uses compact linked category tiles with decorative, semantic-slug-mapped
Lucide icons, two initially visible direct children and native disclosures for
remaining children. Intact column-flow groups avoid unequal grid-row gaps, with
two columns at 390px and no fixed tile heights. Default live category height
changed **533.9 → 406.8px at 390px (24% shorter)**; the first product section begins at
**y=659.0 rather than 818.1px (159px sooner)**. Desktop category height changed
415.5 → 216.8px. Product sections use taxonomy headings and category-scoped
**View all** links, not technical selection prose. The shared footer retains
only its concise brand description; unavailable destinations remain honest.

| Refreshed/new set | Actual source and controls |
|---|---|
| Eight #61 home/shell captures, four #64 search captures, six #63 category/card captures, #86 desktop/mobile default and two expanded category disclosures | Anonymous real owner Hive `localhost:4002`; isolated frontend build at `localhost:3220`; 22 real categories/10 roots and 42 products, read-only; dark OS preference, reduced motion, DPR 1 |
| Seven #63 list/loading/parent-empty/error captures | Separate disposable PostgreSQL + unchanged Spring artifact + real Hive `:4063`, forwarding/control bridge `:4064`, frontend `:3229`; exact nine-category/thirty-product recipe; default light OS preference and motion, DPR 1 |
| #86 empty desktop/mobile | Fresh isolated database with zero categories/products, new frontend/cache origin `:3221` |
| #86 sparse mobile | Supported mutations on that isolated empty target: two categories/one product, fresh origin `:3222` |
| #86 no-products desktop | Another fresh isolated database: two supported categories/zero products, fresh origin `:3223` |
| #86 taxonomy/probe/section failure and pending Retry | Fresh full fixture database; separate origins `:3225/:3226/:3227/:3228`; bridge-selected real-operation errors/delay, not fabricated data |

All #86 state captures use reduced motion and DPR 1. Browser: Chromium
156.0.8078.4, external Playwright 1.64.0. Desktop category default is now
viewport-only to avoid the known sticky-sidebar full-page stitching artifact;
mobile/full-page empty/error/list captures record the updated shared footer.
Loading URLs below are the actual freshly generated cold inputs.

Lint, typecheck, generated GraphQL consistency and production builds passed;
115 existing library/fixture tests passed, zero failed. Production builds used
`bun run build --webpack` in separate frontend copies with existing dependencies:
the owner's running `:3000` frontend and its `.next` output were not stopped,
rebuilt or overwritten. No dependencies changed, so no frozen install was needed.

Full storefront smoke passed 11 scenarios, including **actual Chromium 200%
browser zoom** at physical 1440/390/320 widths and keyboard opening/closing of
homepage disclosures at that zoom. Live homepage checks passed real
category/subcategory/product destinations, header suggestions/submission and
two-stage Escape, visible focus, two readable columns, native disclosures and
real category navigation with JavaScript disabled. The full eight-state real
isolated homepage matrix and full catalog regression passed. Independent
section failures retain successful selections; disabled pending Retry recovers.
Fresh cold homepage `:3224` again measured **1 taxonomy + 1 probe + 3 selections**,
warm reload added zero, and every home operation revalidated after 61 seconds.
Selection/direct-membership/cache/price/card/search contracts are unchanged.
Unexpected console/runtime/hydration errors: **zero**. Expected controlled
GraphQL failures and explicitly scoped missing category/product-detail 404s are
not claimed as successful destinations. No GraphQL response stubs were used.

The native-disclosure captures show expanded real remaining children and
visible keyboard focus. Approved desktop megamenu and expanded-header search
composition/interaction are preserved; their replacement images still do not
inherit the original `6a30862`/`d7db51a` approvals. Last updated by **#86** applies
to all 37 fresh captures. Owner visual approval, stock #95, images #81/#96,
details #65, cart/checkout/authentication and genuinely rated-product ordering
remain deferred; no next ticket was started.

### #86 / #63 / #64 verified refresh, 2026-10-08

**Rendered source: `663de45e92a747e3c89c9d11639514311365b16e`, `dev`.**
All 29 new/replacement images were captured temporarily from production builds
in Chromium **156.0.8078.4** (external Playwright **1.64.0**), inspected, then
retained. Eight #61 shell/home
states, seven #63 catalog states and two #64 desktop search states replace
previous bytes in place; ten #86 homepage states and two #64 mobile search
states are new. All are **Model-reviewed — awaiting owner approval**. Original
menu/search approvals remain attached to the immutable revisions above.
No inline-header redesign or megamenu redesign was made.

| Refreshed/new set | Actual source and controls |
|---|---|
| All #61 home states, #86 desktop/mobile default, #64 desktop/mobile results/suggestions | Anonymous live owner Hive `localhost:4002`, frontend `localhost:3186`; real Estonian taxonomy, read-only; dark OS preference, reduced motion, DPR 1 |
| #63 second-page lists, grid/list loading, parent-empty, error | Separate disposable PostgreSQL + current Spring + real Hive `:4063`, forwarding bridge `:4064`, frontend `:3205`; exact nine-category/thirty-product recipe; default light OS preference and motion, DPR 1 |
| #86 category/probe/section failure and Retry loading | Same real full fixture; fresh origins `:3211/:3212/:3213/:3214`; only selected operations fail or delay, never fabricated category/product responses; dark theme with reduced motion, DPR 1 |
| #86 empty desktop/mobile | Fresh isolated database, zero categories and products, frontend `:3201` |
| #86 sparse mobile | Supported mutations on that isolated empty target, two categories/one product, new frontend/cache origin `:3202` |
| #86 no-products desktop | Another fresh isolated database, two supported categories/zero products, frontend `:3203` |

Homepage selections use the documented direct-category/lowest-price rule, not
recommendations. Bounded content is awaited before initial markup so all live
cards remain visible without JavaScript; Retry shows disabled `Retrying…`
using React Aria pending semantics. The loading image is this real pending
transition, not the removed streamed homepage skeleton.

Full catalog smoke passed on the exact isolated recipe, including direct versus
child membership, parent-empty child navigation, pagination/grid/list, exact
price cases, search/facets/sorts, reload/history, loading/error/retry/cache reuse
and missing-category HTTP 404/noindex. Full storefront smoke passed 11 scenarios,
including real 200% Chromium zoom at physical 1440/390/320 widths. Home passed
all real populated/sparse/empty/no-products/failure states, keyboard/focus,
reduced motion, overflow and live no-JavaScript rendering. Unexpected browser
console/runtime/hydration errors: **zero**. Controlled GraphQL failures and
explicitly scoped missing-category/product-detail 404s are expected, not hidden.
Cold home at fresh `:3210` measured exactly **1 taxonomy + 1 probe + 3 selections**,
warm reload **zero additional requests**, and every home operation type
revalidated after 61s.

The six previously Stale #63 images are now freshly recaptured, not relabelled
old evidence. The fixture gap was resolved by mounting individual Hive
configuration/SDL files rather than hiding its executable under a directory
mount, and checking free ports once before resource startup. Node parser
regressions cover verification entrypoints. English `/catalog/laptops` is
correct for these fixtures; live `/catalog/sulearvutid` is a different dataset,
not a replacement slug for the fixture suite. Exact preflight rejects mixing
them without weakening counts/prices.

Desktop search results are viewport-only to avoid sticky-sidebar stitching.
Home defaults are full-page; live overlays are viewport-only. The two #61
defaults are byte-identical copies of the inspected #86 defaults, retained
under stable ownership names. A preliminary fading menu capture was rejected:
blurring its trigger dismissed the nonmodal popover. Final capture preserves
focus, waits finite animations and asserts the menu is still visible.
The two owner-rejected not-found images remain deliberately untouched
before-evidence; missing-category behavior was verified anew without promoting
those rejected compositions.

Last updated by **#86, #63, #64** applies to the 29 actual new/replacement images
and their rows below. Unchanged historical rows retain their earlier provenance.
Public stock (#95), images (#81/#96), details (#65), cart/checkout/authentication
and genuine rated ordering remain deferred. Unrated rating-sort tests establish
wiring and ID tie-break only. No repository dependencies changed.

### Historical capture reports -- superseded where the current refresh applies

The following reports preserve earlier revisions and approval history, including
the initially denied tooling/seeding operations. They are not current blockers
or statuses. The retained-file inventory below is authoritative for current
bytes; original approvals are authoritative only at their recorded revisions.

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

### Search-placement correction + visual refinements (this round) — #61, #63, #64

Implements the owner's search-interaction correction (real search input shown
by default on every listing route with the header's separate Search action
hidden there; an expandable header search control on all other routes with
immediate focus, restrained/reduced-motion-aware animation, two-stage Escape,
an accessible close control and global whole-catalog scope on submit) and five
visual refinements requested from the prior round's screenshots: collapsible
facet/category disclosures, readable attribute labels, aligned grid-card
title/price baselines, removal of overly technical stock-disclaimer copy, and
tighter suggestion-panel density/alignment. The owner-approved desktop
megamenu (`issue-61-home-desktop-1440-catalog-popover-open.png`) is
**unchanged** — not touched, not recaptured.

Consequences for this manifest:

- `issue-63-category-desktop-1440-default.png`,
  `issue-63-category-mobile-390-default.png` and
  `issue-63-category-mobile-390-filters-open.png` are **replaced in place**:
  attribute facets are now closed HeroUI `Disclosure` groups by default (open
  only when they carry an active filter), category-navigation spacing is
  tighter, the price group carries an explicit "Price (EUR)" heading with
  accessible min/max labels, and grid-card titles/prices now align on a fixed
  baseline regardless of how many lines the product name wraps to. Still own
  #63; "Last updated by" stays `#63, #64`.
- `issue-64-search-desktop-1440-default.png` and
  `issue-64-search-desktop-1440-suggestions-open.png` are **replaced in
  place**: same collapsible-facet/aligned-card refresh, the removed
  "Stock information is not public yet"/"not a checkout quote" copy, and a
  flush-aligned, denser suggestion popup (previously misaligned against the
  input).
- `issue-63-catalog-desktop-1440-card-alignment.png` is **new**: a dedicated
  capture of `/catalog/all-products`'s default grid specifically evidencing
  the title/price-baseline alignment fix across one- and two-line product
  names, and the full 32-key attribute facet list (union across every
  category) collapsed by default. Captured viewport-only rather than
  full-page — Chromium/Playwright's full-page screenshot stitching visibly
  duplicates the `position: sticky` sidebar at the tile boundary; this is a
  capture-tooling artifact, confirmed by comparing against a clean
  viewport-only capture, not a rendering defect in the app.
- `issue-61-home-desktop-1440-search-expanded.png` and
  `issue-61-home-desktop-1440-search-suggestions-open.png` are **new**: the
  non-listing-page header search control, expanded, showing the focused field,
  submit and close controls, and sibling header controls (brand, catalog,
  account, cart) hidden while expanded; the second capture shows the same
  control with live, real suggestions open, submitting globally to `/search`
  (not scoped to any category).
- All other #63/#64 rows (`list`, `loading`, `list-loading`, `empty`,
  `parent-empty`, `error`, `not-found`) are **unaffected by this round** — none
  of this round's changes touch pagination, loading-skeleton markup, empty/
  error composition or the standalone not-found page; their existing Stale/
  Model-reviewed statuses and reasons from the prior round stand unchanged.
- The **owner-approved megamenu capture is untouched** and keeps its
  **Owner-approved baseline** status; this round did not redesign or recapture
  it, per the owner's explicit instruction to preserve it.

**Data source:** same local development stack (Postgres + Spring backend +
Hive on `:4002`), read-only, real dev data. The isolated fixture-stack gap
remains unresolved this round (only the persistent dev-stack containers are
running; no ephemeral verification stack was found or stood up) — reported
again, not newly introduced. A second, independent blocker was discovered
(not caused by this round): `scripts/catalog-smoke.mjs` hardcodes the English
category slug `/catalog/laptops`, which 404s against the current dev dataset's
Estonian-language slugs (`sulearvutid`, `lauaarvutid`, `komponendid`, …) — a
pre-existing dataset/script mismatch, left unfixed as out of this pass's scope
and reported as a new, distinct gap from the isolated-fixture-stack gap.
Verification: `bun run lint`/`typecheck`/`build` pass; `bun test src/lib/catalog
src/lib/graphql` (100 tests) pass; `storefront-smoke.mjs` (11/11 scenarios,
rerun against a freshly rebuilt production server to rule out stale-build
artifacts) passes with zero console/hydration/runtime errors; a standalone
Playwright script covering header-search submit/Escape/close/focus-restoration/
reduced-motion/mobile-overflow/listing-visibility/category-scoped-search/facet-
disclosure passed 9/9 checks with zero console/runtime errors (after
correcting the script's own origin mismatch — `127.0.0.1` vs the configured
`STOREFRONT_ORIGIN=http://localhost:3000` — which had produced a spurious,
reproducible-looking 403 on suggestion requests; confirmed via a direct Hive
query and a direct proxy-route inspection that this was a verification-
environment artifact, not a product defect, before re-running all checks
against the correctly-matching origin).

**No owner visual approval** is claimed for any of the seven refreshed/new
files in this round. They remain *Model-reviewed — awaiting owner approval*.

## Retained-file inventory

### #65 coordinated product/variant pass — 2026-10-08

Rendered production code matches local implementation revision
`ae3826e9e764de60c2d46b20ae79cf90379458e2` (committed after capture), following
typed-contract/model revision `e6d51baccb2549f33e4d69282cb5057befb7f48d`.
No push or owner visual approval is implied. All **17 new product captures**
and **10 refreshed homepage files** are **Model-reviewed — awaiting owner
approval**. Every distinct retained image was opened; the two #86 defaults
are byte-identical copies of the inspected #61 defaults.

Product captures came from production `http://localhost:3313`, with real
disposable Spring/Postgres/Hive behind `127.0.0.1:4064/graphql`, not fabricated
response data. The exact nine-category/thirty-product catalog recipe was
preserved; opt-in `product-fixture-data.mjs` enriched existing laptop description,
storage, ports, backlight and grams. Laptop variants **1/2** cost **€499/€649**
and weigh **1400/1500 grams**. The foreign variant **32** belongs to Compact
Desktop; the inactive variant is **37**; an actual temporary record **38** was
created and deleted. Missing optional description/barcode/weight/ratings and
unknown availability remain honest. Null required price is a decoder/service
failure, not a successful nullable-price state. Positive aggregate shapes are
unit-covered; the real browser fixtures are unrated, so rated rendering is not
claimed.

`variant-loading` uses a controlled 1.5-second browser route delay on real Next
navigation; price/specifications hide together with their footprint preserved
(footer displacement checked at ≤1px). `error` uses the bridge's product-specific
GraphQL failure; `retry-loading` uses product delay on real recovery. Initial
missing product has actual HTTP 404/noindex; service failure has a distinct
HTTP-200 recovery view. Oversized first placeholders and collapsing first
skeleton attempts were rejected and refined **before** retention.

Final lint (zero errors; one pre-existing owner homepage warning), typecheck,
generated-operation consistency, webpack production builds, **142 tests/432
expectations**, full 11-scenario storefront suite, full catalog/search suite,
home smoke and isolated/live product smoke passed. Coverage includes true
Chromium 200% zoom, keyboard/focus, 1440/390px, shared/invalid/repeated/removed/
foreign IDs, equal-price records, refresh/history/rapid navigation, no-JavaScript
variant links and service-error reload recovery. Unexpected browser/hydration
errors: zero. Live checks used anonymous owner Hive `:4002`, without mutation.
Bridge counters also proved one cold detail lookup across layout/page/metadata,
warm reuse across variant URLs, and one fresh lookup per repeated missing URL.
Task frontends and exact disposable resources were removed after verification;
the owner's original `:3000` process and live Hive stayed responsive, with
22 categories/42 products preserved (checked through a bounded size-one search).

Homepage replacements use production `http://localhost:3311` and real owner
Hive. They preserve the owner's **root-only** `3f94137` composition and existing
header interactions, not the old child-shortcut layout. Obsolete #86 expanded-
disclosure images are removed. Five previously retained sparse/no-products/
probe-error/section-error/loading images contain the owner-superseded hierarchy:
they are explicitly **Stale — historical before-evidence**, not fresh baselines.
Empty-taxonomy/category-error and unaffected catalog/search evidence retain their
original source/provenance; this pass did not alter their compositions.
Original scoped approvals at `6a30862` and `d7db51a` remain immutable and are
**not inherited by replacements**.

Except for #65-updated or explicitly Stale rows below, Model-reviewed rows retain
source `1c7f4cd` and the real/control provenance above. The two Owner-rejected
category not-found rows retain their historical bytes/provenance.

| File | Owns | Route | Viewport | State | Covers | Status | Last updated by |
|---|---|---|---|---|---|---|---|
| `issue-61-home-desktop-1440-default.png` | #61 | `/` | 1440×900, full page | Default | Owner root-only tiles, unchanged real discovery | Model-reviewed — awaiting owner approval | #65 |
| `issue-61-home-mobile-390-default.png` | #61 | `/` | 390×844, full page | Default | Owner root-only two-column tiles and real cards | Model-reviewed — awaiting owner approval | #65 |
| `issue-61-home-desktop-1440-catalog-popover-open.png` | #61 | `/` | 1440×900, viewport only | Catalog popover open | Settled preserved menu over root-only home; original approval at `6a30862` | Model-reviewed — awaiting owner approval | #65 |
| `issue-61-home-desktop-1440-account-popover-open.png` | #61 | `/` | 1440×900, viewport only | Account popover open | Preserved nonmodal overlay over root-only home | Model-reviewed — awaiting owner approval | #65 |
| `issue-61-home-mobile-390-drawer-open.png` | #61 | `/` | 390×844, viewport only | Drawer open | Preserved grouped modal drawer over root-only home | Model-reviewed — awaiting owner approval | #65 |
| `issue-61-home-narrow-320-default.png` | #61 | `/` | 320×640, full page | Default | Narrow root-only tiles, wrapping and single-card discovery | Model-reviewed — awaiting owner approval | #65 |
| `issue-63-category-desktop-1440-default.png` | #63 | `/catalog/sulearvutid` | 1440×900, viewport only | Grid | Unchanged real category/sidebar/search/facets/card alignment; viewport avoids sticky-sidebar stitching | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-mobile-390-default.png` | #63 | `/catalog/sulearvutid` | 390×844, full page | Grid | Real category cards, toolbar wrapping and concise shared footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-mobile-390-filters-open.png` | #63 | `/catalog/sulearvutid` | 390×844, viewport only | Filters drawer open | Existing collapsible facets, labelled price group and category navigation | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-desktop-1440-list.png` | #63 | `/catalog/laptops?page=2&view=list` | 1440×900, full page | List | Real isolated 21–23 of 23, exact prices, sidebar/pagination and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-mobile-390-list.png` | #63 | `/catalog/laptops?page=2&view=list` | 390×844, full page | List | Same three real items, wrapping, mobile toolbar and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-desktop-1440-loading.png` | #63 | `/catalog/laptops?page=2&minPrice=0.89&maxPrice=25654705.29` | 1440×900, viewport only | Loading grid | Fresh valid bounds retain all 23 fixture products; real listing delayed by bridge; retained sidebar | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-catalog-desktop-1440-list-loading.png` | #63 | `/catalog/all-products?page=2&view=list&minPrice=0.89&maxPrice=146384188.05` | 1440×900, viewport only | Loading list | Cold real price-filtered listing; sidebar-aware skeleton, not a cached response | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-catalog-desktop-1440-card-alignment.png` | #63 | `/catalog/all-products` | 1440×900, viewport only | Default grid | Unchanged real card title/price baselines; viewport avoids sticky-sidebar stitching | Model-reviewed — awaiting owner approval | #86 |
| `issue-64-search-desktop-1440-default.png` | #64 | `/search?q=Dell` | 1440×900, viewport only | Query results | Two live Dell products, real facets and preserved accepted results composition | Model-reviewed — awaiting owner approval | #86 |
| `issue-64-search-desktop-1440-suggestions-open.png` | #64 | `/search?q=Dell` | 1440×900, viewport only | Suggestions open | Live suggestions and visible focus; original approval at `d7db51a` | Model-reviewed — awaiting owner approval | #86 |
| `issue-64-search-mobile-390-default.png` | #64 | `/search?q=Dell` | 390×844, full page | Query results | Mobile shared results, real filter-drawer trigger and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-64-search-mobile-390-suggestions-open.png` | #64 | `/search?q=Dell` | 390×844, viewport only | Suggestions open | Readable live mobile popup, visible focus and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-61-home-desktop-1440-search-expanded.png` | #61 | `/` | 1440×900, viewport only | Header search expanded | Preserved interaction over root-only home; original scoped approval at `d7db51a` | Model-reviewed — awaiting owner approval | #65 |
| `issue-61-home-desktop-1440-search-suggestions-open.png` | #61 | `/` | 1440×900, viewport only | Header search, suggestions open | Live Dell suggestions over root-only tiles | Model-reviewed — awaiting owner approval | #65 |
| `issue-63-category-desktop-1440-empty.png` | #63 | `/catalog/tarkvara` | 1440×900, full page | Empty | Real zero products, honest recovery, category highlight and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-mobile-390-empty.png` | #63 | `/catalog/tarkvara` | 390×844, full page | Empty | Real empty state, wrapping, filter-drawer trigger and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-desktop-1440-parent-empty.png` | #63 | `/catalog/gaming` | 1440×900, full page | Parent empty | Genuine zero direct members; Components has real €49.95 product; child/back navigation and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-mobile-390-parent-empty.png` | #63 | `/catalog/gaming` | 390×844, full page | Parent empty | Honest scope, zero count, child navigation, drawer and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-desktop-1440-error.png` | #63 | `/catalog/laptops?page=397910426` | 1440×900, full page | Controlled error | Cold bridge-selected failure, safe message, real Retry/valid-page recovery and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-63-category-desktop-1440-not-found.png` | #63 | `/catalog/missing-category` | 1440×900, full page | HTTP 404 | Category-specific dark not-found, catalog recovery/noindex | Owner-rejected composition — before-evidence only | #63 |
| `issue-63-category-mobile-390-not-found.png` | #63 | `/catalog/missing-category` | 390×844, full page | HTTP 404 | Mobile missing-category recovery/footer | Owner-rejected composition — before-evidence only | #63 |
| `issue-86-home-desktop-1440-default.png` | #86 | `/` | 1440×900, full page | Default | Owner root-only categories, eight unchanged real cards | Model-reviewed — awaiting owner approval | #65 |
| `issue-86-home-mobile-390-default.png` | #86 | `/` | 390×844, full page | Default | Owner root-only two-column tiles and unchanged stacked discovery | Model-reviewed — awaiting owner approval | #65 |
| `issue-86-home-mobile-390-sparse.png` | #86 | `/` | 390×844, full page | Historical sparse | Prior real 2/1 fixture, superseded child shortcut | Stale — historical before-evidence | #65 |
| `issue-86-home-desktop-1440-empty.png` | #86 | `/` | 1440×900, full page | Empty taxonomy | Genuine zero-category/product database, honest navigation recovery and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-86-home-mobile-390-empty.png` | #86 | `/` | 390×844, full page | Empty taxonomy | Real empty state, wrapping, mobile shell and concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-86-home-desktop-1440-no-products.png` | #86 | `/` | 1440×900, full page | Historical no assigned products | Prior real 2/0 fixture, superseded child shortcut | Stale — historical before-evidence | #65 |
| `issue-86-home-mobile-390-categories-error.png` | #86 | `/` | 390×844, full page | Controlled taxonomy failure | Explicit Retry, never a success-shaped empty state; concise footer | Model-reviewed — awaiting owner approval | #86 |
| `issue-86-home-desktop-1440-probe-error.png` | #86 | `/` | 1440×900, full page | Historical controlled probe failure | Prior real taxonomy with superseded child shortcuts | Stale — historical before-evidence | #65 |
| `issue-86-home-desktop-1440-section-error.png` | #86 | `/` | 1440×900, full page | Historical controlled section failure | Prior real fixture with superseded child shortcuts | Stale — historical before-evidence | #65 |
| `issue-86-home-desktop-1440-loading.png` | #86 | `/` | 1440×900, full page | Historical controlled Retry loading | Prior taxonomy with superseded child shortcuts | Stale — historical before-evidence | #65 |
| `issue-65-product-desktop-1440-default.png` | #65 | `/products/laptop-1` | 1440×900, full page | Default | Real variant 1, €499, 512GB, 1400 grams | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-default.png` | #65 | `/products/laptop-1` | 390×844, full page | Default | Stacked product and real variant 1 | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-desktop-1440-selected.png` | #65 | `/products/laptop-1?variant=2` | 1440×900, full page | Selected | €649, 1TB, 1500 grams, visible keyboard focus | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-selected.png` | #65 | `/products/laptop-1?variant=2` | 390×844, full page | Selected | Consistent selected price/SKU/specifications and focus | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-desktop-1440-variant-loading.png` | #65 | `/products/laptop-1` → `?variant=2` | 1440×900, full page | Navigation loading | Real route delay; footprint-preserving price/specification skeletons | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-variant-loading.png` | #65 | `/products/laptop-1` → `?variant=2` | 390×844, full page | Navigation loading | Price/specifications hidden together without footer collapse | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-desktop-1440-invalid-variant.png` | #65 | `/products/laptop-1?variant=32` | 1440×900, full page | Foreign-ID recovery | Visible real-default recovery and valid-link action | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-invalid-variant.png` | #65 | `/products/laptop-1?variant=32` | 390×844, full page | Foreign-ID recovery | Readable recovery and retained valid variant navigation | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-desktop-1440-no-variants.png` | #65 | `/products/computer-kit` | 1440×900, full page | No variants | Honest missing price, description, specifications and ratings | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-no-variants.png` | #65 | `/products/computer-kit` | 390×844, full page | No variants | Stacked optional-data states; no purchase action | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-desktop-1440-inactive.png` | #65 | `/products/retired-laptop?variant=37` | 1440×900, full page | Explicit inactive | Inspectable real €9.99 record, unavailable not out of stock | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-inactive.png` | #65 | `/products/retired-laptop?variant=37` | 390×844, full page | Explicit inactive | Readable warning, SKU and no fake stock inference | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-desktop-1440-not-found.png` | #65 | `/products/nonexistent-product-smoke-65` | 1440×900, full page | HTTP 404 | Product-specific missing state, noindex and catalog recovery | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-not-found.png` | #65 | `/products/nonexistent-product-smoke-65` | 390×844, full page | HTTP 404 | Distinct mobile missing-product recovery | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-desktop-1440-error.png` | #65 | `/products/laptop-22` | 1440×900, full page | Controlled service failure | Safe error and working Retry, not a false 404 | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-error.png` | #65 | `/products/laptop-22` | 390×844, full page | Controlled service failure | Mobile Retry and preserved shell | Model-reviewed — awaiting owner approval | #65 |
| `issue-65-product-mobile-390-retry-loading.png` | #65 | `/products/laptop-22` | 390×844, full page | Controlled Retry loading | Supported disabled Retrying feedback before real recovery | Model-reviewed — awaiting owner approval | #65 |

No unexpected console, runtime or hydration errors occurred. Controlled
HTTP-200 GraphQL failures and the deliberate missing-category HTTP 404 are expected.

## Invalidation ledger — which correction step supersedes which capture

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
| `issue-63-category-desktop-1440-list.png` | **3** | List view inside the new two-region layout | **Resolved** — fresh real fixture second-page/sidebar capture, exact pagination and prices verified |
| `issue-63-category-mobile-390-list.png` | **3** | As above, mobile | **Resolved** — fresh mobile fixture capture |
| `issue-63-category-desktop-1440-loading.png` | **3** | Skeleton must match the sidebar layout | **Resolved** — fresh cold-input real listing with controlled bridge delay |
| `issue-63-category-desktop-1440-empty.png` | **3** | Layout only | **Done, replaced in place** at `/catalog/tarkvara` (the old route is no longer empty in live dev data either) |
| `issue-63-category-mobile-390-empty.png` | **3** | Layout only | **Done, replaced in place**, same route |
| `issue-63-category-desktop-1440-parent-empty.png` | **3** | Layout only | **Resolved** — real isolated Gaming has zero direct members; Components product and back navigation verified |
| `issue-63-category-mobile-390-parent-empty.png` | **3** | Layout only | **Resolved** — fresh mobile fixture capture and child navigation |
| `issue-63-category-desktop-1440-error.png` | **3** | Layout only | **Resolved** — actual cold controlled GraphQL failure, safe error and real Retry/valid-page recovery |
| `issue-63-category-desktop-1440-not-found.png` | **3** (shell), **4** (header) | Shell/header only | **Yes** — HTTP 404 + noindex behaviour unchanged; confirmed unaffected by reading `catalog/not-found.tsx` (no sidebar) |
| `issue-63-category-mobile-390-not-found.png` | **3**, **4** | Shell/header only | **Yes**, same confirmation |
| `issue-61-home-desktop-1440-default.png` | **4** header search input, **7** home replacement | Header gains a real field; the holding home composition is replaced outright | **Done** — fresh live discovery capture |
| `issue-61-home-mobile-390-default.png` | **4**, **7** | As above, mobile | **Done** — fresh live mobile discovery capture |
| `issue-61-home-narrow-320-default.png` | **4** | The <22rem Search-label rule and the <16rem stacking rule must be re-proven against a real input | Narrow-width coverage retained |
| `issue-61-home-desktop-1440-account-popover-open.png` | **4**, **7** | Header geometry and the page behind it change | **Done** — fresh live capture, overlay semantics retained |
| `issue-61-home-desktop-1440-search-expanded.png`, `issue-61-home-desktop-1440-search-suggestions-open.png` | **7** | Holding body behind accepted expanded search replaced | **Done** — fresh live images; original approval remains at its original revision |

### Captures the correction should add

New states worth preserving once the owning step lands — keep the set small, and
add an image only where it proves something the rows above do not:

| Planned file | Step | Proves |
|---|---|---|
| `issue-61-home-desktop-1440-catalog-popover-open.png` *(replaced in place)* | 1, 7 | Approved original menu composition preserved; fresh new-home replacement is Model-reviewed, original 2026-10-07 approval retained in history |
| ~~`issue-63-catalog-desktop-1440-default.png`~~ *(removed)* | 2 (superseded) | Was category discovery at a standalone `/catalog` page; the owner reversed this before review — the megamenu is the sole discovery surface instead |
| `issue-63-category-desktop-1440-default.png` *(replaced in place)* | 3 | Sidebar with in-context category navigation beside a compact product area — **done** |
| `issue-63-category-mobile-390-filters-open.png` *(new)* | 3 | The mobile filter/navigation drawer with its active-filter count — **done** |
| `issue-64-search-desktop-1440-default.png` *(new)* | 5 | The shared results surface under a search URL, with real facets — **done** |
| `issue-64-search-desktop-1440-suggestions-open.png` *(new)* | 6 | Suggestion popup built only from `name`/`slug`, with keyboard selection — **done** |
| `issue-86-home-desktop-1440-default.png`, `issue-86-home-mobile-390-default.png` *(new)* | 7 | **Done** — real category entry points and accurately labelled discovery on desktop/mobile |

The previously outstanding second-page, loading, parent-empty and controlled
error captures are resolved through the real isolated fixture stack. No Stale
row remains in the current retained-file inventory. Historical reports above
remain historical; neither their old bytes nor rejected not-found compositions
were relabelled current.

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

The 2026-10-08 refreshed captures used the production build, isolated Hive `localhost:4063`, a
verification-only forwarding bridge at `localhost:4064`, and frontend
`localhost:3205`. The bridge forwards normal requests to real Hive and counts
operation names; `/control?mode=delay` delays only CatalogListing by 2500ms,
`mode=error` returns a selected-operation HTTP-200 GraphQL INTERNAL error,
`mode=categories-error` does so only for CatalogCategories, and `mode=normal`
restores real forwarding. It returns `{ identity, mode, counts }`. Homepage
fault/delay modes and category-scoped section failures are described in the
frontend README. This bridge/fixture
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
  FRONTEND_URL=http://localhost:3164 CATALOG_TEST_CONTROL=http://127.0.0.1:4064/control \
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
