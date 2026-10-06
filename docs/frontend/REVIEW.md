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
> screenshots in `docs/frontend/baseline/`. Open the closest already-implemented
> page and reuse its components, classes and tokens.
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
> **After coding** — look at the actual rendering at desktop and mobile widths,
> not just the diff. Fix crowding, weak hierarchy, unnecessary panels and
> inconsistent spacing before you call it done.
>
> **Verify** — `bun run lint && bun run typecheck && bun run build`; keyboard
> and focus behavior (tab order, Escape/backdrop dismissal, focus restoration);
> responsive behavior at 390px and at 200% zoom; loading and error states;
> no console or hydration errors. Update
> `frontend/scripts/storefront-smoke.mjs` for intentional markup changes without
> dropping behavioral coverage.
>
> **Report** — in the issue, state what you built, any intentional departure
> from `DESIGN.md` and why, which checks ran, and what remains unavailable.

### Self-check before finishing

- [ ] One `<h1>`; heading levels not skipped.
- [ ] Exactly one action reads as most important, and it is the right one.
- [ ] No card or panel added purely to fill space.
- [ ] Interactive controls share `--control-height`; radii are `--radius-sm`/`--radius-lg`.
- [ ] Colours come from tokens; state is never colour-only.
- [ ] Content uses `PageContainer`; no extra outer vertical padding.
- [ ] Icons are decorative (`aria-hidden`) and the control has a text accessible name.
- [ ] Every interactive element is reachable and operable by keyboard with a visible focus ring.
- [ ] Overlays trap focus, dismiss on Escape and backdrop, and restore focus.
- [ ] Checked at 390px and at 200% zoom; no horizontal overflow.
- [ ] Loading state matches the real layout's dimensions; error state is actionable.
- [ ] Nothing is simulated; unavailable features say so.
- [ ] Lint, typecheck, build and the smoke script pass.

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
> state.
>
> **Reference:** `docs/frontend/DESIGN.md` (the approved design) and
> `docs/frontend/baseline/` (approved-baseline screenshots). The baseline is the
> owner-approved appearance — judge consistency against it.
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
>   them.
> - `DESIGN.md` distinguishes **conventions** from **examples**. A different
>   composition is not a defect. A broken convention is — unless the
>   implementer explained the departure, in which case judge the explanation.
> - If you find nothing above Preference level, say so plainly.
>
> **Output:** a short summary verdict, then a table of
> `Severity | Location | Issue | User impact | Suggested correction`, then any
> separate notes on the ticket itself.
