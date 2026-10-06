# Third-party store references

Screenshots of three Estonian electronics retailers, curated by the repository
owner as a **pattern brief** for ByteCore. They cover much of the same shopping
journey in stores that solve it at very different densities, so the set shows a
*range* of workable answers rather than a single target.

| Store | Character | Theme |
|---|---|---|
| **arvutitark.ee** | A computer retailer with a broad catalogue. Covers the journey most completely here. | Dark header and page frame over light content panels — **the closest analogue to ByteCore's theme** |
| **iDeal by C&C** (`shop.cec.ee`) | A focused Apple reseller. Minimal, spacious, very few controls. | Light |
| **1a.ee** | A broad-range marketplace. Dense, promotion-heavy, deep taxonomy. | Dark header over light content |

## What this directory is, and is not

**`docs/references/` (here)** — third-party inspiration, curated by the owner.
Not ByteCore, not a baseline, not a before-state, and not a target to reproduce.
These files do **not** follow the ticket-linked screenshot naming or recapture
lifecycle that ByteCore's own captures use; their names are the owner's.

**[`docs/frontend/baseline/`](../frontend/baseline/)** — screenshots of the
**actual ByteCore implementation**, each with a review/approval status, an owning
ticket and recapture rules. That is the standard a UI change is measured against.
See its [manifest](../frontend/baseline/README.md).

A third-party capture never becomes a ByteCore baseline.

The approved design itself is described in
[`docs/frontend/DESIGN.md`](../frontend/DESIGN.md).

### How to read them

- **Take structure, not surface treatment.** Grouping, ranking, pacing and what
  gets emphasis transfer. Colours, card fills and shadows do not. Only
  arvutitark is dark; in the light references a white panel on grey carries a
  separation that the same shape would not carry in ByteCore's theme.
- **Take patterns, not branding.** Never copy colours, wordmarks, imagery or
  copy. The product photography in these captures is other companies' content
  and is **not** a source of runtime assets — ByteCore's product-image policy
  remains the shared "404 / Image unavailable" placeholder until admin upload
  exists (#96).
- **They offer alternatives, not requirements.** Nothing here mandates a
  sidebar, a mega-menu, a particular column count, a facet rail or any feature
  beyond what the ticket asks for. Where two references disagree, both are
  legitimate; pick what suits ByteCore's real content and feature state.
- **Don't combine every feature.** Between them they carry far more
  merchandising, financing, loyalty and service upsell than ByteCore has or
  needs.
- ByteCore's own identity — HeroUI v3 dark theme, Lucide icons, restrained
  surfaces, deliberate emphasis — takes priority wherever they disagree.
- Descriptions below record only **what the capture visibly shows**. They are
  not claims about how those sites behave underneath.

## Index

Captures are full-page at roughly 1880×990, desktop width, with browser chrome
included. No mobile captures exist in this set.

### arvutitark.ee — dark

| File | Page / state | Ticket | Pattern worth studying |
|---|---|---|---|
| [`arvutitark_main_page.png`](arvutitark_main_page.png) | Home | #86, #61 | A compact primary row on a dark gradient — wordmark · an accent-filled `Products` pill · a wide search field · three equal circular icon actions — above a visibly **quieter** secondary row of utility links. Below: promo cards, a row of category tiles, then content lifting onto a light panel. The clearest evidence in the set that a single emphasized catalog control and one restrained accent read as a real store on a dark background. |
| [`arvutitark_products_catalog_dropdown.png`](arvutitark_products_catalog_dropdown.png) | Catalog panel open from the header | #63 | The accent pill swaps to a close affordance; a full-width panel shows an icon strip of top-level groups above six columns of labelled sub-category links. The top strip stays dark, the links sit on a light panel. Note the restraint: plain text links, no promo tiles, and the panel simply ends with empty space. |
| [`arvutitark_search_on_main_page.png`](arvutitark_search_on_main_page.png) | Search field focused with a query typed | #64 | Live suggestions in one panel split into two labelled sections — `PRODUCTS` (ten full product names) and `CATEGORIES` (five links). Plain text rows, no thumbnails or prices. A realistic shape for a first implementation of global suggestions: cheap to render and honest about what matched. |
| [`arvutitark_products_list.png`](arvutitark_products_list.png) | Category listing (Monitors) | #63 | A dark page header carrying breadcrumb, title and an expandable intro, with results on a light panel below. One toolbar holds a page-size selector, pagination, a grid/list toggle and a sort control, with "Showing 1–40 from 1923" opposite. Cards carry a stock badge, a price block (current price in the accent, previous struck through) and four labelled spec lines. A reminder that spec lines, not photography, carry an electronics listing. |
| [`arvutitark_product_page.png`](arvutitark_product_page.png) | Product detail | #65, #97 | Three columns: gallery with a thumbnail grid · a main column (title, brand, product code, a row of key-spec chips repeating the listing's specs, prose and feature bullets) · a **bordered purchase panel** with price, a monthly-payment line, a collapsed services group, a quantity stepper beside a solid `Add to cart`, and a quieter `Buy now` beneath. Delivery and per-store availability sit in a **separate, lighter panel below** the purchase panel — adjacent but visibly subordinate. |
| [`arvutitark_product_page_description.png`](arvutitark_product_page_description.png) | Product detail, scrolled to specifications | #65 | The simplest treatment of long spec content in the set: full-width two-column label/value tables with zebra striping, stacked under plain headings (*Technical Details*, *Connectivity*, *Extra information*, *Dimensions*), with info tooltips on labels. No tabs. The purchase panel stays in place as they scroll past. |
| [`arvutitark_success_moveing_item_to_cart_with_suggestions.png`](arvutitark_success_moveing_item_to_cart_with_suggestions.png) | Confirmation overlay after adding to cart | #73 | A centred overlay over a dimmed page, split into a dark left half (product image, "Product added to basket!", the quantity and item name, one primary `Proceed to basket →`) and a light right half (delivery note, optional services, a compatible-products carousel). The header cart badge reads `1` behind the overlay. |
| [`arvutitark_shopping_cart_left_rail_popup_variant.png`](arvutitark_shopping_cart_left_rail_popup_variant.png) | Cart preview panel open from the header | #73 | A right-side panel over a dimmed page: a "My basket" heading with a close button, one compact line per item (thumbnail · truncated name · price with the previous price struck through · quantity stepper), then shipping and total pinned to the bottom above a full-width `Proceed to basket`. The panel summarises and offers a way forward rather than duplicating the cart page — a useful split if ByteCore's global cart preview and `/cart` page need different jobs. |
| [`arvutitark_shopping_cart_page.png`](arvutitark_shopping_cart_page.png) | Cart page with one item | #73 | A `MY BASKET / PAYMENT / ORDER CONFIRMATION` breadcrumb doubling as a progress trail; a title with quiet bulk actions (empty, share); one card per line with its shipping date and a collapsible services group; and an **Order summary** panel on the right with per-line totals, subtotal, shipping, a collapsible coupon field and the total. The two forward paths are ranked — a solid `Buy as a guest` above a quiet `Log in`. |
| [`arvutitark_payment_workflow.png`](arvutitark_payment_workflow.png) | Checkout | #74 | **A single-page checkout** — the arrangement #74 specifies. Shipping method as four selectable cards with prices, payment method as tabbed groups of provider logos, and contact details as a private/corporate toggle with a short form, each in its own bordered card, stacked in one column. The order summary stays alongside with the terms checkbox and `Checkout`. The header is kept but reduced; the breadcrumb shows the step. Shipping methods unavailable for the basket are stated in accent text rather than hidden. |
| [`arvutitark_footer.png`](arvutitark_footer.png) | Footer, from the checkout page | #87 | A dark footer: wordmark, legal/registry details, trust marks and payment logos on the left; three labelled link columns (*Contact*, *Müügiinfo*, *Support*) on the right, contact rows as icon + text. Useful for how much a footer can carry once real policy pages exist, and for the fact that it stays quiet rather than becoming another content band. |

### iDeal by C&C — light, restrained

| File | Page / state | Ticket | Pattern worth studying |
|---|---|---|---|
| [`iDeal_main_page_1.png`](iDeal_main_page_1.png) | Home, EN, top of page | #86, #61 | Maximum header restraint: logo · a flat row of top-level category links · a separator · secondary links · icon-only actions, with **search as an icon and no field at all**. Below, one promo strip and a single full-bleed hero with a product name, a price line and one quiet `Buy`. A useful counterweight whenever a header starts accumulating controls. |
| [`iDeal_main_page_2.png`](iDeal_main_page_2.png) | Home, ET, scrolled to the category row | #86 | A *Toodete valik* row of product-type entries — image above a plain text label, horizontally scrollable, no cards or borders — acting as the catalog entry point. Beneath it three featured products, each just name · image · one `Osta →`. The header here shows a **cart count badge** beside the icon-only search. |
| [`iDeal_top_nav_bar_1.png`](iDeal_top_nav_bar_1.png) | Header menu open on `Store` | #63 | The **sparsest** case: three labelled groups holding one, one and two links. The template keeps its grid rather than collapsing or recentring, and the remaining space is simply left empty. |
| [`iDeal_top_nav_bar_2.png`](iDeal_top_nav_bar_2.png) | Header menu open on `Mac` | #63 | A full-width panel below the header with three *labelled* column groups — "Shop Mac" (product lines, largest type), "For your Mac" (accessories, smaller), "Our Solutions" (services, smaller). The page behind is dimmed but remains visible, and the active top-level link is underlined. |
| [`iDeal_top_nav_bar_3.png`](iDeal_top_nav_bar_3.png) | Header menu open on `iPad` | #63 | The same three groups for a different category — a consistent template rather than per-category bespoke layout. |
| [`iDeal_top_nav_bar_4.png`](iDeal_top_nav_bar_4.png) | Header menu open on `iPhone` | #63 | The **longest** case: fourteen links in the first column. The panel grows taller; columns are not rebalanced and the list is neither split nor scrolled. |
| [`iDeal_top_nav_bar_5.png`](iDeal_top_nav_bar_5.png) | Header menu open on `Watch` | #63 | A short case: three accessory links and five service links. The panel shrinks and the leftover space is left empty — no filler, no promo tile. |
| [`iDeal_product_list.png`](iDeal_product_list.png) | Category listing (MacBook Pro) | #63 | Page title above a breadcrumb; a bordered category panel on the left showing the tree **in context** (ancestors, siblings, the active branch expanded with its children indented); a light toolbar with sort, sort direction and a grid/list toggle — and **no facets at all**. Cards carry image · name · "Starting from" · price · colour dots. Evidence that a listing can be useful before faceting exists, if that suits the catalogue. Note also the honest `0` on the header cart badge. |
| [`iDeal_product_page.png`](iDeal_product_page.png) | Product detail | #65, #97 | Breadcrumb · SKU above the title · a gallery with a vertical thumbnail rail and prev/next arrows. On the right, **variant selection as its own bordered group** — colour swatches with labels, storage as chips — then two selectable price/financing panels as radio cards. Delivery and trade-in information sits *below* the purchase decision rather than beside it. The closest model for the variant dimensions in #97. |
| [`iDeal_Shopping_Cart.png`](iDeal_Shopping_Cart.png) | Cart page with items | #73 | Line items as rows: thumbnail · name · **chosen variant attributes as quiet label/value pairs** · a small price/quantity/subtotal block · a plain `Remove item` link. A *Totals* panel on the right carries a tax breakdown, a primary `PROCEED TO CHECKOUT` and a secondary `RETURN TO STORE`, with a separate promo-code box. Also a warning: the per-item warranty and protection-plan radio lists make the page heavy. |
| [`IDeal_login.png`](IDeal_login.png) | Sign-in page | #66 | A narrow bordered form card (email, password, one primary `SIGN IN`, a quiet `FORGOT YOUR PASSWORD?` beside it) next to a separate, visually quieter *New Customers* panel whose only content is a secondary `CREATE AN ACCOUNT`. Required fields marked `*`. Two paths, clearly ranked rather than equally weighted. |
| [`iDeal_registration.png`](iDeal_registration.png) | Create-account page | #67 | One column inside a card, grouped into labelled fieldsets (*Personal Information*, *Sign-in Information*), full-width inputs, required markers, a newsletter opt-in, then a primary `CREATE ACCOUNT` with a quiet `BACK` beside it. No multi-step wizard and no side panel. |

### 1a.ee — dense

| File | Page / state | Ticket | Pattern worth studying |
|---|---|---|---|
| [`1a_main_page.png`](1a_main_page.png) | Home | #86 | The density end: a dark utility strip, a dark primary row (logo · a wide search field with an accent submit · account · wishlist · cart), a promo band, then a persistent category sidebar beside a carousel and offer tiles. Shows both the value of a permanent taxonomy and the cost of stacking four full-width bands before any content. |
| [`1a_catalog.png`](1a_catalog.png) | Category sidebar with one entry hovered | #63 | The alternative to a header menu: an always-present sidebar of icon + label categories, where hovering one opens a wide **four-column flyout** of sub-category groups, each with a heading and a "see more" link. Scales to a deep taxonomy that would not fit a header row. The flyout covers page content rather than dimming it. |
| [`1a_product_list.png`](1a_product_list.png) | Search results with facets applied | #64 | A result count in the heading, a row of refining category chips with counts, then a toolbar with "showing 1–48 of 1291", a sort control, a page-size selector and a grid/list toggle. The left rail carries availability, delivery method, a price range with a slider and product type. Cards show two prices, a long descriptive name and spec bullets. A picture of what faceting looks like once the catalogue justifies it. |
| [`1a_product_page.png`](1a_product_page.png) | Product detail | #65 | The denser counterpart to `iDeal_product_page`: breadcrumb, gallery with thumbnails, a long specification-bearing title, two prices side by side, financing panels, optional warranty rows, a quantity stepper, then a strong `В КОРЗИНУ` primary with quiet *like* and *compare* actions beside it, delivery options as icon+text rows and a trust column. Note the ranking — one primary, everything else visibly lighter. |
| [`1a_product_page_description.png`](1a_product_page_description.png) | Product detail, specification tab | #65 | Specs as three columns of labelled groups (General, Screen, Processor, Memory…), each a simple two-column label/value table with linked values. Tabs separate product info, manufacturer details and warranty. An answer to "where does all the detail go" that keeps it clear of the purchase decision — heavier than arvutitark's stacked tables, and worth it only if the content needs separating. |
| [`1a_success_moveing_item_to_cart_with_suggestions.png`](1a_success_moveing_item_to_cart_with_suggestions.png) | Confirmation overlay after adding to cart | #73 | An overlay over a dimmed page: a success icon and message, the added line with its price, optional warranty rows, then **two clearly ranked actions — a quiet "continue shopping" and a solid "view cart"** — and only below them a related-products strip. Confirms the action and offers both paths without navigating away. Compare arvutitark's version, which offers only the forward path. |
| [`1a_shopping_cart.png`](1a_shopping_cart.png) | Cart page with one item | #73 | A table-shaped cart: column headers (product / price / quantity / sum), one row per item with the SKU under the name, a quantity stepper and a plain remove link. Delivery options and totals sit in two panels **below** rather than beside, and the forward action appears twice — top right and in the totals panel. A different trade-off from `iDeal_Shopping_Cart`'s side summary. |
| [`1a_shopping_cart_empty.png`](1a_shopping_cart_empty.png) | Cart page, **empty** | #73 | The empty state keeps the page's structure — column headers, a delivery panel showing each method as unavailable, a totals panel reading `0,00 €` — says plainly that the cart is empty, disables the forward action and offers a secondary "choose from more products" link. An empty state treated as a real designed state rather than a blank page. |
| [`1a_payment_workflow.png`](1a_payment_workflow.png) | Checkout step 1 — delivery, address method selected | #74 | A **multi-step** checkout, in contrast to arvutitark's single page: a three-step chevron indicator (*Delivery → Payment → Order placed*) with the current step filled, a header stripped of navigation and search, delivery methods as a radio list on the left with price and lead time, the selected method's fields in the middle, and a persistent order summary on the right with a promo-code field and `CONTINUE`. |
| [`1a_payment_workflow_with_dpd_or_omniva_shipping.png`](1a_payment_workflow_with_dpd_or_omniva_shipping.png) | Checkout step 1 — parcel-machine method selected | #74 | The same step with a different delivery method chosen: carrier choice as two selectable cards with the fee on each, then a dependent pickup-point select and a recipient-details form. The method's own fields appear in place without changing the page's shape, and the summary's delivery line reflects the choice. |
| [`1a_payment_workflow_part2.png`](1a_payment_workflow_part2.png) | Checkout step 2 — payment | #74 | Payment methods as a radio list on the left (bank link, wallets, instalments, card, invoice); the selected method's banks as a logo grid; a read-only buyer-details block with an edit link; a terms checkbox; and the total repeated immediately beside the final `КУПИТЬ`. Confirmation detail stays visible at the point of commitment. |
| [`1a_login.png`](1a_login.png) | Sign-in page | #66 | A bordered email/password card with a quiet "forgot password", a full-width primary `ВОЙТИ`, and "no account? Register" beneath it — with federated sign-in buttons in a separate, visually detached panel alongside. Ranks the paths the opposite way round from `IDeal_login`; both are workable. |
| [`1a_registration.png`](1a_registration.png) | Create-account page | #67 | The dense counterpart to `iDeal_registration`: the same single-column form, but with consent checkboxes and a long legal block before the submit, plus a benefits panel alongside. A warning as much as a model — the legal text outweighs the form. |

## Patterns that recur across all three

Where the three agree, the signal is strongest — it holds in both a dark and a
light treatment, and at both low and high density.

- **Every page states where you are** — breadcrumbs on listing, product and cart;
  the active top-level link marked in an open menu; the active branch marked in a
  category tree; the current step marked in checkout.
- **One primary action per decision, with the alternative kept quiet beside it.**
  Sign in / forgot password · create account / back · checkout / return to store ·
  guest / log in. The secondary is never hidden and never equally weighted.
- **Supporting information sits below the decision, not beside it** — delivery,
  trade-in and availability on the product page; the tax breakdown inside the
  totals panel.
- **Long specification content gets its own region** in simple label/value
  tables, clear of the purchase panel — stacked (arvutitark) or tabbed (1a).
- **Electronics listings are carried by labelled spec lines**, not photography.
  Both dense references put three or four specs on every card.
- **Chosen variant attributes travel with the product** into the cart line.
- **Adding to the cart is confirmed in place** by an overlay naming what was
  added and offering a way forward, rather than only changing a badge.
- **An order summary accompanies every money decision**, repeating the line items
  and total rather than asking the customer to remember them.
- **Empty and unavailable states keep the page's structure** and say plainly what
  is missing, rather than collapsing to a blank area.
- **Checkout reduces the shell and shows progress** — 1a removes navigation and
  uses a step indicator; arvutitark keeps a reduced header and a breadcrumb.
- **Empty space is left empty.** No filler tiles, no rebalanced columns, no
  decorative panels when a category or a form is short.

## How these informed the current design

Recorded so the reasoning is not re-derived. The full version is in
[`DESIGN.md`](../frontend/DESIGN.md) §1.

- A **compact search affordance is legitimate** — iDeal ships no header search
  field at all, only an icon. That supported ByteCore's labelled Search action
  while real search (#64) is unbuilt, instead of a non-functional field.
- The **catalog entry is the one emphasized header control**, beside the
  wordmark — arvutitark's accent `Products` pill, 1a's `Каталог товаров` block.
  That is why ByteCore has a single accent Catalog trigger rather than several
  competing nav links.
- A secondary navigation row needs **real** content — services, campaigns,
  support, a taxonomy. ByteCore has none until #63, so a second row would be
  decoration. `arvutitark_main_page` shows what a genuinely quiet one looks like
  when there is something to put in it.
- The **wordmark is the home link** in all three, which removed ByteCore's
  redundant `Home` item.

## When to look at them

Before designing a **new kind of page** — a listing, a product page, a cart, a
checkout step, an auth form — where ByteCore has no implemented precedent. For a
change to an existing page, the implemented page and the approved baseline in
[`docs/frontend/baseline/`](../frontend/baseline/) are the better reference.

The owner curates this set — files may be added or removed. If the directory no
longer matches the index above, trust the directory and refresh this file.
