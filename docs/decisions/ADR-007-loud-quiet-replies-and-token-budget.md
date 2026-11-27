# ADR-007: Quiet vs. Loud Replies, Reply Budget, Prompt-Injection Fencing, and Guide Token Caps

- **Status**: Accepted
- **Scope**: `cmd/Loud.kt`, `cmd/PageBook.kt`, `cmd/VScreenReport.kt`, `cmd/ScreenText.kt`, `api/AgentGuide.kt`, `core/Meter.kt`

## Context
Returning full element lists and screenshots on every routine step inflates LLM context cost and latency, whereas omitting critical unexpected events (popups, toasts, snap-backs, missed steps) causes silent agent hallucinations. Simultaneously, untrusted screen text from third-party apps can forge reply headers (`【注意】`) or inject prompt instructions.

## Decision
1. **Pure Loud/Quiet Decision Function (`Loud.loudReasons`)**: Default reply mode is `quiet`. An `act` returns a compact diff reply (with `PageBook` 32-card LRU referencing when interval `<= 8` turns and element diff `< 40%`) ONLY when all steps met explicit expectations and none of the 10 loud triggers fired (failure, notes, no-expect step, popup, system toast, snap-back, before/after surprise, no-text canvas, forced/escalated). Detector-only icon boxes do not count as text elements.
2. **Automatic Escalation & Monotonic `#N` IDs**: If the agent refetches (`look` / bare `act`) 3+ times within the last 10 quiet replies, the session automatically escalates to full replies for 10 turns. Element IDs (`#N`) never recycle within a display generation (`Numbers.max` monotonic).
3. **Deterministic 1200-Token Reply Budget & Guide Caps**:
   - `applyReplyBudget` trims in strict priority order: flashed words → element details → timing metadata — NEVER trimming `【注意】` or failure facts.
   - `AgentGuide.guide()` is unit-test gated at `<= 1500` tokens; `AgentGuide.memoryFor()` is gated at `< 1880` chars (ZH) / `< 4170` chars (EN).
4. **Prompt-Injection Boundaries (`ScreenText`)**: All third-party screen text, system toasts, input readbacks, app labels, and AppFunctions metadata strip `\r`/`\n`/control/Bidi overrides and neutralize fake section markers (`【...】`, `</screen-data>`). Text replies quote and fence screen content in `<screen-data>…</screen-data>`; structured JSON isolates untrusted text under top-level `screen_data` (`untrusted: true`) and separates `notes[].system_text` from `screen_excerpts`.

## Consequences
- Median `act` token usage stays low on expected steps with zero false-quiet regressions (`false_quiet == 0` CI eval gate) while maintaining explicit trust boundaries between system instructions and untrusted app text.
