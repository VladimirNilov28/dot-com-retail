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
   approved-baseline screenshots in [`docs/frontend/baseline/`](../docs/frontend/baseline/).
   Then open the closest already-implemented page and reuse its components,
   shared classes and tokens.
2. **Compose it yourself** within that design. `DESIGN.md` separates
   conventions from examples — follow the conventions, choose your own layout.
   Don't add wrapper components, a design-system layer or new colour/size values
   when a token exists.
3. **Look at the result, not just the diff.** Render it at desktop and mobile
   widths before finishing.
4. **Refine before calling it done:** crowding, weak hierarchy, panels added to
   fill space, inconsistent spacing or control sizing.
5. **Verify:** keyboard reachability and visible focus, overlay dismissal and
   focus restoration, 390px and 200% zoom, no horizontal overflow, relevant
   loading and error states, no console or hydration errors. Keep
   `scripts/storefront-smoke.mjs` passing; update its assertions for intentional
   markup changes instead of dropping scenarios.
6. **Report** any intentional departure from `DESIGN.md`, and why, in the issue
   report.

Missing backend functionality stays visibly missing — never mock data, counts or
actions to make a page look finished.

[`docs/frontend/REVIEW.md`](../docs/frontend/REVIEW.md) holds a self-check list
and a visual-review prompt for a stronger model.
