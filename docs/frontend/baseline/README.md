# Approved baseline screenshots

Owner-approved storefront appearance, captured from the **running production
build** at commit `aef4673` (`dev`). These are design documentation — they are
not runtime assets and must never be moved into `frontend/public/`.

Inspect these before changing storefront UI, and compare against them after.
See [`../DESIGN.md`](../DESIGN.md) for the reasoning behind what they show.

## Capture settings

Shared by every image: Chromium 153 (Playwright 1.63), `deviceScaleFactor: 1`,
`colorScheme: "light"` (deliberate — the storefront is fixed-dark via
`data-theme="dark"`, so a light OS preference proves the theme is not
preference-driven), `prefers-reduced-motion` unset, no authentication, no
backend running.

| File | Route | Viewport | State |
|---|---|---|---|
| `01-home-desktop-1440.png` | `/` | 1440×900, full page | Default. Temporary holding page (#86 replaces it). |
| `02-home-mobile-390.png` | `/` | 390×844, full page | Default mobile shell: wordmark, Search, Menu. |
| `03-catalog-popover-desktop-1440.png` | `/` | 1440×900 | Catalog popover open (clicked the `Catalog` trigger). |
| `04-account-popover-desktop-1440.png` | `/` | 1440×900 | Account popover open (clicked the `Account` trigger). |
| `05-navigation-drawer-mobile-390.png` | `/` | 390×844 | Mobile navigation drawer open (clicked `Menu`). |
| `06-catalog-unavailable-desktop-1440.png` | `/catalog` | 1440×900, full page | `UnavailablePage` — the honest not-yet-built route pattern. |
| `07-header-narrow-320.png` | `/` | 320×640 | Narrow-width header. Below 22rem the Search label is visually hidden while keeping its accessible name. |

No console errors, runtime errors or hydration warnings occurred during capture.

## Reproducing

Playwright is **not** a project dependency — installing it would change
`package.json`/`bun.lock`. Install it outside the repository:

```bash
# once, outside the repo
mkdir -p /tmp/bytecore-pw && cd /tmp/bytecore-pw
npm install playwright          # browsers cache in ~/.cache/ms-playwright
```

```bash
# serve the production build
cd frontend
bun run build
PORT=3100 bun start
```

Then drive the seven captures with a short script against
`http://127.0.0.1:3100`, using accessible-name selectors so the script does not
depend on markup details:

```js
page.getByRole("button", { name: /^Catalog/ })   // catalog popover
page.getByRole("button", { name: /^Account/ })   // account popover
page.getByRole("button", { name: /^Menu/ })      // mobile drawer
page.getByRole("banner")                          // header — never page.locator("header")
```

Capture `01`, `02` and `06` with `fullPage: true`; the overlay states with the
default viewport clip.

## Refreshing

Replace an image only when the UI it shows has intentionally changed, and update
the row above in the same commit. Keep the set small — add a new image only for
a genuinely new state worth preserving, not for every page.
