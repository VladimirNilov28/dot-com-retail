# ByteCore storefront design handoff

The owner approved the storefront appearance at commit `aef4673` (`dev`). This
document records **why** it looks the way it does so later work extends it
instead of re-deciding it.

Read this before any storefront UI change, together with the baseline
screenshots in [`baseline/`](baseline/) ([manifest](baseline/README.md)).

For a **new kind of page** with no implemented ByteCore precedent — a listing, a
product page, a cart, a checkout step, an auth form — also study the third-party
store references in [`.claude/references/`](../../.claude/references/)
([what each one shows](../../.claude/references/README.md)). They are a pattern
brief, never a baseline: take hierarchy, density and interaction ideas, not
branding, colour or layout. Neither reference is a dark storefront — the
structure transfers, the surface treatment does not.

- Narrative history of the correction: [`frontend/README.md`](../../frontend/README.md) → *#61 visual correction*
- Shared delivery/design contract: issue #94
- Review checklist and prompt templates: [`REVIEW.md`](REVIEW.md)

Two kinds of statement appear below and are labelled throughout:

- **Convention** — established; follow it or explain the departure in the ticket report.
- **Example** — one composition that works; you may choose differently.

**Baseline:** HeroUI v3 (fixed dark theme) and Lucide. Both stay.

---

## 1. What went wrong before, and what fixed it

Worth understanding, because the same mistakes are easy to repeat on a catalog
or checkout page.

The first shell was functionally complete but read as assembled parts:

| Symptom | Actual cause |
|---|---|
| Header felt heavy (136px) | The right-hand column stacked two rows — Account/Cart above Categories/Home/Catalog — so the stack, not the content, set the header height |
| Search looked like a broken input | A bordered, filled, full-width slab in the stretching middle column imitated a search field that did not exist |
| Navigation felt redundant | Three items (Home, Catalog, Categories) expressed two real destinations, and the wordmark already linked home |
| The page felt like a demo | A large foundation headline plus a component playground occupied the customer-facing route |

The correction did **not** shrink everything. Four changes carried it:

1. **Collapse to one row by removing duplicate meaning,** not by reducing
   padding. Merging `Categories` + `Catalog` and promoting the wordmark to the
   home link removed two controls, and the header became one row as a
   consequence (~65px).
2. **Make each control honest about what it is.** Search became a labelled
   action because search does not exist yet. A real field arrives with #64.
3. **Spend emphasis once, deliberately.** With a single accent control, the eye
   lands on the catalog entry instead of choosing between competing surfaces.
4. **Give the page a composition.** A single left-aligned column inside an 80rem
   container reads as a half-empty page; a two-column grid reads as a layout.

**The transferable lesson:** crowding is usually duplicated meaning or
undifferentiated emphasis, not insufficient whitespace.

---

## 2. Tokens

**Convention: never hard-code a colour, radius or control height.** Use a token.
If a value is genuinely new and shared, add it to `:root` in
[`globals.css`](../../frontend/src/app/globals.css) with a comment.

### Project tokens — `globals.css` `@layer base`

| Token | Value | Purpose |
|---|---|---|
| `--page-max-width` | `80rem` | Shared content width (`.page-container`) |
| `--page-gutter` | `clamp(1rem, 4vw, 2.5rem)` | Responsive page inset |
| `--control-height` | `2.5rem` | Minimum height of **every** interactive shell control and page action |

`--accent`, `--accent-foreground` and `--focus` are redefined under
`:root[data-theme="dark"]` to a lighter blue that carries on a near-black
background.

> `--page-max-width` is the current working width, chosen because 72rem left the
> header bunched at 1440px. It is **not** a permanent constraint. A catalog grid
> or a product gallery may justify a different width — change it deliberately
> and say so in the ticket report.

### HeroUI semantic tokens — use these, don't invent colours

Surfaces `--background` `--background-secondary` `--surface` `--surface-secondary`
`--surface-tertiary` `--overlay`; text `--foreground` `--muted`
`--surface-foreground` `--accent-foreground`; lines `--border`
`--border-secondary` `--border-tertiary` `--separator`; state `--accent`
`--accent-hover` `--accent-soft` `--focus` `--danger` `--success` `--warning`
(each with `-foreground`/`-soft` variants).

`--danger`, `--success` and `--warning` are **unused today**. They are the
correct source for form validation, stock warnings and payment outcome states —
use them rather than new colours.

### Radii — from Tailwind 4, not HeroUI

`--radius-sm` (0.25rem) and `--radius-lg` (0.5rem) come from Tailwind's default
theme. HeroUI defines `--radius`, `--radius-xs` and `--radius-xl`+.

**Convention — two radii only:**

- `--radius-sm` for text-level affordances (wordmark, inline links).
- `--radius-lg` for controls, buttons and panels.

Larger radii belong to HeroUI's own components; don't introduce a third scale.

### Typography

System font stacks (`@theme inline`); no downloaded webfonts. Body line height
`1.6`.

**Convention:**

- One `<h1>` per page.
- Headings use `font-semibold` + `tracking-tight`, not `font-bold`. The wordmark
  is the only `font-bold` element in the shell.
- Scale up responsively rather than starting large: page title
  `text-2xl sm:text-3xl`; hero title `text-3xl sm:text-4xl`.
- Secondary text uses `text-muted`, not a lowered opacity.
- Small uppercase labels (eyebrows, section labels) use
  `text-sm font-semibold tracking-widest uppercase` — accent for a page eyebrow,
  muted for a section label.

### Spacing

**Convention — prefer a small set of steps** so unrelated pages stay visually
related: `0.5rem` (within a control), `0.75rem` (control padding),
`1rem` (panel padding), `1.5–2rem` (between related blocks), `2.5–4rem`
(between major regions).

Page rhythm is owned by the storefront layout
([`layout.tsx`](<../../frontend/src/app/(storefront)/layout.tsx>)): `py-8 sm:py-12`.
Pages should not add their own outer vertical padding.

---

## 3. Surfaces, borders and accent

The dark theme is near-black and **low-contrast between surfaces**. Depth comes
from borders and small lightness steps, not shadows or large filled panels.

**Convention:**

- Default to **no surface**. Content sits directly on `--background`.
- Use `--surface` for hover and for quiet controls; `--surface-secondary` for a
  selected or current state.
- Separate regions with a `1px` border (`border-separator`) before reaching for
  a filled panel. The header, footer and the home availability list all do this.
- Reserve `--overlay` for genuine overlays (popovers, drawers) — HeroUI applies
  it for you.
- **Don't wrap content in a card to fill space.** If a region looks empty, the
  composition is wrong; a card only hides it.

### Accent

The accent currently appears **once per viewport**, on the Catalog trigger.

**This is a hierarchy rule, not a quota.** It exists because the shell today has
no genuinely primary page action. It must **not** be used to argue that
*Add to cart*, *Checkout* or *Place order* should be quiet. Those are the most
important actions on their pages and should take primary emphasis — which may
mean demoting the header catalog pill on those routes.

The real convention: **on any given screen, exactly one action should look most
important, and it should be the one the customer most likely wants.**

`.store-cta` is the quiet secondary action — a bordered `--surface` control that
shares `--control-height` with everything else. It exists because HeroUI v3's
`Button` is a `react-aria-components` button with **no polymorphic `as` prop**,
so a link cannot be rendered as a HeroUI button. For a link styled as a button,
use a Next `<Link>` with `.store-cta`. HeroUI's exported `Link` wraps React
Aria's and needs a `RouterProvider` for client-side routing — don't reach for it
casually.

---

## 4. Shell structure and responsive behavior

### Desktop (≥48rem)

```
ByteCore │ Catalog ⌄ ············ Search · Account ⌄ · Cart
```

`.store-header` is a 4-column grid: `auto auto minmax(0, 1fr) auto`. The
flexible third column is `.store-search-slot`.

### Mobile (<48rem)

```
ByteCore ········· Search · Menu
```

A 3-column grid. Catalog, Account and Cart move into the drawer.

**Convention: mobile is a different composition, not a squeezed desktop.**

Two narrow-width rules exist and should be preserved by anything added to the
header: below **22rem** the Search label is visually hidden while keeping its
accessible name; below **16rem** the header stacks to one column.

> The ~65px header height is a *result* of the current content, not a target.
> When #64's search field and #73's cart preview land, the header may legitimately
> get taller. Don't treat the current height as an acceptance test.

### Composition slots — use these instead of editing the shell

`StoreHeader` and `StoreNavigation` accept two optional props:

| Slot | Replaces | Owner |
|---|---|---|
| `quickSearch` | the compact Search link in `.store-search-slot` | #64 |
| `cartPreview` | the Cart link in `.store-actions` | #73 |

Each slot is mounted **once** and is responsive. Fill the slot; don't add a
parallel control.

### Overlay behavior — convention, verify after any change

All three overlays come from HeroUI/React Aria:

- **Catalog and Account** — `Popover`, `isNonModal`, `placement="bottom start"`,
  `aria-haspopup="dialog"`, each with a labelled `Popover.Dialog`.
- **Mobile navigation** — `Drawer`, modal, left placement, `inert` background,
  explicit close trigger with an accessible name.
- Width is `min(20rem, calc(100vw - 2rem))` / `min(20rem, 90vw)` so overlays fit
  the real viewport — including 160px CSS width at 200% mobile zoom.

Behavior that must survive any shell change:

- Escape, outside/backdrop click and route change all dismiss.
- Navigating via a link closes the overlay, but modified clicks (⌘/Ctrl/Shift/Alt) don't.
- Crossing the 48rem breakpoint moves focus between the matching desktop and
  mobile control — see the `matchMedia` effect in
  [`store-navigation.tsx`](../../frontend/src/components/store-navigation.tsx).
- `prefers-reduced-motion` disables overlay and control transitions.

**Choose modal or nonmodal deliberately, and verify against that choice.** The
two are not interchangeable, and a generic "overlays trap focus" checklist is
wrong here:

- **Modal** (the mobile drawer): contains focus, makes the background `inert`,
  dismisses on Escape and backdrop, and restores focus to the trigger or a
  logical fallback.
- **Nonmodal** (the Catalog and Account popovers): does **not** trap focus and
  does **not** block the background. Focus may legitimately move out to the
  surrounding page. Verify keyboard access, Escape and outside dismissal, and
  sensible focus behavior — but don't force focus back to the trigger when the
  user deliberately moved elsewhere, and don't add a global focus trap.

Preserve HeroUI's built-in behavior for the semantics you chose rather than
layering your own focus management on top.

> **Gotcha:** CSS selector *lists* cannot share a descendant combinator.
> `` `${".a, .b"} button` `` parses as `.a, .b button`. This previously broke
> focus restoration, which is why `DESKTOP_LINKS`/`DESKTOP_BUTTONS` are spelled
> out explicitly.

### DOM order

**Convention:** DOM order matches visual order — catalog → search → actions →
mobile — so tab order equals reading order (WCAG 2.4.3). Keep it that way when
inserting into a slot.

---

## 5. Existing components and reuse

Everything in `frontend/src/components/`:

| Component | Type | Reuse |
|---|---|---|
| [`page-container.tsx`](../../frontend/src/components/page-container.tsx) | Server | **Always** use for page-width content. Accepts native div props, so `className` composes. |
| [`store-header.tsx`](../../frontend/src/components/store-header.tsx) | Server | Takes `quickSearch` / `cartPreview`. |
| [`store-navigation.tsx`](../../frontend/src/components/store-navigation.tsx) | Client | The only client boundary in the shell. Owns route state and all three overlays. |
| [`store-brand.tsx`](../../frontend/src/components/store-brand.tsx) | Client | Wordmark as home link with `aria-current`. Client only because the shell is a Server Component and it needs `usePathname`. |
| [`store-footer.tsx`](../../frontend/src/components/store-footer.tsx) | Server | Grouped link columns mirroring the header. Add a column here when a new destination group appears. |
| [`unavailable-page.tsx`](../../frontend/src/components/unavailable-page.tsx) | Server | **Reuse for every not-yet-built route.** Takes `title` + `description`; renders eyebrow, `h1`, description and a home link. |
| [`foundation-demo.tsx`](../../frontend/src/components/foundation-demo.tsx) | Client | Component playground. Lives only at `/dev/foundation` (`noindex, nofollow`, unlinked). Not customer-facing. |

Shared CSS classes live in `globals.css` `@layer components`: `.page-container`,
`.store-header`, `.store-brand`, `.store-nav-link`, `.store-action`,
`.store-action-label`, `.store-icon`, `.store-search-slot`, `.store-actions`,
`.store-catalog-slot`, `.store-popover`, `.store-drawer-*`, `.store-close`,
`.store-footer`, `.store-link`, `.store-cta`.

### Reuse opportunities, honestly assessed

**Clear reuse today:**

- `UnavailablePage` for `/checkout`, `/orders`, and any route stubbed before its
  feature lands.
- `PageContainer` for every new page region.
- `.store-cta` for secondary page actions; `.store-link` for inline links.
- `.store-icon` + `size={20} strokeWidth={2}` for every Lucide icon.

**Extract only when a second real caller exists.** Three components are likely
to emerge from upcoming tickets, but **do not create them in advance**:

- A **product card** (#63) — genuinely shared by listing, search, home and cart
  recommendations. Build it in #63 for listing, then reuse it; don't design it
  for four pages at once.
- A **page header** (eyebrow + `h1` + description + optional toolbar) — already
  duplicated between `UnavailablePage` and the home hero. Worth extracting when
  the third caller appears, not before.
- An **empty/error state** — needed by #63/#64 and currently has no shared home.

**Do not** build a design-system package, a token abstraction layer, a variant
system on top of HeroUI, or wrapper components around HeroUI primitives. HeroUI
*is* the component layer.

---

## 6. Extending the storefront

Guidance, not wireframes. Choose your own composition; these are the constraints
that keep it consistent.

Each section below names the most useful reference captures in
[`.claude/references/`](../../.claude/references/) — study how those pages
prioritise and pace information, then design ByteCore's version in this theme.
Don't reproduce them. The set covers two stores at opposite densities (iDeal
restrained, 1a.ee dense); ByteCore sits much nearer the restrained end today, so
when they disagree prefer the quieter solution unless real data justifies the
density.

### Catalog and search (#63, #64)

*References: `iDeal_product_list` is where #63 should start — page title above a
breadcrumb, a category tree shown in context on the left, a light toolbar with
sort and a grid/list toggle, and **no filters at all**. `1a_product_list` is
where #64 eventually lands — result count, refining chips, page-size selector,
and a heavily faceted rail. Build the first, not the second. For the category
**navigation** itself, `iDeal_top_nav_bar_1–5` show a full-width panel with three
labelled column groups (a fixed template, plain text links, no icons or promo
imagery); `1a_catalog` shows the sidebar-plus-flyout alternative that scales to a
deeper taxonomy. Both references are light themes — take the structure, not the
white cards.*

- **Convention:** results sit directly on the background, separated by borders
  and spacing. A filled card per product will look heavy in this theme.
- Keep one consistent image area ratio across every card so rows align. The
  current product-image policy is a shared **"404 / Image unavailable"
  placeholder**; real photos arrive later via admin upload and are stored outside
  Git. Design for the placeholder as a normal state, not an error.
- Price is the most prominent element after the product name. Availability is a
  short honest phrase, never a fabricated number.
- *Example:* a responsive grid with filters in a desktop sidebar and a mobile
  drawer; a grid/list toggle in a result toolbar beside the result count. The
  mobile filter drawer should reuse the existing `Drawer` behavior.
- Skeletons must match the real layout's dimensions so nothing reflows.

### Product detail (#65)

*References: `iDeal_product_page` — breadcrumb, SKU above the title, a gallery
with a thumbnail rail, and a separate purchase panel on the right. Variant
selection is its own bordered group (colour swatches with labels, storage as
chips), the closest model for the variant dimensions in #97. Delivery and
trade-in information sits *below* the purchase decision rather than competing
with it. `1a_product_page_description` answers "where does all the detail go":
long specifications as tabbed, three-column label/value tables well below the
purchase panel.*

- The gallery and the purchase panel are the two anchors; everything else is
  secondary.
- **This page has a genuine primary action.** *Add to cart* takes primary
  emphasis — see §3.
- An unavailable variant must not look purchasable. Use `--danger`/`--warning`
  for that signal.
- *Example:* two columns on desktop (gallery left, sticky purchase panel right),
  stacking to gallery-then-panel on mobile.

### Forms (#66–#68, #71, #74)

*References: `iDeal_registration` — one column, grouped into labelled fieldsets
inside a card, primary action with a quiet secondary beside it. `IDeal_login` —
a narrow form card next to a visually quieter panel whose only content is the
secondary "create an account" path. `1a_login` ranks the same two paths the
other way round, inside one card. All of them rank the paths rather than giving
them equal weight. `1a_registration` is a warning: its consent and legal text
outweighs the form itself — keep #66's consent handling honest but far shorter.*

- **Convention:** use HeroUI form components. They carry label association,
  description/error wiring and focus styling already.
- Single column. Labels above fields. Errors below the field they belong to, in
  `--danger`, and announced — never colour alone.
- Submit is the primary action; cancel is quiet or a plain link.
- Disable submit only while a request is in flight, and say why.
- Respect `--control-height` so fields line up with shell controls.

### Cart and checkout (#73, #74)

*References: `iDeal_Shopping_Cart` — line items as rows (thumbnail · name ·
chosen variant attributes as quiet label/value pairs · a small price/quantity/
subtotal block · a plain remove link), a totals panel on the right with the tax
breakdown, a primary checkout action with a quiet "return to store" beside it,
and a separate promo-code box. Note also what makes it heavy: per-item warranty
and protection-plan radio lists. ByteCore has no such upsell — keep the line
simple. `1a_shopping_cart_empty` is the more immediately useful one: an **empty
cart** that keeps the page's structure (column headers, delivery panel, totals
reading `0,00 €`), says plainly that it is empty, disables the forward action
and offers a way back to the catalog. `iDeal_main_page_2` shows a cart count
badge on the header icon, the affordance #73 needs. No capture covers the
checkout steps themselves; design those from the conventions above and note any
reference you add in `.claude/references/README.md`.*

- The order summary is reference information — keep it quiet. *Checkout* /
  *Place order* is the emphasis.
- Money arrives as **decimal strings** through the #62 transport. Format for
  display; never do float arithmetic.
- Quantity controls are small and consistent; they are not the page's emphasis.
- The global cart affordance must show an honest indeterminate state before
  hydration rather than a possibly-wrong `0` — see #73 for why the cart is read
  client-side.
- *Example:* two columns on desktop (items left, sticky summary right), stacking
  on mobile with the summary and primary action reachable without a long scroll.

### Home and merchandising (#86)

*References: `iDeal_main_page_2` — a horizontally scrollable row of product-type
entries (image above a plain text label, no cards or borders) acting as the real
catalog entry point, then a small number of featured products with one action
each. `iDeal_main_page_1` shows the opposite emphasis: a single full-bleed hero.
`1a_main_page` shows the cost of the dense approach — four full-width bands
before any content.*

- The current home page is a **temporary holding composition**, not a design to
  preserve. #86 replaces it outright.
- Whatever replaces it must still be honest: no invented products, prices or
  discounts ahead of real data.
- Categories are the most useful first row once #63 lands — they are navigation
  the customer actually needs, not merchandising filler.

### Things that are explicitly not rules

The temporary holding home page, the ~65px header height, the 80rem container
and "one accent per viewport" are all **current state**, not constraints. #86
replaces the home page outright. Change the others when real content justifies
it — and record the reasoning in the ticket report.

---

## 7. Known characteristics

Recorded so they aren't mistaken for bugs or silently "fixed":

- **The Catalog trigger is always accent-filled**, including on `/catalog`. It
  reads as a persistent catalog entry rather than a current-route indicator.
  `.store-panel-trigger[aria-current="page"]` only tints the ghost Account
  trigger. When real categories land, consider a distinct current-route
  treatment — deliberately, as part of that ticket.
- **The holding home page leaves large empty space** below the fold at desktop
  heights. Expected; #86 replaces it.
- **Popover content is intentionally sparse.** Both popovers state what is not
  available instead of listing invented destinations. Replace the copy when the
  feature is real — don't pad it. When #63 brings real categories, the Catalog
  panel will likely need to grow into a grouped, wider layout
  (`iDeal_top_nav_bar_1–5` in [`.claude/references/`](../../.claude/references/)
  show the pattern). That is a deliberate change of overlay semantics, not a
  cosmetic one — re-decide modal vs nonmodal and verify the focus and dismissal
  behavior that follows.

---

## 8. Screenshot lifecycle

Baseline screenshots are **documentation under version control**, not
throwaway artifacts. They only stay useful if they are treated as owned,
inspected and maintained. The full rules and the current set live in
[`baseline/README.md`](baseline/README.md); the essentials:

### Naming — convention

`issue-<ticket>-<route>-<viewport>-<state>.png`

```
issue-63-catalog-desktop-1440-default.png
issue-73-cart-mobile-390-drawer-open.png
issue-61-home-desktop-1440-catalog-popover-open.png
```

The ticket is the **owning** ticket — the one whose implementation created the
view. Names are **stable**: replace the file in place. No timestamps, no `-v2`,
no `-final`, no numbered copies. A diff should show an image changing, not the
set growing.

### Capture and review — convention

1. After implementing a UI ticket, capture the **actual running app** with
   Playwright — the production build, at the desktop and mobile widths and the
   interaction states the ticket actually affects.
2. **Inspect every capture**, alongside the working interaction in a real
   browser. A screenshot proves rendering, not usability.
3. If a capture reveals a visual defect, **fix the implementation and
   recapture** the affected states. Do not document the defect as the baseline.
4. Capture to a temporary directory and promote only inspected, accepted files
   into `baseline/`. **Never overwrite an accepted baseline with a
   known-broken or uninspected result.** Intermediate, before and debug
   captures stay temporary unless an issue report genuinely needs them as
   before/after evidence.
5. Keep only the final inspected captures in `baseline/`.

### Approval — convention

Two distinct states, both recorded in the manifest:

- **Model-reviewed — awaiting owner approval:** captured and inspected by the
  implementer. Legitimate evidence in an issue report; not an authority.
- **Owner-approved baseline:** the owner explicitly approved this appearance.
  The reference the next change is judged against.

**Passing lint, typecheck, the build or the smoke script does not establish
owner approval. Neither does taking a screenshot.** Only the owner's explicit
approval promotes a capture to baseline. The current `issue-61-*` set is
owner-approved.

### Recapture — convention

**Before editing UI, read the manifest and identify which captures your change
affects.**

A ticket ID marks **ownership, not exclusive coverage**. Editing the shell, a
shared token in `globals.css` or a shared component invalidates captures owned
by several different tickets — a change to `--control-height` or `StoreHeader`
touches every image in the set.

- Recapture every affected route and state, update those manifest rows, and
  delete any file the change supersedes — all in the same commit as the UI
  change.
- A **new page** uses its implementation ticket's ID. An **existing capture
  keeps its owning ticket ID** when refreshed; record the refreshing ticket in
  the manifest's *Last updated by* column.
- If a capture cannot be reproduced (missing data, removed route, broken
  dependency), mark it **Stale** in the manifest with the reason. Do not
  silently present a stale image as current.
- Backend-only changes need a recapture only when they change visible UI
  behavior or invalidate the data a capture depends on.

Keep the set small and representative. Add an image only for a genuinely new
state worth preserving.

---

## Verifying a UI change

```bash
cd frontend
bun run lint && bun run typecheck && bun run build
bun run test:graphql

# Behavioral smoke against the production build (see frontend/README.md)
PLAYWRIGHT_MODULE=/abs/path/to/playwright/index.mjs \
FRONTEND_URL=http://127.0.0.1:3100 \
bun scripts/storefront-smoke.mjs
```

`frontend/scripts/storefront-smoke.mjs` already covers focus containment,
dismissal, focus restoration, breakpoint focus transfer, dark first paint, no-JS
rendering, overflow and a real 200% zoom pass. **Update its assertions when you
intentionally change markup; don't delete scenarios to make it pass.**

Then capture, inspect and promote screenshots per §8, and update
[`baseline/README.md`](baseline/README.md) in the same commit.
