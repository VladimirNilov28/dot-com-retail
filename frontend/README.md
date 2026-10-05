# Frontend foundation

Minimal Next.js App Router application for [#60](https://github.com/VladimirNilov28/dot-com-retail/issues/60).
The home page demonstrates actual HeroUI v3 Cards and a working Button disclosure,
not storefront functionality or simulated business integrations.

## Stack

| Dependency | Version |
|---|---|
| Bun | 1.4.2 |
| Next.js / eslint-config-next | 16.3.6 |
| React / React DOM | 19.2.8 |
| HeroUI React / styles | 3.2.6 |
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
host Spring instructions; do not start a second Spring process.

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
- `src/app/page.tsx` remains a Server Component. Only the small
  `FoundationDemo` needs a client boundary for HeroUI composition and disclosure
  state. Its initial collapsed state is identical on server and client.
- The page has a keyboard-visible skip link, semantic headings, visible HeroUI
  focus styling, and an accessible disclosure with `aria-expanded` and
  `aria-controls`. The button sends no network request.

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

No existing frontend test suite is present. This bootstrap intentionally avoids
introducing a large test framework. Browser automation used during implementation
is reported on #60; the procedure above is the reproducible manual smoke check.

## Version-matched references

- [HeroUI v3 quick start](https://heroui.com/docs/react/getting-started/quick-start)
- [HeroUI components](https://heroui.com/docs/react/components)
- [HeroUI theming](https://heroui.com/docs/react/getting-started/theming)
- Installed Next.js guides: `node_modules/next/dist/docs/`.
  Read these before changing version-sensitive APIs, as required by `AGENTS.md`.
