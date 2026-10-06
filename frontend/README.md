# Frontend storefront shell

Responsive ByteCore storefront shell for [#61](https://github.com/VladimirNilov28/dot-com-retail/issues/61)
and typed Hive access for [#62](https://github.com/VladimirNilov28/dot-com-retail/issues/62),
built on the [#60](https://github.com/VladimirNilov28/dot-com-retail/issues/60) foundation.
The home page retains the actual HeroUI v3 Card/Button demonstration. Shopping
features are not implemented or simulated.

## Routes and shell

All storefront pages live in `src/app/(storefront)/`. Its server layout owns the
shared header, one `main#main-content`, PageContainer and footer. Root layout
retains the single skip link and deterministic dark theme.

| Route | Current behavior |
|---|---|
| `/` | Small introduction and existing component playground |
| `/catalog` | Honest unavailable page; no products |
| `/search` | Honest unavailable page; no search form or quick-search overlay |
| `/account` | Honest unavailable page; no fake sign-in |
| `/cart` | Honest unavailable page; no counts, cart contents, preview or checkout |

Every unavailable page has a working return-home link. Header links use Next.js
navigation with visible current-route and keyboard-focus states. At widths below
48rem, a HeroUI **Menu** button opens a modal left navigation Drawer. React Aria
contains focus, hides/blocks the background and restores trigger focus on dismissal.
Enter/Space open it; Escape, backdrop, close button and navigation dismiss it.
Desktop Categories and Account buttons open nonmodal HeroUI popovers on keyboard
or touch, never on hover alone. Breakpoint changes close
overlays and move focus out of controls becoming hidden. At very narrow
CSS widths (including 200% zoom on mobile), branding and the trigger stack and
long text wraps instead of being clipped.

`StoreHeader` accepts optional `quickSearch` and `cartPreview` React nodes as
composition points for #64 and #73. Both are absent today; Search and Cart remain
ordinary links. Do not add placeholder interactive overlays to those slots.

## #61 design follow-up

The shell borrows electronics-store hierarchy, not branding or content, from
Arvutitark, 1a and C&C: a clear ByteCore identity, prominent discovery area,
category entry, compact account/cart actions, and grouped footer navigation.
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

Category names/URLs cannot be populated yet: there is no frontend catalog
integration and today's backend does not allow anonymous catalog browsing.
Categories therefore offers only the supported `/catalog` destination with an
explicit availability note. Account offers only `/account`, not invented login,
orders or settings actions. No promotional merchandising (#86), live search
(#64), cart contents/drawer (#73), product data or authentication is simulated.

Reference access was limited: C&C hierarchy was readable; Arvutitark extraction
was minimal and 1a returned HTTP 403. These sites are not runtime dependencies.

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

Open <http://localhost:3000>. The shell still needs no backend or environment
variables. To enable the separate GraphQL boundary, copy `frontend/.env.example`
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
  native div props. Its `.page-container` class uses `--page-max-width` (72rem)
  and responsive `--page-gutter` tokens.
- Storefront pages, layouts, header and footer remain Server Components.
  `StoreNavigation` is a small client boundary for route state and the mobile
  overlays; `FoundationDemo` retains its existing interactive boundary.
  All overlays start closed identically on server and client.
- The page has a keyboard-visible skip link, semantic headings, visible HeroUI
  focus styling, and accessible overlay triggers with `aria-expanded`.
  Navigation overlays send no business network requests.

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
3. Click **Show details**, confirm the details appear and the label becomes
   **Hide details**, then collapse them again.
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
keyboard/touch opening, Escape/outside dismissal and focus return.
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
targets **same-origin `/graphql`** with the guest header and browser credentials.
`server.ts` is marked `server-only`; its server adapter takes an explicit,
request-specific guest cookie or access token. It never maintains a shared jar,
obtains a fixture/admin token, or discovers credentials from infrastructure.
The browser adapter imports no server configuration. The current shell has no
data dependency and no GraphQL code in its client interaction boundary.

`src/app/graphql/route.ts` accepts only the three exact generated **guest read**
documents: development shipping options, guest cart, and guest order projection.
It rejects arbitrary queries/batches, unsupported variables, foreign/missing
Origin, missing guest header, bearer requests and non-JSON bodies. It forwards
only selected `retail_guest_cart` / `retail_guest_orders` cookies, the configured
exact Origin, and `X-Guest-Cart-Request: 1`. No forwarded-host/API-key/session
headers are copied. Multiple validated HttpOnly, host-only, SameSite=Lax,
`Path=/graphql` Set-Cookie fields remain separate; HTTPS requires Secure.
Future mutation documents and authenticated session routing are not registered.
The route and every current upstream request are private/no-store.

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
| Existing home/shell and unavailable pages | Server-rendered, statically prerendered; no live data to revalidate | Small navigation/demo client boundaries only |
| Future public home/about/catalog/product | Server content; explicit initial 60-second browsing revalidation once anonymous access is supported | Credential-free public data only; do not cache HTTP-200 GraphQL failures |
| Future URL-driven search/results | Server-backed URL parameters and explicit uncached/fresh search reads | Small suggestion/filter boundaries; cancellation/latest-result guard |
| Current guest reads and shipping demonstration | Explicit `cache: "no-store"`; `/graphql` response private/no-store | Per-request cookies; no shared session/guest cache |
| Future cart/checkout/account/orders/auth/admin | Request/session-specific server content where the resolved credential contract permits it; always no-store | Isolated private widgets; never hydrate a public cache with private results |
| Cart/checkout price, stock and accepted quotes | Fresh authoritative server validation, not browsing revalidation | Cached price/stock is never an accepted purchase quote |

The installed Next.js 16.3.6 **fetch-cache model** is used; Cache Components is
not enabled. Fetch caching is opt-in, and even authorization/cookie POSTs could
be cached if a caller used `force-cache`, so this adapter deliberately offers
no shared-cache switch for current operations. Future public caching must wrap
validated credential-free successful data, not raw GraphQL HTTP responses.
The build leaves existing routes static and only `/graphql` request-driven.
There is no global CSR, static export or blanket dynamic configuration.

### Actual API/auth gaps and verification

Anonymous catalog queries currently fail through Hive with GraphQL errors at
HTTP 200 containing downstream 401. Shipping options are an existing protected
guest-transport **development** catalog, not public product browsing. The live
verification reads those options and a null credential-free `guestCart`; it
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
