# Visual reference captures — two Estonian electronics stores

Screenshots collected by the owner as the **visual brief** for ByteCore. They
cover the same purchase journey in two very different stores, so the set shows a
*range* rather than a single target:

- **iDeal by C&C** (`shop.cec.ee`) — a focused Apple reseller. Minimal, spacious,
  few controls, light theme throughout.
- **1a.ee** — a broad-range marketplace. Dense, promotion-heavy, a dark header
  over light content, a persistent category sidebar.

Both are captured at home → catalog navigation → listing → product → cart, plus
sign-in and registration. The value is in comparing them: the same problem solved
at two different densities.

## Read this first

- **These are not ByteCore.** They are not a baseline, not a before-state and not
  a target to reproduce. The approved ByteCore appearance lives in
  [`../../docs/frontend/baseline/`](../../docs/frontend/baseline/); the design it
  records is explained in [`../../docs/frontend/DESIGN.md`](../../docs/frontend/DESIGN.md).
- **Neither reference is a dark storefront.** Take *structure* — grouping,
  ranking, spacing, what gets emphasis — never surface treatment. White cards on
  grey, and the near-black-on-white type both rely on, do not transfer. In
  ByteCore's dark theme a filled card reads much heavier than it does here.
- **Take patterns, not branding.** Do not copy colours, wordmarks, imagery or
  copy — these are other companies' commercial sites.
- **Don't combine every feature.** Between them they carry far more
  merchandising, financing, loyalty and service upsell than ByteCore has or
  needs. Pick what suits the current feature state and leave the rest.
- The storefront's own identity — HeroUI v3 dark theme, Lucide, restrained
  surfaces, deliberate emphasis — takes priority wherever they disagree.

## How to use the pair

iDeal is the **restraint** end, 1a.ee is the **density** end. ByteCore sits
between them and much nearer iDeal today, because it has almost no catalogue
depth and no merchandising.

When the two disagree, prefer the quieter solution unless real data justifies
the density. 1a.ee shows what becomes *necessary* at scale — faceted filters,
result counts, page-size controls, a persistent taxonomy — not what to build
first. It is also a useful warning: several of its pages have three or four
elements competing for the same attention.

## Journey coverage

| Page type | iDeal (restrained) | 1a.ee (dense) |
|---|---|---|
| Home | `iDeal_main_page_1`, `iDeal_main_page_2` | `1a_main_page` |
| Catalog navigation | `iDeal_top_nav_bar_1`–`_5` | `1a_catalog` |
| Listing / results | `iDeal_product_list` | `1a_product_list` |
| Product detail | `iDeal_product_page` | `1a_product_page`, `1a_product_page_description` |
| Cart | `iDeal_Shopping_Cart` (filled) | `1a_shopping_cart_empty` (empty state) |
| Sign in | `IDeal_login` | `1a_login` |
| Register | `iDeal_registration` | `1a_registration` |

Captures are full-page at roughly 1880×990 with browser chrome included.

## iDeal — pages

| File | Page | Worth studying |
|---|---|---|
| `iDeal_main_page_1.png` | Home (EN, top) | **Maximum header restraint.** Logo · a flat row of top-level category links · a separator · secondary links (Services, Support, B2B) · icon-only actions including **search — no field at all**. Below it one promo strip and a single full-bleed hero: product name, a price line, one quiet `Buy`. A useful counterweight whenever a header starts accumulating controls. |
| `iDeal_main_page_2.png` | Home (ET, category row) | **The most relevant model for #86.** A *Toodete valik* row of product-type entries — image above a plain text label, horizontally scrollable, no cards or borders — acting as the real catalog entry point. Beneath it three featured products, each just name · image · one `Osta →`. Note the header variant: a **cart count badge** and icon-only search. |
| `iDeal_product_list.png` | Category listing (MacBook Pro) | **The closest structural model for #63.** Page title above a breadcrumb; a bordered category panel on the left showing the tree *in context* (ancestors, siblings, the active branch expanded, its children indented); a light toolbar with sort, sort direction and a grid/list toggle — and **no filters at all**. Cards carry image · name · "Starting from" · price · colour dots. Proof a listing can ship useful before faceting exists. |
| `iDeal_product_page.png` | Product detail | **The strongest model for #65 and #97.** Breadcrumb · SKU above the title · gallery with a vertical thumbnail rail and prev/next arrows. On the right, variant selection as its own bordered group — **colour swatches with labels, storage as chips** — then two selectable price/financing panels as radio cards. Delivery and trade-in information sits *below* the purchase decision, not beside it. |
| `iDeal_Shopping_Cart.png` | Cart (filled) | **The model for #73/#74.** Line items as rows: thumbnail · name · **chosen variant attributes as quiet label/value pairs** · a small price/quantity/subtotal block · a plain `Remove item` link. On the right a *Totals* panel with a tax breakdown, a primary `PROCEED TO CHECKOUT` and a secondary `RETURN TO STORE`, then a separate promo-code box. Useful warning too: the per-item warranty and protection-plan radio lists make the page heavy — ByteCore has no such upsell and should stay simple. |
| `IDeal_login.png` | Sign in | **A model for #67.** A narrow bordered form card (email, password, one primary `SIGN IN`, a quiet `FORGOT YOUR PASSWORD?` beside it) next to a separate, visually quieter *New Customers* panel whose only content is a secondary `CREATE AN ACCOUNT`. Required fields marked `*`. Two paths, clearly ranked rather than given equal weight. |
| `iDeal_registration.png` | Create account | **A model for #66.** One column, grouped into labelled fieldsets (*Personal Information*, *Sign-in Information*) inside a card, full-width inputs, an opt-in checkbox, then a primary `CREATE ACCOUNT` with a quiet `BACK` beside it. No multi-step wizard, no side panel. |

## iDeal — header navigation states

The same mega-menu opened on five different top-level entries.

| File | State | Worth studying |
|---|---|---|
| `iDeal_top_nav_bar_1.png` | `Store` open | The **sparsest** case: "Our stores" with one link, "Info" with one, "About us" with two. The template does not collapse to a single column or recentre — it holds its grid and leaves the space empty. |
| `iDeal_top_nav_bar_2.png` | `Mac` open | **The best model for #63's category navigation.** A full-width panel drops below the header with three *labelled* column groups — "Shop Mac" (product lines, largest type), "For your Mac" (accessories, smaller), "Our Solutions" (services, smaller). The page behind dims rather than being covered; the active top-level link is underlined. |
| `iDeal_top_nav_bar_3.png` | `iPad` open | The same three groups for a different category — a consistent template, not per-category bespoke layout. |
| `iDeal_top_nav_bar_4.png` | `iPhone` open | The **longest** case: fourteen product-line links in column one. The panel simply grows taller; columns are not rebalanced and the list is not split or scrolled. |
| `iDeal_top_nav_bar_5.png` | `Watch` open | A short case: three accessory links, five service links. The panel shrinks and the leftover space is left empty — no filler, no promo tile. |

**Why this group matters.** ByteCore's Catalog popover is deliberately a small
nonmodal panel because there is nothing real to list yet. When #63 brings actual
categories, this is the natural evolution: a wider panel with labelled column
groups and a clear type hierarchy between product lines, accessories and
services.

Note what it does *not* do — no icons per link, no promo imagery, no nested
flyouts, and no attempt to rebalance columns or fill space when a category is
sparse (`_1`, `_5`) or long (`_4`). The template stays fixed and the content
varies. That restraint is the transferable part.

If you adopt it, keep ByteCore's overlay semantics deliberate (see
[`DESIGN.md`](../../docs/frontend/DESIGN.md) §4): a dimming full-width panel is
closer to modal than the current nonmodal popover, and that choice changes the
focus and dismissal behavior you must implement and verify.

## 1a.ee — pages

| File | Page | Worth studying |
|---|---|---|
| `1a_main_page.png` | Home | The **density** end. A dark utility strip, a dark primary row (logo · a wide search field with an accent submit · account · wishlist · cart), then a promo band, then a persistent category sidebar beside a carousel and offer tiles. Shows both the value of a permanent taxonomy and the cost of stacking four full-width bands before any content. |
| `1a_catalog.png` | Catalog menu open | The alternative to iDeal's mega-menu: hovering a sidebar category opens a **four-column flyout** of sub-categories with "see more" links, over a dimmed page. Scales to a deep taxonomy that would not fit a header row. Useful for #63 if ByteCore's category tree turns out broad rather than shallow. |
| `1a_product_list.png` | Search results | **The reference for #64.** A result count in the heading, a row of refining category chips with counts, then a toolbar with "showing 1–48 of 1291", a sort control, a page-size selector and a grid/list toggle. The left rail is heavily faceted: availability, delivery method, a price range with a slider, product type. Cards show both prices (member and regular), a long descriptive name and spec bullets. This is where ByteCore ends up *eventually* — not where it starts. |
| `1a_product_page.png` | Product detail | Denser counterpart to `iDeal_product_page`: breadcrumb, gallery with thumbnails, a long specification-bearing title, member and regular price side by side, financing panels, optional warranty rows, a quantity stepper, then a strong `В КОРЗИНУ` primary with quiet *like* and *compare* actions beside it, delivery options as icon+text rows and a trust column. Note the ranking: one primary, everything else visibly lighter. |
| `1a_product_page_description.png` | Product detail (specs) | **The model for long specification content.** Specs as three columns of labelled groups (General, Screen, Processor, Memory, …), each a simple two-column label/value table with linked values. Tabs separate product info, manufacturer details and warranty. A clean answer to "where does all the detail go" without crowding the purchase decision. |
| `1a_shopping_cart_empty.png` | Cart (**empty**) | **The most immediately useful capture for #73.** The empty state keeps the page structure — column headers, a delivery-options panel with each method shown as unavailable, a totals panel reading `0,00 €` — and states "your cart is empty" plainly. The forward action is disabled, with a secondary "choose from more products" link offering the way out. Shows an empty state as a real designed state, not a blank page. |
| `1a_login.png` | Sign in | A single narrow card: heading, a bordered email/password group, a quiet "forgot password", a full-width primary, then "no account? Register" beneath it. Federated sign-in sits in a separate, visually detached panel. Contrast with `IDeal_login`, which instead gives registration its own panel. |
| `1a_registration.png` | Create account | The dense counterpart to `iDeal_registration`: the same single-column form, but with consent checkboxes and a long legal block before the submit, plus a dark benefits panel beside it. A warning as much as a model — the legal text outweighs the form. Keep #66's consent handling honest but far shorter. |

## Patterns worth carrying across both stores

- **Every page states where you are.** Breadcrumbs on listing, product and cart;
  the active top-level link underlined in the open menu; the active branch
  highlighted in the category tree.
- **One primary action per decision, with the alternative kept quiet beside it.**
  Sign in / forgot password · create account / back · checkout / return to store ·
  add to cart / compare. The secondary is never hidden, and never equally
  weighted.
- **Supporting information sits below the decision, not beside it** — delivery,
  trade-in and payment detail on the product page; the tax breakdown inside the
  totals panel rather than scattered.
- **Long specification content gets its own region**, usually tabbed, in simple
  label/value tables — it never competes with the purchase panel.
- **Chosen variant attributes travel with the product** into the cart line, as
  quiet label/value pairs.
- **Empty and unavailable states keep the page's structure** and say plainly
  what is missing, rather than collapsing to a blank area.
- **Empty space is left empty.** No filler tiles, no rebalanced columns, no
  decorative panels when a category or a form is short.

## How this informed the current design

Recorded so the reasoning isn't re-derived (full version in
[`DESIGN.md`](../../docs/frontend/DESIGN.md) §1):

- A **compact search affordance is legitimate** — iDeal ships no header search
  field at all, only an icon (`iDeal_main_page_1`). That justified ByteCore's
  labelled Search action while real search (#64) is unbuilt, instead of a
  non-functional field imitation.
- The **catalog entry belongs beside the wordmark** as the one emphasized header
  control — iDeal's leading nav entry, 1a's green `Kataloog` block — not one of
  several competing nav links. That is why ByteCore has a single accent Catalog
  trigger.
- A secondary nav row needs **real** content — services, support, a category
  taxonomy. ByteCore has none until #63, so a second row would be decoration.
  Revisit when real categories exist.
- The **wordmark is the home link** in both, which removed ByteCore's redundant
  `Home` item.

## When to look at them

Before designing a **new kind of page** — a listing, a product page, a cart, a
checkout step, an auth form — where ByteCore has no implemented precedent. For a
change to an existing page, the implemented page and the approved baseline are
the better reference.

Keep this directory as the owner left it: a reference brief, not a growing
archive. These files are documentation and are never served at runtime.

The owner curates this set — files may be added or removed. If the directory no
longer matches the tables above, trust the directory and refresh this file.
