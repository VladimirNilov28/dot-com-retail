# Frontend storefront shell

Responsive ByteCore storefront shell for [#61](https://github.com/VladimirNilov28/dot-com-retail/issues/61),
typed Hive access for [#62](https://github.com/VladimirNilov28/dot-com-retail/issues/62),
and public catalog browsing for [#63](https://github.com/VladimirNilov28/dot-com-retail/issues/63),
built on the [#60](https://github.com/VladimirNilov28/dot-com-retail/issues/60) foundation.
The HeroUI v3 Card/Button demonstration lives at `/dev/foundation`. Catalog,
search, category-based homepage discovery and product details/variant selection
use real anonymous Hive data. Account, cart and checkout remain unimplemented.

## #86 homepage discovery contract

Home leads with compact **root-only** main-category tiles, preserving the owner's
`3f94137` refinement. Child navigation remains in the megamenu/category pages;
do not restore homepage shortcuts/disclosures. CSS columns keep tiles together without grid-row gaps between
unequal groups: two columns at 390px, four on desktop, one below 352px.
Root links have decorative Lucide icons mapped by semantic slug aliases
(English/Estonian), never database IDs; unknown slugs use the neutral Grid2X2
fallback. Icons are presentation, not backend category metadata. Categories have
no invented descriptions, imagery or product counts. A secondary All products link
continues to use `/catalog/all-products`; `/catalog` remains a compatibility
redirect. The footer owner's catalog-link correction is preserved.

`HomeDiscovery` calls anonymous `searchProducts` at page 0/size 1 for direct
category facets only. Positive-count facet IDs are matched to the real taxonomy,
ordered by the existing English-locale name/ID comparison, and capped at three.
Each selected category uses `HomeCategoryProducts`, fixed page 0/size 4,
`categoryId` and `PRICE_ASC` (backend product-ID tie-break). Sections say
the unchanged taxonomy name with a category-scoped **View all** link. Selection
details belong in this contract, not technical customer-facing copy.
This is a deterministic category sample, not curation, popularity,
personalization or a recommendation. Parent membership never includes children.
No-price products retain the shared Price unavailable state.

The cold-render budget is **five logical Hive operations maximum**: one
request-deduplicated taxonomy shared with the header, one probe and up to three
parallel selections, with at most twelve product cards. There is no per-category
probe loop, unbounded products query or fallback all-products feed. Empty
selections are omitted; safe probe/category/section errors have explicit Retry
and do not masquerade as successful empty data. The bounded discovery result
is fully awaited before returning initial markup: streamed Suspense sections
left real live-data cards hidden without JavaScript. Buffered SSR now renders
every selection visibly without JavaScript, matching catalog rendering.
Initial navigation uses native page loading; explicit Retry transitions retain
context and show disabled **Retrying…** through HeroUI/React Aria's supported
`isPending` accessibility contract. No global loading boundary, client-only
catalog fetching or production fixtures are introduced.

Validated public successes reuse endpoint/origin/input-keyed caching with
**60-second stale-while-revalidate**, not a hard maximum age guarantee. Failures
are logged safely and are not cached as successes or permanent error prerenders.
Raw/private transport stays no-store. Metadata and categories/products are
server-rendered. Existing expanded-header Search is unchanged on home; listing
pages keep their default-visible input. Product links use actual slugs at
`/products/[slug]`, now rendered by #65.

## #65 product details and variant URL contract

`/products/[slug]?variant=<real ID>` resolves one public `ProductDetail` operation.
The server selects price, SKU and specifications from the same actual record;
there is no browser detail fetch, inventory query or added proxy dispatch.
Plain variant anchors are enhanced by Next navigation, preserve other query
parameters, push history and remain usable without JavaScript.

Without a parameter, the default is the exact lowest-priced active variant,
then lowest numeric ID. Empty, malformed, repeated, nonexistent, removed or
foreign IDs recover visibly to that default; a valid-link action canonicalizes
the URL without automatic replacement. Explicit inactive records remain
inspectable and unavailable, not out of stock. All-inactive/no-variant products
have no default and show Price unavailable. Required `price: BigDecimal!`
cannot legitimately be absent: null/missing price is a protocol/service error.

Anonymous product fields include descriptions, categories, aggregate ratings
and variants with price, JSON attributes, activity, SKU, optional barcode/grams.
The schema has no images, brand, dimensions or customer stock. Use the existing
placeholder and unknown availability, omit absent rating aggregates, and defer
media to #96/#81, units/brand/dimensions to #97 and public stock to #95.
No Add to cart until #73; no auth, wishlist, checkout or review submission.

React request caching deduplicates layout/page/metadata lookup. Public successes
use endpoint/origin/slug-keyed **60-second stale-while-revalidate**, not a hard
freshness maximum. Failures and successful missing lookups are not persisted as
successes. Resolve existence before streaming so missing products return a real
404/noindex and initial HTML stays visible without JavaScript. Service failure
renders Product unavailable, HeroUI Retry and a no-JavaScript reload link.
Enhanced loading hides price/specifications together without collapsing layout.

Product fixtures extend the existing exact 9-category/30-product isolated recipe,
not owner data. Import `seedProductFixture` from
`scripts/product-fixture-data.mjs` and call it with
`http://127.0.0.1:4063/graphql` and the privately issued supported setup token.
It enriches existing laptop attributes/grams/description and creates/deletes one
real variant, returning `removedVariantId`; it does not add products/change prices.
Use a fresh matching build/server origin to avoid cached success hiding failures.

```bash
bun test src/lib scripts/catalog-fixture-data.test.mjs scripts/product-fixture-data.test.mjs
PLAYWRIGHT_MODULE=<authorized-external-playwright-index.mjs> \
  FRONTEND_URL=<isolated-production-origin> \
  PRODUCT_REMOVED_VARIANT=<returned-real-ID> \
  CATALOG_TEST_CONTROL=http://127.0.0.1:4064/control \
  BROWSER_ARTIFACTS=<temporary-capture-directory> node scripts/product-smoke.mjs
# Read-only live verification instead:
PLAYWRIGHT_MODULE=<authorized-external-playwright-index.mjs> \
  FRONTEND_URL=<live-production-origin> PRODUCT_TEST_HIVE=http://localhost:4002/graphql \
  PRODUCT_LIVE_SLUG=kingston-fury-beast-16gb-ddr4 node scripts/product-smoke.mjs
```

Only product errors/delays are injected by the bridge; normal data comes from
real disposable Hive. Positive aggregate shapes are unit-covered, but fixtures
are unrated and **rated-browser rendering is not claimed**. Run existing
storefront/catalog/home checks as well; home smoke now requires successful
product destinations and the preserved root-only composition.

### Reproducible isolated catalog verification

Never point the English-slug fixture suite at the owner's Estonian live catalog.
`scripts/catalog-fixtures.mjs` starts independently named disposable Postgres,
the current Spring bootJar and real Hive on loopback ports 5463/8063/4063, plus
a verification-only forwarding/control bridge on 4064. It mounts no owner
database volumes and does not start/reconfigure the main Compose project.
The Hive container uses host networking only to reach the separate loopback
Spring process; its HTTP listener is loopback-bound. Kafka listeners/admin
auto-creation are disabled only in this isolated runner's environment.
Normal bridge requests reach real Hive; selected delays/errors never enter
application production behavior.

```bash
# Build current Spring first, from backend:
./gradlew bootJar
# From frontend, with an already-issued real setup token supplied privately:
CATALOG_SETUP_TOKEN=<private-environment-value> node scripts/catalog-fixtures.mjs
# Alternatively supply CATALOG_SETUP_EMAIL/CATALOG_SETUP_PASSWORD privately;
# the runner uses the supported loopback dev-token flow, not forged authentication.
# --empty starts an isolated empty database without requiring setup credentials.
```

Do not put tokens in source, browser storage, command history or screenshots.
Use environment injection from a private credential source, not the literal
placeholder above. TOTP/AAL2 accounts need a valid already-issued token.
The runner refuses nonempty seed targets and restricts mutations to its isolated
Hive endpoint. It creates the manifest's nine-category/thirty-product recipe
through supported mutations, adding deterministic laptop `ram`/`color`
attributes without changing product counts or prices. On termination it cleans
only its owned child process, exact container IDs/anonymous volumes and unique
temporary configuration directory.

Build/serve frontend with a matching origin and a fresh cache identity for
controlled scenarios:

```bash
HIVE_GRAPHQL_URL=http://127.0.0.1:4064/graphql \
  STOREFRONT_ORIGIN=http://localhost:3164 bun run build
HIVE_GRAPHQL_URL=http://127.0.0.1:4064/graphql \
  STOREFRONT_ORIGIN=http://localhost:3164 bun start --hostname 127.0.0.1 --port 3164
PLAYWRIGHT_MODULE=<authorized-external-playwright-index.mjs> \
  FRONTEND_URL=http://localhost:3164 \
  CATALOG_TEST_CONTROL=http://127.0.0.1:4064/control \
  BROWSER_ARTIFACTS=<temporary-capture-directory> node scripts/catalog-smoke.mjs
```

Catalog smoke now preflights the exact fixture taxonomy/count and requires its
isolated controls before opening a browser. English `laptops` is the intended
fixture slug, not an obsolete application route. Missing fixtures cause an
explicit setup failure; changing the test to a live slug would invalidate its
23-product price/pagination coverage. Full smoke additionally covers supported
search, price/attribute facets, sorts and URL restoration. Unrated rating-sort
fixtures check ID tie-break only, not rated ordering.

### Historical coordinated pass, 2026-10-08 — before #65

The earlier tooling/seeding blocks were resolved. On source `663de45`, the full
catalog smoke, all 11 storefront smoke scenarios and homepage browser matrix
passed against production builds. Anonymous live Hive additionally passed home
category/subcategory/product-destination navigation, header suggestions and
submission, keyboard focus, two-stage Escape and visible no-JavaScript cards.
Native search Escape now dismisses suggestions without clearing the query.
Product destinations are checked as actual slug links; their expected 404 is
not presented as implemented product details.

The isolated matrix used real supported fixtures: 9 categories/30 products,
0/0, 2/1 and 2/0. Only failures/delays were injected by the forwarding bridge;
these are not GraphQL data stubs. Homepage cold counts were exactly one taxonomy,
one probe and three selections; warm reload added zero requests, and every home
operation type really revalidated after 61 seconds. A readiness request to
`GET /graphql` (expected 405) avoids warming taxonomy; `/dev/foundation` does not.
For cold measurement, build a fresh origin during `categories-error`, restore
`normal`, check readiness, then use `HOME_VERIFY_COLD=1` and
`HOME_VERIFY_REVALIDATION=1` with `scripts/home-smoke.mjs`.

For homepage controlled states use a new matching build/server origin for each:
`categories-error`, `home-error` (probe failure), or `home-section-error` with
`categoryId=2` (Accessories in the exact fixture). Run home smoke with the
corresponding `HOME_TEST_STATE=categories-error|probe-error|section-error`.
For `HOME_TEST_STATE=loading`, start with `home-error`; the script switches to
`home-delay` on Retry, verifies pending/disabled feedback, then real recovery.
`HOME_TEST_STATE=empty|sparse|no-products` requires genuinely matching disposable
data, not a fault response relabelled empty. The runner's `--empty` supports
starting those fresh databases; use supported setup mutations only.

Lint, typecheck, generated-operation consistency, production builds and all
115 affected tests (368 Bun expectations) passed. Dependencies did not change.
The screenshot manifest records 29 inspected new/replacement captures, original
approval scopes, real versus controlled provenance and resolved Stale states.
New images await owner approval. Public stock (#95), images (#81/#96), details
(#65), authentication/cart/checkout and genuinely rated ordering remain deferred;
the rating-sort fixture proves only unrated wiring and ID tie-break.

**Before changing storefront UI, read [`docs/frontend/DESIGN.md`](../docs/frontend/DESIGN.md)**
— the approved design, its reasoning and extension guidance — and look at the
approved-baseline screenshots in [`docs/frontend/baseline/`](../docs/frontend/baseline/).
Reusable review checklist and prompt templates: [`docs/frontend/REVIEW.md`](../docs/frontend/REVIEW.md).

## Routes and shell

All storefront pages live in `src/app/(storefront)/`. Its server layout owns the
shared header, one `main#main-content`, PageContainer and footer. Root layout
retains the single skip link and deterministic dark theme.

| Route | Current behavior |
|---|---|
| `/` | Real root-only category tiles and up to three deterministic direct-category product selections |
| `/catalog` | Compatibility redirect to home, or legacy listing query forwarded to `/catalog/all-products` |
| `/catalog/all-products` | Bounded shared results, grid/list, supported facets/sorts and URL-backed state |
| `/catalog/[slug]` | Direct category members, ancestors/child links, real metadata and missing-category 404 |
| `/search` | Default-visible search input and shared URL-backed catalog results/facets/sorts/suggestions |
| `/products/[slug]` | Server product details, real URL-backed variants, HTTP 404 and service-error/Retry |
| `/account` | Honest unavailable page; no fake sign-in |
| `/cart` | Honest unavailable page; no counts, cart contents, preview or checkout |
| `/dev/foundation` | `noindex, nofollow` component playground; not linked from shell navigation |

Every unavailable page has a working return-home link. The ByteCore wordmark is
the home link and carries `aria-current` on `/`, so primary navigation does not
repeat Home. Header links use Next.js navigation with visible current-route and
keyboard-focus states. At widths below 48rem, a HeroUI **Menu** button opens a
modal left navigation Drawer. React Aria contains focus, hides/blocks the
background and restores trigger focus on dismissal. Enter/Space open it; Escape,
backdrop, close button and navigation dismiss it. Desktop Catalog and Account
buttons open nonmodal HeroUI popovers on keyboard or touch, never on hover alone.
Breakpoint changes close overlays and move focus out of controls becoming hidden.
At very narrow CSS widths (including 200% zoom on mobile), branding and the
trigger stack and long text wraps instead of being clipped.

`StoreHeader` accepts the optional `cartPreview` composition point for #73.
Search is implemented by StoreNavigation: listing routes hide the header action
in favor of their own visible SearchForm; other routes expand the accepted
full-width header search, with a `/search` link fallback without JavaScript.
Cart remains an ordinary link; do not simulate a preview.

> **Current correction.** Category discovery uses the grouped header megamenu
> and the #86 homepage entry points; `/catalog` does not have a standalone
> discovery page. Category/search results share the desktop sidebar and mobile
> drawer, and search follows the route-aware placement contract above.
> [`docs/frontend/DESIGN.md`](../docs/frontend/DESIGN.md) §9 records the decisions.

## #61 design follow-up

Historical implementation account below; current route/search/home behavior is
recorded above and in DESIGN.md §9. Do not restore the old holding composition.

The shell borrows electronics-store hierarchy, not branding or content, from
Arvutitark, 1a and C&C: a clear ByteCore identity, a single catalog entry,
compact account/cart actions, and grouped footer navigation.
The Search area is an actual link, not a fake input. Shared slots are mounted
once (Search in the responsive header; cart preview in the desktop actions).
Future #64/#73 implementations must supply their own real interaction/state;
the mobile drawer currently links to the separate Search and Cart pages.

HeroUI surfaces, separators, radii, muted typography and the existing blue accent
are reused, with consistent 20px Lucide icons and restrained spacing. The drawer
and popovers use HeroUI's portal placement and React Aria focus/dismissal behavior;
the installed modal primitive blocks background interaction with `inert`.
Overlay widths fit the actual CSS viewport, including 160px at mobile 200% zoom.
Reduced-motion rules cover portals as well as header controls.

Category names/URLs now come from credential-free Hive data under #95, shared
with listing/metadata. Catalog popover and mobile drawer include real root/child
links; empty or failed category reads are explicit. Account offers only `/account`, not invented
login, orders or settings actions. No promotional merchandising (#86), live search
(#64), cart contents/drawer (#73), product data or authentication is simulated.

Reference access was limited: C&C hierarchy was readable; Arvutitark extraction
was minimal and 1a returned HTTP 403. These sites are not runtime dependencies.

## #61 visual correction

The first #61 shell was functionally complete but read as assembled parts: a
136px header whose right-hand side stacked Account/Cart above
Categories/Home/Catalog, a bordered full-width Search slab in the middle column,
three navigation items (Home, Catalog, Categories) expressing two destinations,
and a foundation headline plus component playground on the customer-facing page.

**Composition considered and rejected.** A two-row header (Arvutitark's and 1a's
literal shape: utility row above a category rail) was rejected because every
reference secondary row carries real content — services, campaigns, a category
taxonomy. ByteCore has none until #63, so a second row here could only repeat
`/catalog` and would be decoration.

**Composition chosen.** One compact ~3.5rem row:

```
ByteCore │ Catalog ⌄ ·················· Search · Account ⌄ · Cart
```

- The wordmark is the home link, as all three references do, which removes the
  redundant `Home` item.
- `Categories` and `Catalog` are merged into one emphasized Catalog trigger. It
  keeps the keyboard/touch category panel #61 and #94 require while expressing a
  single catalog entry, mirroring Arvutitark's `Tooted` and 1a's `Tootevalik`.
- The right cluster is uniform and muted (C&C's restraint). C&C ships a search
  icon rather than a field, which is why a compact search action is honest here
  rather than an input imitation.
- The flexible middle column is the existing `quickSearch` slot. It is empty
  today, so the compact Search action sits at its end; when #64 lands, the real
  field expands into space that already exists.
- **One accent per viewport.** The solid accent is spent on the Catalog trigger,
  so in-page actions (`.store-cta`) are quiet bordered controls that only share
  its `--control-height` metrics.
- DOM order matches visual order (catalog → search → actions → mobile), so tab
  order equals reading order (WCAG 2.4.3).

Shared tokens: `--page-max-width` 72rem → 80rem for future catalog rows,
`--page-gutter` capped at 2.5rem, and a single `--control-height` of 2.5rem
shared by every shell control and page action. The footer mirrors the header —
brand anchored left, link groups hugging the right. The home page uses a
two-column desktop grid so the wide container reads as composition rather than a
left column floating in empty space.

Mobile is deliberately not the desktop composition: `ByteCore … Search · Menu`,
with Catalog, Account and Cart inside the drawer. Below 22rem the Search label is
visually hidden while keeping its accessible name; below 16rem the shell stacks.

Deliberately out of scope: no secondary navigation row, no merchandising (#86),
no product data, no cart counts, no search results. The component playground
moved to `/dev/foundation` (`noindex, nofollow`, unlinked from shell navigation).

## Stack

| Dependency | Version |
|---|---|
| Bun | 1.4.2 |
| Next.js / eslint-config-next | 16.3.6 |
| React / React DOM | 19.2.8 |
| HeroUI React / styles | 3.2.6 |
| Lucide React | 1.52.0 |
| TypeScript | 5.9.3 |
| Tailwind CSS / PostCSS plugin | 4.3.3 |
| ESLint | 9.39.5 |
| React Compiler Babel plugin | 1.0.0 |

HeroUI's required React Aria/date peers are explicitly declared in `package.json`.
`bun.lock` records the resolved dependency graph. React Compiler remains enabled
in `next.config.ts`.

## Install and run

Use **Bun 1.4.2** and **Node.js >=20.9.0** (Next.js CLI scripts use Node).
Install the exact Bun release using the [official instructions](https://bun.sh/docs/installation).

From the repository root:

```bash
cd frontend
bun --version # must print 1.4.2
bun install --frozen-lockfile
bun dev
```

After installing dependencies, `make front-dev` from the repository root runs
the same development server in the foreground.

Open <http://localhost:3000>. Public catalog/shell categories require Hive;
missing configuration has an actionable unavailable state, not fallback products.
Copy `frontend/.env.example`
to `frontend/.env.local` and run the existing Hive/backend stack. The repository
root `.env` configures infrastructure, not this application. Never copy its
credentials into frontend variables.

| Command (inside `frontend/`) | Purpose |
|---|---|
| `bun install --frozen-lockfile` | Reproduce the committed dependency graph without changing the lockfile |
| `bun dev` | Start the development server |
| `bun run lint` | Run ESLint with Next.js core-web-vitals and TypeScript rules |
| `bun run typecheck` | Generate Next.js route types, then run `tsc --noEmit` |
| `bun run build` | Create and type-check the production build |
| `bun start` | Serve the existing production build |
| `bun run graphql:generate` | Validate operations against composed SDL and regenerate operation types/documents |
| `bun run graphql:check` | Fail if generated output is stale |
| `bun run test:graphql` | Run focused mocked transport/scalar/proxy/isolation tests |
| `bun run graphql:verify` | Perform real credential-free reads through the configured Hive endpoint |

Run checks and production serving with:

```bash
bun run lint
bun run typecheck
bun run build
bun start
```

To use a different port without disturbing an existing service:

```bash
bun dev --hostname 127.0.0.1 --port 3100
# Or, after building:
bun start --hostname 127.0.0.1 --port 3100
```

These are standalone host-development commands. The Docker-only, one-command
full-stack reviewer workflow is tracked separately in #59/#94; this foundation
does not implement or claim completion of it. See
[`docs/local-setup.md`](../docs/local-setup.md) for existing infrastructure and
containerized Spring instructions; do not start a second Spring process.

## Rendering and design conventions

- `src/app/layout.tsx` is a Server Component. It renders `class="dark"` and
  `data-theme="dark"` on `<html>` and exports `colorScheme: "dark"` viewport
  metadata. `globals.css` also sets `color-scheme: dark`. The theme never depends
  on device preference, local storage, a mount effect, or a theme toggle.
- `globals.css` imports Tailwind **before** `@heroui/styles`. HeroUI v3 needs no
  HeroUIProvider or v2 Tailwind plugin. There is no hydration-warning suppression.
- HeroUI owns semantic colors such as `--background`, `--foreground`,
  `--surface`, `--muted`, and `--separator`. The fixed-dark override defines a
  lighter blue `--accent`, dark `--accent-foreground`, and matching `--focus`.
  Use their Tailwind utilities rather than introducing page-specific colors.
- Shared sans-serif and monospace system font stacks are defined in `@theme
  inline`. Typography uses responsive heading sizes and a 1.6 body line height;
  builds do not download Google fonts.
- `src/components/page-container.tsx` is a reusable Server Component accepting
  native div props. Its `.page-container` class uses `--page-max-width` (80rem)
  and responsive `--page-gutter` tokens.
- Storefront pages, layouts, header and footer remain Server Components.
  `StoreNavigation` is a small client boundary for route state and the mobile
  overlays; `FoundationDemo` retains its existing interactive boundary.
  All overlays start closed identically on server and client.
- The page has a keyboard-visible skip link, semantic headings, visible HeroUI
  focus styling, and accessible overlay triggers with `aria-expanded`.
  Navigation overlays reuse server-supplied categories without per-category requests.

## #63 catalog contract

> **Partly superseded.** The data contract below still holds, but the owner
> rejected the *composition* it renders. `DESIGN.md` §9 replaces the catalog
> root with category discovery, adds a shared sidebar/drawer results surface for
> category browsing **and** search with URL-backed query/filter/sort/page state,
> introduces the real `ProductSort` values (`RELEVANCE`, `PRICE_ASC`,
> `PRICE_DESC`, `RATING_DESC`) and the real `ProductFilterInput`/`ProductFacets`
> capabilities, and reduces the image placeholder. Update this section as each
> step lands.

`CatalogCategories` fetches the flat category/parent relationships once;
`CatalogListing` uses only `searchProducts` with **20 items and PRICE_ASC**.
The backend supplies zero-based pages; URLs use one-based `page` and optional
`view=grid|list`. Pagination is immediate, without debounce. Categories reset
the page and preserve the view. Invalid/duplicated state has a first-page recovery;
out-of-range pages redirect to the last page.

Category pages show **direct membership**, not descendants. Parent pages provide
child links and explain that scope, including when only their children have products.
Category existence is validated before streaming so missing slugs return HTTP 404.
Products, prices, counts and metadata are server-rendered, including with JavaScript
disabled. Pending navigation uses geometry-matched skeletons and destination feedback;
view transitions retain useful products.

Prices are the exact minimum among active variants. Differing active prices use
“From”; equal prices use one price; no active variants means “Price unavailable”.
`isActive` is never a stock signal. Currency is implicitly EUR, consistent with
`docs/api/guest-cart.md` and `docs/api/checkout.md`; the schema has no currency field.
Decimal strings are compared without floating-point money arithmetic and displayed
to two places using positive half-up rounding. Browsing prices are not checkout quotes.

Inventory is protected under #95: **“Availability unknown” is not completion of
public availability**. Real images remain deferred to #81/#96. Cards share a
code-rendered “404 / Image unavailable” placeholder and make no image requests.
Product links target the planned `/products/[slug]` route (#65), which is not
implemented here. No facets/search (#64), details or cart actions (#73) are simulated.

The server-only loader caches **validated successful credential-free results**
with 60-second revalidation and endpoint/origin/normalized-input cache identity.
React request-local deduplication shares category data with shell and metadata.
Time-based revalidation can serve stale successful data while refreshing; 60 seconds
is not a strict maximum age. Failures/partial results throw inside the cache scope
and are recovered outside it; failed rendering opts out of prerender/full-route caching.
Retry refreshes the route. Core transport and same-origin proxy remain `no-store`;
private/guest data never enter the public cache.

Scale assumption: hundreds of categories within the existing 1 MiB transport budget.
The backend flat category root and per-product variants are unpaginated. There are
no category waterfalls, unbounded `products` listing requests or silent truncation.

Focused checks: `bun test src/lib/graphql src/lib/catalog`. The production
`scripts/catalog-smoke.mjs` additionally needs an **isolated real backend/Hive**
with the fixture prerequisites in the screenshot manifest; its optional
`CATALOG_TEST_CONTROL` bridge supplies verification-only delays/errors, never
production fallback data. Start from a cold public cache for loading checks.

## Shared custom UI icons

Use **`lucide-react` 1.52.0** for custom storefront and future admin UI icons.
Import only the icons needed via direct named imports; do not import the icon
namespace or add a dynamic icon loader, another custom icon library, or an
external icon API/CDN. Keep HeroUI's built-in internal icons and authentic
provider/brand logos. Do not use emoji or text glyphs as interface icons.

The shell uses `Menu` on the drawer trigger, `X` on its close action, `Grid2X2`
and `ChevronDown` on category/account panels, and `Search`, `UserRound`,
and `ShoppingCart` beside relevant navigation labels. Home, Catalog and ByteCore branding
remain text-only. Icons use the shared `.store-icon` class (1.25rem / 20px at the
default font size), stroke width 2, and Lucide's `currentColor` stroke, inheriting
the existing semantic dark-theme colors and active states.

Decorative icons must have `aria-hidden="true"` and `focusable="false"`.
Keep useful visible labels; an icon-only button must have an accessible name on
the **button**, not the SVG. The mobile trigger retains its visible **Menu**
label/accessibility name, with `aria-expanded` indicating whether
the menu is open. Icons are not new actions: Search and Cart remain ordinary
links to the existing honest unavailable pages.

## Browser smoke verification

Check the running production build, not just compilation:

1. With the device/browser preference set to **light**, navigate to `/`, then
   reload. Confirm the page is dark from its first visible render. Repeat with
   a dark preference and with JavaScript disabled; static content stays dark.
2. At desktop width and mobile widths down to **320px**, confirm cards stack
   appropriately, controls remain readable, and there is no horizontal overflow.
3. Open `/dev/foundation`, click **Show details**, confirm the details appear and
   the label becomes **Hide details**, then collapse them again. The playground
   is no longer on `/`.
4. Reload, use Tab to reach **Skip to content** and the HeroUI button, and verify
   visible focus. Activate the button with Enter and Space. Confirm
   `aria-expanded` tracks the visibility of its `aria-controls` target.
5. Inspect the browser console for hydration warnings and runtime errors.
   Confirm Card and Button styles are applied, including the blue button and
   its keyboard focus ring. Repeat with reduced motion enabled.

Also check header/footer and active navigation on every route, direct loading and
reloads, mobile Menu opening/closing, Escape and focus return, closing after
navigation (including the current route), browser history, modal focus containment
and blocked background interaction. Desktop popovers are nonmodal and support
keyboard/touch opening, Escape/outside dismissal and focus return. The header must
stay a single row with all controls on one baseline, and the wordmark must be the
only element marking `/` as the current route.
Check **1440px, 390px, 320px and 200% browser zoom**, with the menu open and closed.
Unfinished pages must say they are unavailable and offer a working return link.
Repeat against both production and development rendering.

Focused automation is in `scripts/storefront-smoke.mjs`. It uses an externally
available Playwright installation/Chromium rather than adding a framework to the
application dependencies. From `frontend/`, with a server already running:

```bash
PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs \
  FRONTEND_URL=http://127.0.0.1:3100 \
  node scripts/storefront-smoke.mjs
```

The harness uses Chromium's extension API to set **actual per-tab browser zoom**,
and checks the changed CSS viewport/device pixel ratio; CSS zoom and pinch
scaling are not substitutes. It reports console/runtime errors and writes optional
screenshots to `BROWSER_ARTIFACTS` when that environment variable is set. Repeat
with the development URL. Exact execution results and limitations are reported
on #61; a documented check is not a claim that it passed.

## Version-matched references

- [HeroUI v3 quick start](https://heroui.com/docs/react/getting-started/quick-start)
- [HeroUI components](https://heroui.com/docs/react/components)
- [HeroUI theming](https://heroui.com/docs/react/getting-started/theming)
- Installed Next.js guides: `node_modules/next/dist/docs/`.
  Read these before changing version-sensitive APIs, as required by `AGENTS.md`.

## Typed GraphQL boundary (#62)

This is one schema-derived, typed-operation + `fetch` approach, not competing
Apollo/urql clients or a global normalized cache. `operations.graphql` is validated
against `infrastructure/hive/supergraph.graphql`; the generated file records its
SHA-256. GraphQL Code Generator's operation plugin generates only the selected
results/variables/enums. Add a real operation, regenerate, supply a runtime decoder
and variable validator, and register browser operations deliberately. Do not
invent fields or assume the federation snapshot makes catalog reads public.

`src/lib/graphql/transport.ts` is the common bounded transport. `browser.ts`
targets **same-origin `/graphql`**: guest reads use the guest header/credentials;
registered public reads use `requestPublic` with credentials omitted.
`server.ts` is marked `server-only`; its server adapter takes an explicit,
request-specific guest cookie/access token or an explicit public context.
Public context attaches no credentials or guest ceremony. It never maintains a shared jar,
obtains a fixture/admin token, or discovers credentials from infrastructure.
The browser adapter imports no server configuration. Shell categories load on
the server; its client interaction boundary receives the resulting flat data.

`src/app/graphql/route.ts` accepts three exact generated **guest read**
documents (development shipping options, guest cart and guest order projection)
and two registered public catalog documents. It rejects arbitrary queries/batches
and unsupported variables. Guest dispatch additionally rejects foreign/missing
Origin, missing guest header, bearer requests and non-JSON bodies. It forwards
only selected `retail_guest_cart` / `retail_guest_orders` cookies, the configured
exact Origin, and `X-Guest-Cart-Request: 1`. No forwarded-host/API-key/session
headers are copied. Multiple validated HttpOnly, host-only, SameSite=Lax,
`Path=/graphql` Set-Cookie fields remain separate; HTTPS requires Secure.
Future mutation documents and authenticated session routing are not registered.
Public dispatch needs no guest header, rejects bearer/foreign Origin requests,
ignores incoming cookies and rejects upstream Set-Cookie. The proxy response and
every raw upstream request remain private/no-store; public successful data caching
exists only in the server catalog loader.

Configure only server variables:

```dotenv
HIVE_GRAPHQL_URL=http://localhost:4002/graphql
STOREFRONT_ORIGIN=http://localhost:3000
```

Origin must also be allowlisted independently in Spring and Hive. Use one
hostname; do not mix localhost and 127.0.0.1. Missing/invalid configuration returns
an explicit safe failure and a value-free server log. Non-local storefronts
require HTTPS. No `NEXT_PUBLIC_` credential/endpoint variables are needed.
Existing Hive request/response propagation was sufficient and left unchanged.

### Scalars, failures and lifetime

`BigDecimal` money is **branded exact text**, decoded from raw JSON numeric tokens
with `lossless-json` before any JavaScript floating-point conversion. Trailing
zeros, large values and exponents are preserved; already-rounded JS numbers are
rejected as money inputs. The proxy serializes money as exact strings. No money
arithmetic or `parseFloat` is used. UUID strings are validated and branded.
Recursive JSON retains numeric tokens as `LosslessNumber`; use the integration's
`serializeJson` to serialize/round-trip them without mistaking ordinary JSON
marker properties for numeric class instances. Do not pass those class instances
directly through an RSC boundary: serialize JSON explicitly, while Decimal/UUID
strings can be passed normally. Only checked GraphQL Int values become JS numbers.

Results are discriminated `success`, `partial` or `failure`. Even HTTP 200
GraphQL errors remain errors; partial results carry a typed decoded selection
and a safe failure, never pretend full success. Schema-invalid/incomplete data
is rejected as a protocol failure rather than cast to a valid selection.
Validation, auth/authorization, resource, conflict, unavailable, transport,
timeout, cancellation, malformed-response and configuration failures have
safe messages. Hive's nested downstream HTTP statuses are recognized. Raw
upstream messages, stack traces, credentials and arbitrary extensions are not
returned by the proxy. Safe classification supports future widget recovery
and route error boundaries; no feature-page error UI is fabricated here.

Requests default to **10 seconds**, accept bounded 1–30,000ms deadlines, and
support caller cancellation through body reading. Requests are limited to 32KiB
and responses to 1MiB, with JSON nesting bounded at 64. Redirects are rejected.
`latestRequest()` aborts earlier work and rejects superseded results even if an
adapter ignores cancellation. Consumers must use it for racing interactive reads;
it is not a live-search UI/debounce implementation (#64).
No automatic retries occur. `retryable` means a read may offer explicit user
recovery for transient failure; validation/auth/cancellation are not blind retries.
Mutations are never automatically replayed. Placement/merge/cancellation tickets
must retain their existing UUID/payload/idempotency and quote-acceptance contracts.

### Rendering and cache matrix (#94)

| Route/data | Rendering and freshness | Isolation/interaction |
|---|---|---|
| Home/shell and unavailable pages | Server-rendered, statically prerendered with successful category data; 60-second revalidation | Failed category rendering is dynamic, never cached error UI |
| Catalog/category | Meaningful server content; validated public success with 60-second revalidation | Credential-free data only; failures/partials not cached |
| Future public home merchandising/about/product | Server content with explicit browsing freshness | Credential-free public data only |
| Future URL-driven search/results | Server-backed URL parameters and explicit uncached/fresh search reads | Small suggestion/filter boundaries; cancellation/latest-result guard |
| Current guest reads and shipping demonstration | Explicit `cache: "no-store"`; `/graphql` response private/no-store | Per-request cookies; no shared session/guest cache |
| Future cart/checkout/account/orders/auth/admin | Request/session-specific server content where the resolved credential contract permits it; always no-store | Isolated private widgets; never hydrate a public cache with private results |
| Cart/checkout price, stock and accepted quotes | Fresh authoritative server validation, not browsing revalidation | Cached price/stock is never an accepted purchase quote |

The installed Next.js 16.3.6 **fetch-cache model** is used; Cache Components is
not enabled. Fetch caching is opt-in, and even authorization/cookie POSTs could
be cached if a caller used `force-cache`, so this adapter deliberately offers
no shared-cache switch on the raw adapter. Catalog caching wraps
validated credential-free successful data, not raw GraphQL HTTP responses.
Normal builds leave existing routes static; catalog/category and `/graphql` are request-driven.
There is no global CSR, static export or blanket dynamic configuration.

### Actual API/auth gaps and verification

Anonymous catalog reads are now allowed by #95; the earlier #62 downstream-401
gap is superseded. Verification includes registered flat categories and bounded
PRICE_ASC listings without credentials. Shipping options are an existing protected
guest-transport **development** catalog, not public product browsing. The live
verification also reads those options and a null credential-free `guestCart`; it
creates no cart or order. A fixture-token read would not prove browsing/login.

The authenticated adapter is a seam for a future request-resolved access token,
not a working Next.js user session. #40 still owns callback/origin/PKCE, token
storage/refresh/logout and onboarding decisions. Backend guest cookies retain
`Path=/graphql`: a browser does **not** send them on `/cart` page requests.
Server-rendered guest content on other paths therefore needs an explicit future
cookie/session-delivery decision; do not widen cookie scope silently or pretend
the server adapter itself solves it. #73 owns merge/mutation/browser cart flows.

Mocked tests cover errors at HTTP 200, partial/malformed data, scalar precision,
timeouts/cancellation/stale results, no automatic mutation replay, header/cookie
selection, Set-Cookie handling and concurrent private isolation. Those are not
live authentication, real-cookie creation or cart/checkout journey evidence.
With the stack already running:

```bash
HIVE_GRAPHQL_URL=http://localhost:4002/graphql \
STOREFRONT_ORIGIN=http://localhost:3000 \
bun run graphql:verify

# Also compare the real Next.js boundary against Hive, after configuring the server:
FRONTEND_URL=http://localhost:3000 \
HIVE_GRAPHQL_URL=http://localhost:4002/graphql \
STOREFRONT_ORIGIN=http://localhost:3000 \
bun run graphql:verify
```

The optional frontend comparison supplies the approved Origin explicitly as a
non-browser client. It is not evidence of a browser session or user login.
For actual browser Origin/cookie-transport verification, run the read-only smoke
at the configured allowed origin (using the external Playwright module):

```bash
PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs \
FRONTEND_URL=http://localhost:3000 \
bun scripts/hive-browser-smoke.mjs
```

It reads shipping options and the null credential-free cart through the Next.js
boundary, without creating guest state or simulating an authenticated journey.
Exact executed results and limitations are reported separately on #62.
