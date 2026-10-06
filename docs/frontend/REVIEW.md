# Frontend review checklist and prompt templates

Companion to [`DESIGN.md`](DESIGN.md). Two reusable pieces:

1. a short **implementation reminder** that any ticket prompt can reference;
2. a separate **visual-review prompt** for a stronger model.

Neither is an approval gate. A routine change that follows the established
design needs the checklist, not a review round.

---

## 1. Implementation reminder

Paste or reference this in a storefront UI ticket prompt.

> **Before coding** — read `docs/frontend/DESIGN.md` and look at the baseline
> screenshots in `docs/frontend/baseline/`. **Check `docs/frontend/baseline/README.md`
> for which existing captures your change will invalidate** — a shared header,
> token or component affects screenshots owned by several tickets. If this is a
> new kind of page with no ByteCore precedent, also study the third-party store
> references in `.claude/references/` (see its README for what each one shows) —
> patterns and density only, never branding or layout. Open the closest
> already-implemented page and reuse its components, classes and tokens.
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
> **Reference:** `docs/frontend/DESIGN.md` (the approved design),
> `docs/frontend/baseline/` (screenshots) and `docs/frontend/baseline/README.md`
> (the manifest: what each image covers and whether it is owner-approved or only
> model-reviewed). Judge consistency against the **owner-approved** images.
> A capture marked "Model-reviewed — awaiting owner approval" is the work under
> review, not a standard to measure against.
> `.claude/references/` holds third-party electronics-store captures used as a
> pattern brief. Use them to judge whether hierarchy and density are plausible
> for a store — **never** to require that ByteCore resemble them.
>
> **Review for, in priority order:**
> 1. Functional and accessibility defects — unreachable or unlabelled controls,
>    broken focus or dismissal, colour-only state, overflow, illegible contrast,
>    broken responsive behavior.
> 2. Honesty — mocked data, fabricated counts, or an unavailable feature
>    presented as working.
> 3. Hierarchy — is the most important action the most prominent one? Does
>    anything compete with it?
> 4. Consistency with the established design — tokens, control sizing, spacing
>    steps, surface and accent usage, reuse of existing components.
> 5. Composition — crowding, misalignment, unnecessary panels, uneven rhythm.
> 6. Screenshot hygiene — do the supplied captures actually cover the states the
>    ticket changed? Were captures invalidated elsewhere (shared header, token
>    or component) refreshed? Are names and manifest rows correct? Missing or
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
