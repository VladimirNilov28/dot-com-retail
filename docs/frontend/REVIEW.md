# Frontend review checklist and prompt templates

Companion to [`DESIGN.md`](DESIGN.md). Two reusable pieces:

1. a short **implementation reminder** that any ticket prompt can reference;
2. a separate **visual-review prompt** for a stronger model.

Neither is an approval gate. A routine change that follows the established
design needs the checklist, not a review round.

---

## 1. Implementation reminder

Paste or reference this in a storefront UI ticket prompt.

> **Before coding** — read `docs/frontend/DESIGN.md`, **including §9
> "Navigation and discovery requirements"**, which records the owner's
> 2026-10-06 correction and overrides anything earlier that conflicts with it.
> Then look at the baseline screenshots in
> `docs/frontend/baseline/`. **Check `docs/frontend/baseline/README.md`
> for which existing captures your change will invalidate** — a shared header,
> token or component affects screenshots owned by several tickets, and the
> *Planned invalidation* table already maps each correction step to the captures
> it supersedes. Note the status column: captures marked **Owner-rejected
> composition** show a layout the owner turned down; they are before-evidence,
> never a standard to match. Consult the manifest's **scoped owner approvals
> and stale-state records**; replacement images never inherit approval. If this
> is a
> new kind of page with no ByteCore precedent, also study the third-party store
> references in `docs/references/` — its README indexes every capture by page,
> interaction state and relevant ticket. Patterns and density only, never
> branding or layout, and treat them as alternatives rather than requirements.
> Open the closest already-implemented page and reuse its components, classes
> and tokens.
>
> **While coding** — compose the page yourself within the established design.
> Use existing components (`PageContainer`, `UnavailablePage`, `.store-cta`,
> `.store-link`) and tokens (`--control-height`, `--radius-sm`/`--radius-lg`,
> the HeroUI semantic colours) rather than new values. Use HeroUI components and
> Lucide icons (`className="store-icon" size={20} strokeWidth={2}`
> `aria-hidden="true"`). Don't add wrapper components or a design-system layer.
> Missing backend functionality stays visibly missing — no mocked data, fake
> counts or simulated actions.
>
> **After coding** — capture the running production build with Playwright at the
> desktop and mobile widths and interaction states your ticket affects, into a
> **temporary** directory. Inspect every capture alongside the working
> interaction. Fix crowding, weak hierarchy, unnecessary panels and inconsistent
> spacing, then recapture — never document a defect as the baseline.
>
> **Promote** only inspected, accepted captures into `docs/frontend/baseline/`,
> named `issue-<ticket>-<route>-<viewport>-<state>.png`, replacing existing
> files in place. Recapture any other screenshots your change invalidated,
> update their manifest rows (including *Last updated by*), and delete
> superseded files — in the same commit. Mark your new captures
> **"Model-reviewed — awaiting owner approval"**; only the owner promotes them
> to baseline.
>
> **Verify** — `bun run lint && bun run typecheck && bun run build`; keyboard
> and focus behavior (tab order, Escape/backdrop dismissal, focus restoration);
> responsive behavior at 390px and at 200% zoom; loading and error states;
> no console or hydration errors. Update
> `frontend/scripts/storefront-smoke.mjs` for intentional markup changes without
> dropping behavioral coverage.
> **Overlay semantics** — choose modal or nonmodal behavior deliberately.
> Use focus containment and background blocking only for modal surfaces.
> Nonmodal popovers allow focus to move to the surrounding page. Preserve
> HeroUI's appropriate built-in behavior rather than adding a global focus trap.
>
> **Report** — in the issue, state what you built, any intentional departure
> from `DESIGN.md` and why, which checks ran, which screenshots you added or
> refreshed, and what remains unavailable.

### Self-check before finishing

**Navigation and discovery (DESIGN.md §9) — only for catalog, search, home or
shell work. Skip entirely otherwise.**

- [ ] Category navigation is **grouped and informative**, not a single narrow list or a row of filter-like buttons. Headings link to their own category.
- [ ] Every navigation surface works by **click, keyboard and touch**. Hover is an enhancement only, guarded by `(hover: hover) and (pointer: fine)`, and never the sole path.
- [ ] Category discovery lives in the header's catalog megamenu, not a second standalone `/catalog` page; "All products" is present there but explicitly secondary. A bare `/catalog` request is a compatibility redirect, not a discovery surface.
- [ ] Category and search results use **one** results implementation and the same URL-backed query/filter/sort/view/page state. Back/forward restores it; page resets on query/filter change; pagination is immediate.
- [ ] Desktop results have a left sidebar with category navigation **and real filters**; mobile has an accessible modal drawer, not a stacked sidebar.
- [ ] Every filter, sort and label maps to a real backend capability in §9.0. No "In stock" while availability is unknown; no featured/popular/trending/new/recommended/deals; no fabricated counts or category imagery.
- [ ] The media placeholder is **not the dominant element** of a card, and carries no `404` numeral.
- [ ] The header offers a **real search input** that submits without JavaScript — not a link to another page.

**General**

- [ ] One `<h1>`; heading levels not skipped.
- [ ] Exactly one action reads as most important, and it is the right one.
- [ ] No card or panel added purely to fill space.
- [ ] Interactive controls share `--control-height`; radii are `--radius-sm`/`--radius-lg`.
- [ ] Colours come from tokens; state is never colour-only.
- [ ] Content uses `PageContainer`; no extra outer vertical padding.
- [ ] Icons are decorative (`aria-hidden`) and the control has a text accessible name.
- [ ] Every interactive element is reachable and operable by keyboard with a visible focus ring.
- [ ] Modal dialogs/drawers contain focus, block background interaction, support Escape and backdrop dismissal where appropriate, and restore focus to the trigger or a logical fallback.
- [ ] Nonmodal popovers do not trap focus or make the background inert. Verify keyboard access, Escape/outside dismissal and appropriate focus behavior; do not force focus back to the trigger when the user intentionally moves elsewhere.
- [ ] Checked at 390px and at 200% zoom; no horizontal overflow.
- [ ] Loading state matches the real layout's dimensions; error state is actionable.
- [ ] Nothing is simulated; unavailable features say so.
- [ ] Lint, typecheck, build and the smoke script pass.
- [ ] Captured the production build at the affected widths and states, and **looked at every image**.
- [ ] Any defect found in a capture was fixed and recaptured — not documented as-is.
- [ ] Promoted files use `issue-<ticket>-<route>-<viewport>-<state>.png` and replace existing names in place.
- [ ] Screenshots invalidated by shared header/token/component changes were recaptured too.
- [ ] Manifest rows updated (route, viewport, state, revision, covers, status, *Last updated by*); superseded files deleted.
- [ ] New captures marked "Model-reviewed — awaiting owner approval", not "baseline".
- [ ] Captures of a composition the owner rejected were not cited as a standard, and were replaced rather than matched.

**Product details and variants (#65)**

- [ ] Options are actual backend variant records, not fabricated combinations.
- [ ] Price, SKU, attributes and grams all match the URL-selected record in initial HTML and after hydration.
- [ ] Exact cheapest-active default and numeric-ID tie-break are deterministic; repeated/malformed/removed/foreign IDs recover visibly.
- [ ] Shared URLs, refresh, rapid navigation and Back/Forward preserve selection; links work without JavaScript.
- [ ] Inactive is labelled Unavailable, availability stays unknown, and no private inventory is queried.
- [ ] Single/multiple/no-variant/all-inactive and absent optional data are honest; required null price is a service/protocol failure.
- [ ] Real missing lookup returns HTTP 404/noindex, while service failure has separate Retry and a no-JavaScript reload link.
- [ ] Pending price/specifications hide together without footer/layout collapse; keyboard focus remains visible.
- [ ] Missing media uses the existing placeholder; no invented ratings, cart CTA or deferred commerce features.
- [ ] Desktop/mobile exceptional states are inspected; absent rated-browser fixtures are not claimed as aggregate rendering proof.

---

## 2. Visual-review prompt

For a stronger model reviewing screenshots alongside the code and the ticket.
Use it for a significant new page or when a change feels wrong — not for every
routine edit.

> You are reviewing a visual change to the ByteCore storefront
> (`VladimirNilov28/dot-com-retail`, Next.js + HeroUI v3 fixed dark theme + Lucide).
>
> **Inputs:** ticket `#<N>`; the diff or changed files; screenshots of the result
> at desktop and mobile widths, plus any relevant open overlay, loading or error
> state. The screenshots are named
> `issue-<ticket>-<route>-<viewport>-<state>.png`, so each one tells you the
> route, viewport and state it shows.
>
> **Reference:** `docs/frontend/DESIGN.md` (the approved design — **§9 records
> the owner's 2026-10-06 navigation and discovery correction and overrides
> anything earlier that conflicts with it**), `docs/frontend/baseline/`
> (screenshots) and `docs/frontend/baseline/README.md`
> (the manifest: what each image covers and its status, scoped approvals and
> original revisions). Judge consistency against the **conventions** in
> `DESIGN.md`, not against an image. A capture marked "Model-reviewed —
> awaiting owner approval" is work under review, not a standard. A capture
> marked **"Owner-rejected composition"** is a layout the owner turned down:
> treat it as before-evidence, and never report a departure from it as a defect.
> `docs/references/` holds third-party electronics-store captures (arvutitark,
> iDeal and 1a.ee) used as a pattern brief. Use them to judge whether hierarchy
> and density are plausible for a store — **never** to require that ByteCore
> resemble them, adopt a layout they happen to use, or add functionality they
> have. They are not baselines and carry no approval status.
>
> **Review for, in priority order:**
> 1. Functional and accessibility defects — unreachable or unlabelled controls,
>    broken focus or dismissal, colour-only state, overflow, illegible contrast,
>    broken responsive behavior, anything reachable by hover alone.
> 2. Honesty — mocked data, fabricated counts, a filter or sort with no backend
>    behind it (check `DESIGN.md` §9.0), a section label such as *Featured*,
>    *Popular* or *Recommended* that no query computes, or an unavailable feature
>    presented as working.
> 3. **Navigation and discovery requirements** — for catalog, search, home or
>    shell work, check each §9 convention: grouped category navigation in the
>    header megamenu (not a second standalone discovery page); one shared
>    results implementation with URL-backed state; desktop sidebar and mobile
>    drawer; a real header search input; a media placeholder that does not
>    dominate the card. A documented, explained departure is a judgement call;
>    an undocumented one is a defect.
> 4. Hierarchy — is the most important action the most prominent one? Does
>    anything compete with it?
> 5. Consistency with the established design — tokens, control sizing, spacing
>    steps, surface and accent usage, reuse of existing components.
> 6. Composition — crowding, misalignment, unnecessary panels, uneven rhythm.
> 7. Screenshot hygiene — do the supplied captures actually cover the states the
>    ticket changed? Were captures invalidated elsewhere (shared header, token
>    or component) refreshed, per the manifest's *Planned invalidation* table?
>    Are names, statuses and manifest rows correct? Missing or
>    stale evidence is itself a finding.
>
> **Rules:**
> - Report each issue with its **location** (file and line, or the screenshot and
>   region), the **user impact**, and a **concrete suggested correction**.
> - Label each as **Defect** (objectively wrong or inconsistent with the
>   documented design) or **Preference** (a subjective alternative). Preferences
>   are advisory and must not block.
> - Do not propose a redesign, a new design system, extracted abstractions, or
>   changes to functional requirements the ticket specifies. If the ticket's
>   requirements seem wrong, say so separately rather than implementing around
>   them. "Does not look like reference site X" is not a defect.
> - `DESIGN.md` distinguishes **conventions** from **examples**. A different
>   composition is not a defect. A broken convention is — unless the
>   implementer explained the departure, in which case judge the explanation.
>   **Everything in §9 is a convention**, including the items it marks
>   "required": they are navigation and honesty requirements, not a wireframe.
>   Column counts, widths, ordering and rhythm inside them remain the
>   implementer's choice.
> - If you find nothing above Preference level, say so plainly.
> - Your review does **not** constitute owner approval. Captures stay
>   "Model-reviewed — awaiting owner approval" until the owner says otherwise.
> - Judge overlays according to their intended semantics: modal dialogs/drawers
>  contain focus and block background interaction; nonmodal popovers do neither.
>  Do not recommend turning a nonmodal popover into a modal solely to satisfy
>  a generic overlay checklist.
>
> **Output:** a short summary verdict, then a table of
> `Severity | Location | Issue | User impact | Suggested correction`, then any
> separate notes on the ticket itself.
