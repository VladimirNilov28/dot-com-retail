# ByteCore storefront design handoff

> ## ⚠ Correction in force — read §9 before any catalog, search or home work
>
> On **2026-10-06** the owner **rejected the catalog composition** shipped at
> `5ffceb0`/`1d9477c`: category navigation reduced to a row of filter-like
> buttons, unrelated products shown together as the default browsing entry,
> oversized image placeholders, a long narrow category dropdown, and a Search
> control that only navigates instead of offering an input.
>
> **[§9 Navigation and discovery requirements](#9-navigation-and-discovery-requirements)**
> records what replaces it. §9 overrides any earlier statement in this document,
> in the baseline screenshots and in the tickets. The rest of the design —
> tokens, surfaces, overlay semantics, honesty rules, the HeroUI v3 dark theme,
> Lucide and the no-photo-assets placeholder policy — is unchanged and still
> applies.
>
> The screenshots in [`baseline/`](baseline/) still show the **rejected**
> composition. They are retained as before-evidence and are **not** a standard to
> match — see the manifest's status column.

The owner approved the storefront appearance at commit `aef4673` (`dev`). This
document records **why** it looks the way it does so later work extends it
instead of re-deciding it.

That approval covers the **shell conventions** listed in §1 — the compact single
row, the wordmark as home link, a single accent catalog entry, restrained
surfaces and honest unavailable copy. It does **not** cover the catalog pages or
the category panel's contents, which were built later and are corrected by §9.

Read this before any storefront UI change, together with the baseline
screenshots in [`baseline/`](baseline/) ([manifest](baseline/README.md)).

For a **new kind of page** with no implemented ByteCore precedent — a listing, a
product page, a cart, a checkout step, an auth form — also study the third-party
store references in [`docs/references/`](../references/)
([indexed by file, state and ticket](../references/README.md)). They cover much
of the same journey in **three** stores at different densities — arvutitark.ee
(dark, the closest analogue, and the most complete journey here),
iDeal/shop.cec.ee (restrained) and 1a.ee (dense).

They are third-party inspiration, never a ByteCore baseline: take hierarchy,
density and interaction ideas, not branding, colour or layout, and treat them as
alternatives rather than requirements. Only arvutitark is dark; from the other
two, structure transfers but surface treatment does not. Their product
photography is not a source of runtime assets — see the image policy in §6.

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
wrong here. The choice is about **behavior, not appearance**: size, elevation
and a dimmed backdrop are presentation, and none of them requires modality.
Decide by whether the surface should contain focus and block interaction with
the page behind it.

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
| [`product-card.tsx`](../../frontend/src/components/product-card.tsx) | Server | #63 grid/list names, exact EUR display and explicitly unknown availability. |
| [`image-unavailable.tsx`](../../frontend/src/components/image-unavailable.tsx) | Server | Shared code-rendered media placeholder, no network image requests. |
| [`catalog-page.tsx`](../../frontend/src/components/catalog-page.tsx) | Server | #63 breadcrumbs, direct category context, results/recovery and bounded pagination. |
| `catalog-controls.tsx`, `catalog-link.tsx`, `catalog-retry.tsx` | Client | URL-backed view/pending navigation and explicit route recovery only. |

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

- The **product card** now exists for #63 grid/list. Reuse it when search/home
  need it; it was not designed speculatively for four pages.
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
[`docs/references/`](../references/) — study how those pages prioritise and pace
information, then design ByteCore's version in this theme. Don't reproduce them.

The set covers three stores: **arvutitark.ee** (dark, the closest analogue, and
the most complete journey), **iDeal** (restrained) and **1a.ee** (dense). Start
with arvutitark when you need to see a pattern working in a dark theme. Where
they disagree, each is a legitimate alternative — choose by what ByteCore's real
content and feature state justify, not by what the references happen to have.
With little catalogue depth today, the quieter answer is usually the right
starting point, but that is a judgement about our content, not a rule.

### Catalog and search (#63, #64)

*References, for **listing (#63)**: `arvutitark_products_list` shows a dark page
header over a light results panel, with one toolbar carrying page size,
pagination, a view toggle, sort and a result count. `iDeal_product_list` shows
the same job with a category tree in context and **no facets at all**.
`1a_product_list` shows **search with facets (#64)** — result count, refining
chips, page-size selector and a filter rail. These are three points on a scale,
not a sequence to work through: how much of it ByteCore needs depends on how
large and varied the real catalogue turns out to be.*

*For **category navigation**: `arvutitark_products_catalog_dropdown` is the
dark-theme example; `iDeal_top_nav_bar_1`–`_5` show a fixed labelled-column
template holding steady across sparse and long categories; `1a_catalog` shows a
persistent sidebar with a flyout, which suits a deep taxonomy. Any of these, or
something else entirely, can work — none is required.*

*For **live suggestions (#64)**: `arvutitark_search_on_main_page` shows one panel
split into labelled `PRODUCTS` and `CATEGORIES` sections as plain text rows, with
no thumbnails or prices.*

- **Convention:** results sit directly on the background, separated by borders
  and spacing. A filled card per product will look heavy in this theme.
- Keep one consistent image area ratio across every card so rows align. The
  current product-image policy is a shared **"image unavailable"
  placeholder**; real photos arrive later via admin upload and are stored outside
  Git. Design for the placeholder as a normal state, not an error — and keep it
  **subordinate to the name and price** (§9.5).
- Price is the most prominent element after the product name. Availability is a
  short honest phrase, never a fabricated number.
- **Required, not an example:** a desktop left sidebar carrying category
  navigation and real filters, with a mobile drawer, and one shared results
  implementation behind both category browsing and search. See §9.3–§9.4.
- Skeletons must match the real layout's dimensions so nothing reflows.

**#63 composition decision (superseded 2026-10-06 — see §9):** the composition
described below was implemented, model-reviewed and then **rejected by the
owner**. It is kept only so the reasoning behind the parts that survive is not
re-derived. **Where it conflicts with §9, §9 wins.**

*Superseded by §9:* the wrapping row of current-branch category links as the
only category navigation; `/catalog` as a flat all-products grid; the 4:3
placeholder as the card's dominant element; the absence of a sidebar and of
filters.

*Still in force:* retain the 80rem PageContainer and the compact shell.
Breadcrumbs and a modest heading lead the page. Products sit directly on the
background with separators; only the media placeholder uses a quiet surface.
Name areas flex to align prices without truncating long names. Control and
navigation icons retain the shared 20px size. The existing Catalog trigger
remains the primary accent; selected view controls are quiet and expose pressed
semantics, not colour alone. Parent pages explain their scope and link children;
they never imply descendant aggregation. The same fetched flat taxonomy supplies
breadcrumbs, in-page navigation and the shell's root/child links.

Prices use exact minimum active-variant EUR values (“From” only for differing
eligible prices; none means “Price unavailable”). Stock remains “Availability
unknown”; `isActive` is not stock. Actual media stays deferred.
Loading retains route context with layout-matching skeletons; errors have an
explicit retry, empty parents direct shoppers to children, and missing slugs have
the dark category-specific HTTP 404. See `frontend/README.md` for freshness,
scale and route rules, and the manifest for fixture-backed visual evidence.

### Product detail (#65)

*References: `arvutitark_product_page` — gallery, a main column with key-spec
chips and prose, and a bordered purchase panel, with delivery and availability in
a separate lighter panel **below** it. `iDeal_product_page` shows variant
selection as its own bordered group (colour swatches with labels, storage as
chips) — the closest model for the variant dimensions in #97 — and likewise puts
delivery and trade-in information below the purchase decision.
`1a_product_page` is the dense counterpart, still with a single clear primary.*

*For long specifications, two workable treatments:
`arvutitark_product_page_description` stacks full-width label/value tables under
plain headings; `1a_product_page_description` uses tabs and three columns. The
stacked version is simpler — prefer it unless the content genuinely needs
separating.*

- The gallery and the purchase panel are the two anchors; everything else is
  secondary.
- **This page has a genuine primary action.** *Add to cart* takes primary
  emphasis — see §3.
- An unavailable variant must not look purchasable. Use `--danger`/`--warning`
  for that signal.
- *Example:* two columns on desktop (gallery left, sticky purchase panel right),
  stacking to gallery-then-panel on mobile.

### Forms (#66–#68, #71, #74)

*References, for **sign-in (#66)**: `IDeal_login` puts a narrow form card beside
a visually quieter panel whose only content is the secondary "create an account"
path; `1a_login` ranks the same two paths the other way round inside one card,
with federated sign-in detached alongside. Both rank the paths rather than
weighting them equally — which one leads is a choice, not a rule.*

*For **registration (#67)**: `iDeal_registration` is one column grouped into
labelled fieldsets inside a card, with a primary action and a quiet secondary
beside it. `1a_registration` is a warning rather than a model — its consent and
legal text outweighs the form itself. Keep #67's CAPTCHA and consent handling
honest but far shorter.*

- **Convention:** use HeroUI form components. They carry label association,
  description/error wiring and focus styling already.
- Single column. Labels above fields. Errors below the field they belong to, in
  `--danger`, and announced — never colour alone.
- Submit is the primary action; cancel is quiet or a plain link.
- Disable submit only while a request is in flight, and say why.
- Respect `--control-height` so fields line up with shell controls.

### Cart and checkout (#73, #74)

*References, for the **cart page (#73)**: `arvutitark_shopping_cart_page` ranks
two forward paths — a solid "buy as a guest" above a quiet "log in" — beside a
summary panel. `iDeal_Shopping_Cart` shows line items as rows with the chosen
variant attributes as quiet label/value pairs, and a totals panel with the tax
breakdown; note also what makes it heavy, per-item warranty radio lists that
ByteCore has no equivalent of. `1a_shopping_cart` puts totals below instead of
beside. `1a_shopping_cart_empty` is the **empty cart**, and the most immediately
useful of them: it keeps the page's structure, says plainly that it is empty,
disables the forward action and offers a way back to the catalog.*

*For the **global cart preview (#73)**:
`arvutitark_shopping_cart_left_rail_popup_variant` shows a side panel that
summarises lines and totals and offers one way forward without duplicating the
cart page. `iDeal_main_page_2` and `iDeal_product_list` show the simpler
affordance — a count badge on the header icon, including an honest `0`. For
**add-to-cart feedback**, `arvutitark_success_moveing_item_to_cart_with_suggestions`
offers only the forward path, while
`1a_success_moveing_item_to_cart_with_suggestions` ranks "continue shopping"
against "view cart"; both confirm in place rather than navigating away.*

*For **checkout (#74)**: `arvutitark_payment_workflow` is a **single page** —
shipping, payment and contact details as stacked bordered cards beside a
persistent order summary, under a reduced header — which matches #74's
single-page scope. The three `1a_payment_workflow*` captures show the multi-step
alternative (a step indicator, navigation removed entirely, one decision per
step, a dependent sub-form appearing in place when a delivery method is chosen).
Read the 1a captures for how each step is composed rather than as an argument for
splitting the flow. These describe only what the captures show, not how those
sites behave underneath.*

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

*References: `arvutitark_main_page` — promo cards, then a row of category tiles,
then promoted products, with content lifting onto a light panel; the dark-theme
example. `iDeal_main_page_2` — a horizontally scrollable row of product-type
entries (image above a plain text label, no cards or borders) acting as the
catalog entry point, then a small number of featured products with one action
each. `iDeal_main_page_1` shows the opposite emphasis: a single full-bleed hero.
`1a_main_page` shows the cost of the dense approach — four full-width bands
before any content. `arvutitark_footer` is useful for #87, once there are real
policy pages to link.*

- The current home page is a **temporary holding composition**, not a design to
  preserve. #86 replaces it outright.
- Whatever replaces it must still be honest: no invented products, prices or
  discounts ahead of real data.
- Categories are the most useful first row once #63 lands — they are navigation
  the customer actually needs, not merchandising filler.
- **Required:** meaningful category entry points plus real, accurately labelled
  product discovery sections. The vocabulary of permitted and forbidden section
  labels is in §9.2 — a section may only claim what a real query computed.

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
- **Popover content was intentionally sparse while there was nothing real to
  list.** That is no longer the case: real categories exist, and the single
  narrow column the panel grew into was **rejected by the owner**. §9.1 replaces
  it with grouped navigation. **Growing the panel is a layout change and does not
  by itself change its semantics** — a wider surface, or one with a dimmed
  backdrop, can still be nonmodal. Keep it nonmodal unless you decide it should
  contain focus and block background interaction; if you do decide that, make it
  an explicit choice in that ticket and verify the focus and dismissal behavior
  that follows.
- **The compact "Search" link was honest while search did not exist.** It is no
  longer acceptable as a final state — §9.6 requires a real input in the header.

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
approval promotes a capture to baseline. **No capture in `baseline/` is
owner-approved today**: the original `issue-61-*` set was approved at `aef4673`,
but every file was replaced when the shell and catalog changed, and approval is
not inherited by a replacement. A third status now also applies —
**Owner-rejected composition** — for captures whose layout the owner has
explicitly turned down. See the manifest.

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

## 9. Navigation and discovery requirements

**Status:** owner correction, 2026-10-06. These are **Conventions** — binding
unless the ticket records an explained departure. They deliberately constrain
*what must be navigable and honest*, not pixel geometry: column counts, exact
widths, ordering and visual rhythm remain yours to choose.

Owning tickets: **#61** (shell navigation and the search slot), **#63**
(catalog root, category pages, shared results), **#64** (search input behavior,
facets, suggestions), **#86** (home). §9.8 gives the sequence.

### 9.0 What the backend can actually support

Design only against this. Everything else is forbidden as fabrication.

| Capability | Real API | Honest label |
|---|---|---|
| Taxonomy | `categories` — flat list, `parent` only, **no** `children`, description or image | Category names only. Build the tree client-side. |
| Category filter | `filters.categoryId` — **direct membership only**, never expanded to descendants | A parent page shows only its own direct products and must say so |
| Price filter | `filters.minPrice` / `maxPrice` (variant-level) | "Price" |
| Attribute filter | `filters.attributes[{name,value}]` — repeated names **overwrite**, so one value per attribute | The real attribute name |
| Sorting | `RELEVANCE` (meaningless without a query), `PRICE_ASC`, `PRICE_DESC`, `RATING_DESC` | "Relevance", "Price: low to high", "Price: high to low", "Highest rated" |
| Facet counts | `facets.categories[{id,name,count}]`, `facets.attributes`, `facets.price` | Counts are over the **already-filtered** population with **no self-exclusion**, and are direct-membership. `facets.price.max` is skewed (open #38 item) — don't present it as an exact bound |
| Ratings | `Product.averageRating` (nullable), `ratingCount` | Show the real value and count, or nothing |
| Suggestions | `productSearchSuggestions(query, limit)` → `productId/name/slug` | Product names only — **no** price, image or category comes back |

**Not available, therefore never shown:** stock or availability counts
(`inStock` exists as a filter but public stock does not — do **not** offer an
"In stock" facet while cards read "Availability unknown"); a featured/promoted
flag; popularity, view or sales counts; discounts, campaigns or previous prices;
personalization or recommendations; a newest-first sort (`createdAt` exists on
`Product` but there is no `ProductSort` value for it); brand (until #97);
category descriptions, icons or imagery.

### 9.1 Category navigation panel — replaces the narrow dropdown

The header Catalog panel must present the taxonomy as **informative grouped
navigation**, not a single narrow column of every category.

- **Grouped.** Each top-level category is a group: its name is a heading **and a
  link to its own page**; its children are links beneath it. Roots without
  children still appear as a linked heading.
- **Wide.** The panel spans a useful fraction of the page — multi-column,
  bounded by `--page-max-width` and the real viewport, never wider than
  `calc(100vw - 2rem)`. Columns reflow responsively; a group is never split
  across columns in a way that separates a heading from its children.
- **Leave empty space empty.** No promo tiles, no filler, no rebalanced columns
  when a group is short. (`iDeal_top_nav_bar_1`–`_5` show one template holding
  steady from one link to fourteen.)
- **"All products" appears once**, as an explicit entry in the panel — not as the
  panel's purpose.
- **Interaction:** click, keyboard and touch must all open, operate and dismiss
  the panel. Desktop pointer hover **may** open it as an enhancement only when
  guarded by `@media (hover: hover) and (pointer: fine)`, with a short open
  intent delay and a close delay so a diagonal cursor path does not dismiss it.
  Hover-opening must not move focus. Nothing may be reachable by hover alone.
- **Semantics:** keep it **nonmodal** (§4) unless the ticket explicitly decides
  and verifies otherwise.
- **Mobile** uses the existing modal `Drawer` with the same grouping — groups as
  sections or as accessible disclosures, not a flat list.
- **No counts** in the panel unless they come from `facets.categories`, in which
  case they must be labelled as direct-membership counts.

**Resolved during implementation (#61, §9.8 step 1) — do not re-litigate
without a new owner decision:**

- **Desktop depth stops at children.** A group is a root heading plus *its
  children*, exactly as defined above. Rendering grandchildren and deeper in the
  panel was tried against a 150-category tree and produced the oversized sitemap
  the owner rejected: unbreakable ~1000px groups and roots pushed out of the
  first view. Deeper levels stay reachable from each category's own page and in
  full from the mobile drawer, which keeps full depth behind collapsed
  disclosures.
- **Column count derives from rendered rows, not root count.** Deriving it from
  the number of roots produces sparse, badly balanced columns. Use CSS multicol
  with `break-inside: avoid` on each group; CSS grid aligns rows and leaves large
  holes beside a tall group.
- **Hover was deliberately not shipped.** Hover is optional above, but
  "hover-opening must not move focus" is mandatory when it *is* implemented.
  With HeroUI v3 / react-aria-components that constraint cannot be met: RAC
  `Dialog` auto-focuses its `role="dialog"` element on mount and re-asserts that
  focus, and `useOverlay`'s `shouldCloseOnBlur` then dismisses the panel when the
  focus is moved back. Both workarounds required patching library internals. Add
  hover only if a future HeroUI release makes dialog auto-focus opt-out; click,
  keyboard and touch already satisfy every requirement.
- **"All products" is the panel/drawer's own entry, independent of the
  category fetch.** It renders as a fixed link to `/catalog/all-products` above
  the grouped categories in both the desktop panel and the mobile drawer, and
  stays present even when category data is loading, empty or failed — it is
  not conditioned on the taxonomy rendering successfully.

### 9.2 Home — category entry points and real discovery

#86 replaces the holding page with:

1. **Category entry points.** The real top-level categories as the first
   meaningful region, each linking to its category page; children may appear as
   quiet secondary links. Names only — the API has no category description or
   image, so **do not invent one** and do not substitute a product placeholder
   for a category "tile".
2. **Product discovery sections**, each backed by one real `searchProducts` call
   and **labelled with exactly what that call computed**, reusing `ProductCard`.

**Permitted section labels** (examples, choose your own wording as long as it is
literally true):

- "Laptops — lowest price first" (`filters.categoryId`, `sort: PRICE_ASC`)
- "Highest-priced workstations" (`sort: PRICE_DESC`)
- "Highest rated" (`sort: RATING_DESC`) — render **only** if at least one
  returned product has a non-null `averageRating`, and show that rating and its
  `ratingCount`. Otherwise omit the section entirely; do not fall back silently
  to another ordering under a rating heading.

**Forbidden section labels:** *Featured*, *Popular*, *Trending*, *Best sellers*,
*New arrivals*, *Recommended for you*, *Deals*, *Our picks*, *Staff favourites*
— none of these corresponds to anything the backend computes.

State each section's selection rule in visible copy or an adjacent note (#86
already requires a documented selection source). Keep the sections few; with a
small catalogue the quieter answer is the right one. Remove the temporary
"Available now" list as the features it names become real.

### 9.3 Catalog root — compatibility route, not a second discovery surface

**Status: superseded, owner correction.** The requirement below ("`/catalog` is
a category and subcategory discovery page") was implemented in #63 step 2 and
then **rejected by the owner**: a standalone discovery page sitting behind the
header megamenu duplicates what #61 already built and was not what the owner
wanted. Category and subcategory discovery now lives **only** in the header's
catalog megamenu (§9.1). `/catalog` itself is a thin compatibility route with
no discovery content of its own — do not revive the standalone discovery page
or its `catalog-discovery.tsx`/`catalogDiscoveryHref()` without a new, explicit
owner decision.

**Current, binding behavior:**

- A bare `/catalog` request (no `page`/`view` query) redirects to the homepage
  (`/`). This is a plain, unconditional redirect — it does not depend on #86's
  home redesign landing first, and it must never auto-open an overlay instead.
- Bookmarked/legacy `/catalog?page=…`/`?view=…` links keep working exactly as
  before #63 step 2's reversal: `/catalog` forwards the exact query string
  (including invalid values) to `/catalog/all-products`, which still owns the
  existing invalid-URL recovery flow.
- `/catalog/all-products` (the bounded, unfiltered listing) and `/catalog/<slug>`
  (category results, direct-membership semantics, unchanged) are unaffected —
  both predate this reversal and survive it unchanged.
- The breadcrumb's "Catalog" crumb is a **plain, non-linked label**. There is no
  discovery destination to link it to, and linking it to the redirecting bare
  route would just bounce the visitor to the homepage from inside a category
  page — worse than a non-interactive label.
- The reserved `all-products` static segment still shadows `/catalog/<slug>`
  for that exact slug (§9.4's table note, unchanged); audited against the real
  taxonomy, no category currently uses that slug.

**Superseded original requirement, kept for history — do not implement:**

> `/catalog` is a category and subcategory discovery page. A flat grid of
> unrelated products is not the default shopping entry. Lead with the taxonomy:
> top-level groups, each heading linking to its category, children listed
> beneath, reusing the already-fetched taxonomy. "All products" stays available
> as an explicit, visibly secondary option. Keep the breadcrumb, the heading and
> the honest copy about direct membership.

### 9.3a History — why the catalog root went from a listing, to its own
discovery page, to a compatibility redirect

**#63 step 1 → step 2 (superseded by §9.3 above):** The previous root behavior
(an unfiltered listing at bare `/catalog`) relocated to the dedicated static
route **`/catalog/all-products`**, rather than to `/search` with no query
(§9.4's recommended-routes table predates this decision). Reasons, still valid
for why `/catalog/all-products` exists as its own route even though the
discovery page above it did not survive:

- A query-parameter marker (e.g. `?products=1`) is ambiguous exactly when a
  control returns to its default — switching the view toggle back to grid at
  page 1 omits every param, so the marker disappears and the page would
  silently fall back to whatever renders at the bare route. A stable path has
  no such failure mode.
- `/search` is reserved for step 4/5's query-driven results surface (§9.6);
  conflating "no query" on that route with "all products" would make the
  search URL contract read two different ways depending on whether `q` is
  present, which §9.4 explicitly rules out ("a second, differently-behaving
  shopping interface is not" acceptable).
- **Accepted trade-off:** `/catalog/all-products` is a reserved static segment
  that takes routing priority over `/catalog/<slug>`, so a real category whose
  slug is ever `all-products` becomes permanently unreachable at `/catalog/<slug>`.
  No such category exists today (audited against the live taxonomy). If one is
  ever introduced, this reservation must be revisited.
- Bookmarked/legacy `/catalog?page=…`/`?view=…` links still work: `/catalog`
  forwards their exact query string (including invalid values) to
  `/catalog/all-products`, so the existing invalid-URL recovery flow is
  preserved at the new address.
- `/search` with no `q` is unresolved by this step — it belongs to step 4/5
  (§9.6). Whatever it does, it must not become a second "all products" entry
  with different behavior; redirecting it to `/catalog/all-products` is one
  reasonable option, left to that step.

### 9.4 Category and results pages — sidebar, filters, one implementation

**Desktop (≥48rem, or a wider breakpoint if you justify it):** two regions.

- A **left sidebar** carrying (a) category navigation *in context* — an "All
  products" entry, ancestors, the current category marked `aria-current`, and its
  children or siblings — and (b) the **real filters** from §9.0, with selected
  values reflected as removable chips and a "Clear all".
- A **compact product area** to its right. With the sidebar present, prefer
  three columns at desktop rather than squeezing four.

**Mobile (<48rem):** the same category navigation and filters in an **accessible
modal drawer**, reusing the existing `Drawer` behavior (focus containment,
`inert` background, Escape and backdrop dismissal, focus restoration). Its
trigger states how many filters are active. Do not simply stack the desktop
sidebar above the results.

**One results implementation.** Category browsing and search render the **same**
results components — toolbar, result count, grid/list toggle, cards, pagination,
skeletons, and the empty/error/invalid-URL states — and read the **same
URL-backed state**: query, filters, sort, view and page. A dedicated search URL
is fine; a second, differently-behaving shopping interface is not.

- Page resets to 1 when the query or any filter changes; pagination and view
  changes do not reset the others.
- Back/forward restores every part of the state.
- Pagination clicks are immediate — never debounced.
- Default sort when there is no query stays an explicit deterministic choice
  (today: price, low to high) and is stated in the toolbar.

**Recommended routes** (an implementer may choose otherwise, but must then
document it and must still have exactly one results implementation):

| Route | Renders |
|---|---|
| `/catalog` | No discovery content of its own (§9.3, superseded): redirects to `/` (bare request) or forwards legacy `page`/`view` query to `/catalog/all-products` |
| `/catalog/all-products` | "All products" — the shared results surface, no category filter (§9.3) |
| `/catalog/<slug>` | Category results — sidebar + results |
| `/search?q=…` | Search results — the same sidebar + results |

### 9.5 Reduce placeholder dominance

The no-photo-assets policy and the code-rendered placeholder **stay**. What
changes is how much of the page they occupy.

- The media area must **not be the dominant element of a card**. Keep one
  consistent ratio across cards, but cap it — a shorter ratio and/or a
  `max-height` — so the name and price carry the card. A quick check: at the
  desktop grid width, the placeholder should not be taller than the card's text
  block.
- Drop the large `404` numeral from product cards. "404" reads as a page error
  next to a product that loaded correctly. A single quiet line — or the muted
  `ImageOff` glyph alone with an accessible name on the `role="img"` wrapper — is
  enough.
- Keep the placeholder on `--surface` and quiet; it must never compete with the
  price.
- Do **not** compensate by inventing content to fill the card. There are no
  specs, brands or ratings on a card until #97/#79 supply them.
- The real-media switch (#96) must be able to drop into the same box without
  relayout.

### 9.6 A real search input in the header

The compact "Search" link that only navigates is replaced by an actual input in
the existing `quickSearch` slot (§4) — one mounted, responsive control, not a
parallel one.

- A labelled `role="search"` form with a visible text input and a submit
  control, sharing `--control-height`. It must work **without JavaScript** by
  submitting to the shared results route.
- Desktop: inline in `.store-search-slot`. Mobile: a labelled trigger opening an
  accessible dialog containing the same form, or an inline field where it fits.
  Never hover-only.
- DOM order stays catalog → search → actions → mobile (§4).
- The ~65px header height is not an acceptance test; the header may legitimately
  grow (§4).

**Ownership split.** #61's correction owns the input's presence, labelling,
layout, responsive presentation and plain form submission — it may only ship once
the shared results route exists, so it is never a dead control. **#64 owns
debounced suggestions (250–350 ms, a documented minimum query length, stale
response cancellation), keyboard selection within the suggestion list, the
enhanced submit path, an explicit "see all results" action, and the suggestion
loading/empty/error states.** If the owner prefers the whole input to land in
#64, move the first half — the only hard rule is that a non-functional field
must never ship.

Suggestions carry `name` and `slug` only. Build the row from those fields;
do not add prices, images or thumbnails that the API does not return. Matching
*categories* may be added from the already-fetched taxonomy in a separately
labelled section (`arvutitark_search_on_main_page` shows that shape) — that is
local filtering of real data, not a fabricated result.

### 9.7 What this correction does not change

Tokens (§2), surfaces and accent (§3), overlay semantics (§4), component reuse
(§5), the honesty rules throughout, the HeroUI v3 fixed dark theme, Lucide, the
80rem container as a starting point, and the screenshot lifecycle (§8).

The references in [`docs/references/`](../references/) remain **alternatives,
not requirements**. §9 asks for grouped navigation, a sidebar and a real search
input because ByteCore's own content and the owner's feedback call for them —
not because arvutitark, iDeal or 1a have them. Do not copy their branding,
colour, copy or layout, and do not adopt a capability they have and we do not.

### 9.8 Implementation sequence

Small and sequential; each step is independently reviewable.

| # | Step | Ticket | Depends on |
|---|---|---|---|
| 1 | Grouped category navigation panel + mobile drawer grouping, with an explicit "All products" entry in the panel/drawer itself (§9.1) | #61 | — (taxonomy already fetched) |
| 2 | Megamenu is the sole category/subcategory discovery surface; `/catalog` is a compatibility redirect, not a second discovery page (§9.1, §9.3) | #61, #63 | 1 |
| 3 | Shared results implementation: sidebar, mobile drawer, URL-backed state, compact product area and reduced placeholder (§9.4, §9.5) | #63 | 2 |
| 4 | Real header search input and plain submission (§9.6) | #61 → #64 | 3 (results route must exist) |
| 5 | Query results and real facets in the shared sidebar/drawer (§9.0, §9.4) | #64 | 3, 4 |
| 6 | Debounced suggestions with keyboard selection (§9.6) | #64 | 5 |
| 7 | Home category entry points and labelled discovery sections (§9.2) | #86 | 3 (shared card), 1 |

Each step recaptures the screenshots it invalidates and updates the manifest in
the same commit (§8). The manifest's *Planned invalidation* table already records
which existing captures each step supersedes.

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
