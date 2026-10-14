<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

<!-- Project instructions below are maintained by hand; the generated block above is rewritten by `next dev`. -->

## Storefront UI work

Applies to changes that affect what the storefront **looks like**. Skip it for
backend work, GraphQL transport, tooling and config.

1. **Read first.** [`docs/frontend/DESIGN.md`](../docs/frontend/DESIGN.md) and the
   baseline screenshots in [`docs/frontend/baseline/`](../docs/frontend/baseline/).
   **If your change touches the catalog, search, home page or the shell, §9
   "Navigation and discovery requirements" is mandatory** — it records the
   owner's 2026-10-06 correction (grouped category navigation, discovery before
   a flat product grid, one shared results implementation with a desktop sidebar
   and mobile drawer, a real header search input, a subordinate image
   placeholder) and overrides anything earlier that conflicts with it. It also
   lists exactly which filters, sorts and section labels the backend can honestly
   support.
   Check the manifest (`baseline/README.md`) for which captures your change will
   invalidate — a ticket ID marks ownership, not exclusive coverage, so a shared
   header, token or component affects several tickets' screenshots, and its
   *Planned invalidation* table already maps each correction step to the captures
   it supersedes. Mind the status column and the manifest's **scoped owner approvals**,
   and captures marked **Owner-rejected composition** show a layout the owner
   turned down — they are before-evidence, never a target. Then open
   the closest already-implemented page and reuse its components, shared classes
   and tokens.
2. **For a new kind of page** with no ByteCore precedent (listing, product,
   cart, checkout, auth form), also study the third-party store captures in
   [`docs/references/`](../docs/references/) — its
   [README](../docs/references/README.md) indexes each one by page, interaction
   state and relevant ticket. They cover three stores — arvutitark (dark, the
   closest analogue), iDeal (restrained) and 1a.ee (dense). Take hierarchy,
   density and interaction patterns only; never branding, colour, copy or
   wholesale layout. They are alternatives, not requirements: nothing there
   mandates a sidebar, a mega-menu, a column count or extra functionality. They
   are third-party inspiration, never a ByteCore baseline, and they do not
   follow the capture lifecycle in steps 4–6.
3. **Compose it yourself** within that design. `DESIGN.md` separates
   conventions from examples — follow the conventions, choose your own layout.
   Don't add wrapper components, a design-system layer or new colour/size values
   when a token exists.
4. **Capture the running production build with Playwright** at the desktop and
   mobile widths and interaction states your ticket affects — into a temporary
   directory, never straight into `baseline/`.
5. **Inspect every capture** alongside the working interaction. Fix crowding,
   weak hierarchy, panels added to fill space, inconsistent spacing or control
   sizing, then recapture. Never promote a known-broken or uninspected image
   over an accepted baseline.
6. **Promote and record.** Copy only accepted captures into
   `docs/frontend/baseline/` as `issue-<ticket>-<route>-<viewport>-<state>.png`,
   replacing existing files in place — no timestamps, `-v2` or numbered copies.
   Recapture anything else your change invalidated, update the manifest rows
   (route, viewport, state, revision, covers, status, *Last updated by*) and
   delete superseded files, in the same commit. Mark new captures
   **"Model-reviewed — awaiting owner approval"**; only the owner promotes a
   capture to baseline, and passing checks never does.
7. **Verify:** keyboard reachability and visible focus, overlay dismissal and
   focus restoration appropriate to the overlay's semantics (modal surfaces
   contain focus and block the background; nonmodal popovers do neither), 390px
   and 200% zoom, no horizontal overflow, relevant loading and error states, no
   console or hydration errors. Keep `scripts/storefront-smoke.mjs` passing;
   update its assertions for intentional markup changes instead of dropping
   scenarios.
8. **Report** any intentional departure from `DESIGN.md`, and why, plus which
   screenshots you added or refreshed, in the issue report.

Missing backend functionality stays visibly missing — never mock data, counts or
actions to make a page look finished.

[`docs/frontend/REVIEW.md`](../docs/frontend/REVIEW.md) holds a self-check list
and a visual-review prompt for a stronger model.
