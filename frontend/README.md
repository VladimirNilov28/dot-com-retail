# Frontend storefront shell

Responsive ByteCore storefront shell for [#61](https://github.com/VladimirNilov28/dot-com-retail/issues/61),
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
48rem, a HeroUI **Menu** button toggles an in-flow disclosure: Tab moves naturally,
Enter/Space toggle, Escape closes and restores trigger focus, and navigation closes
the panel. It is not a dialog and does not trap focus. Breakpoint changes close
the mobile panel and move focus out of controls becoming hidden. At very narrow
CSS widths (including 200% zoom on mobile), branding and the trigger stack and
long text wraps instead of being clipped.

`StoreHeader` accepts optional `quickSearch` and `cartPreview` React nodes as
composition points for #64 and #73. Both are absent today; Search and Cart remain
ordinary links. Do not add placeholder interactive overlays to those slots.

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

Open <http://localhost:3000>. No backend, database, Docker services, environment
variables, or secrets are needed for this demonstration. There is therefore no
frontend `.env.example` for this ticket. The repository root `.env` configures
infrastructure, not this application.

| Command (inside `frontend/`) | Purpose |
|---|---|
| `bun install --frozen-lockfile` | Reproduce the committed dependency graph without changing the lockfile |
| `bun dev` | Start the development server |
| `bun run lint` | Run ESLint with Next.js core-web-vitals and TypeScript rules |
| `bun run typecheck` | Generate Next.js route types, then run `tsc --noEmit` |
| `bun run build` | Create and type-check the production build |
| `bun start` | Serve the existing production build |

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
  disclosure; `FoundationDemo` retains its existing interactive boundary.
  Both disclosures start collapsed identically on server and client.
- The page has a keyboard-visible skip link, semantic headings, visible HeroUI
  focus styling, and an accessible disclosure with `aria-expanded` and
  `aria-controls`. The button sends no network request.

## Shared custom UI icons

Use **`lucide-react` 1.52.0** for custom storefront and future admin UI icons.
Import only the icons needed via direct named imports; do not import the icon
namespace or add a dynamic icon loader, another custom icon library, or an
external icon API/CDN. Keep HeroUI's built-in internal icons and authentic
provider/brand logos. Do not use emoji or text glyphs as interface icons.

The shell uses `Menu` / `X` on the disclosure trigger and `Search`, `UserRound`,
and `ShoppingCart` beside navigation labels. Home, Catalog and ByteCore branding
remain text-only. Icons use the shared `.store-icon` class (1.25rem / 20px at the
default font size), stroke width 2, and Lucide's `currentColor` stroke, inheriting
the existing semantic dark-theme colors and active states.

Decorative icons must have `aria-hidden="true"` and `focusable="false"`.
Keep useful visible labels; an icon-only button must have an accessible name on
the **button**, not the SVG. The mobile trigger retains its visible **Menu**
label/accessibility name in both states, with `aria-expanded` indicating whether
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
navigation (including the current route), browser history, and no focus trap.
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
