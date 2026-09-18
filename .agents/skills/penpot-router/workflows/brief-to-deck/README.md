# brief-to-deck — from a deck brief to a playable, validated slide deck

**Pattern:** Evaluator-Optimizer (same shape as `brief-to-screen`).
**Generator:** `penpot-build-deck` · **Evaluators:** `penpot-audit-accessibility` (`scope:deck`) and the
generator's own scored critique + structural gate (`references/07-critique-framework.md`,
`scripts/auditDeckQuality.js`).

## The loop
1. **generate** — `penpot-build-deck` runs its full workflow from a `prompts/deck-brief.md` brief:
   outline → deck system → style freeze (slides 1–3) → batches of 3–5 slides → flow wiring →
   structural gate → scored critique. It ends with a `deckQuality` report
   (`shared/report-schemas/deck-quality-report.schema.json`) whose `belowThreshold` and
   `structuralGate.pass` fields the branch reads. ✋ Checkpoints inside the skill stay in force.
2. **evaluate** — `penpot-audit-accessibility` in `scope:deck`: slide contrast with the large-text
   thresholds (≥ 24 px, or ≥ 18.67 px bold → 3:1; below → 4.5:1), one `h1` per slide, no target-size
   checks. Emits the accessibility report with `highOrMedium` precomputed.
3. **decide** — `evaluate.highOrMedium == 0 && generate.deckQuality.belowThreshold == 0 &&
   generate.deckQuality.structuralGate.pass == true` → done. Otherwise back to **generate** with both
   findings sets as inputs; the generator revises only the named slides (never rebuilds the deck).
   `maxIterations: 3`.

## Inputs
- `deckBrief` — a filled `prompts/deck-brief.md` (`/penpot-deck-brief`).
- `designSystem` — the file's existing tokens/fonts/components (Phase 0 discovery output).
- `slideOutline` — the approved outline table from the Phase 0 checkpoint.

## Exit
Both gates pass, or `maxIterations` reached → present the remaining findings, the weak axes and any
unlinked slides, plus the PDF-export and share-link steps (`references/06-flow-and-playback.md`).

## Failure modes to watch
- **Generator inflating its own scores** — every axis score must cite slide exports; a 3 without
  evidence of competence is a 2 (`shared/design-quality.md` §8).
- **Trading monotony for accessibility** — fixing contrast by flattening every slide to the same
  light layout re-triggers `consecutiveSameArchetype`; the structural gate catches it.
- **Silent image swaps** — a failed `uploadMediaUrl` must surface as a placeholder + `exceptions`
  entry, never as a missing visual (which would fail `noVisual`) or a guessed URL.
- **Wrong page** — if `createDeckPage.js` PHASE=verify fails, the run halts; boards on the wrong
  page cannot be moved (Finding 9).
