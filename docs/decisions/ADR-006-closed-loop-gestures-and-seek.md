# ADR-006: Closed-Loop Gestures (`hold` / `seek`) and Adaptive Slider Search

- **Status**: Accepted
- **Scope**: `cmd/Gesture.kt`, `cmd/VScreenSteps.kt`, `core/VScreenDisplay.kt`, `core/VScreenPerception.kt`, `core/InputRoute.kt`

## Context
Open-loop gestures (`swipe`/`drag` with guessed coordinates and duration) fail on drop zones, charge bars, long-press context menus, and fine-grained sliders where visual feedback only appears while the finger stays down.

## Decision
1. **Two Closed-Loop Modes (`Gesture.closedLoop`)**:
   - `press ... hold until <cond>` / `drag ... hold until <cond>`: keep the finger down after reaching the destination and poll `color:` or region-scoped `text:`/`gone:` (`in x1,y1,x2,y2` mandatory to avoid full-screen OCR latency) until the condition matches, then lift immediately.
   - `drag x1 y1 x2 y2 seek until <cond>`: traverse a 2-point line in adaptive small steps using `SeekSearch`, backtracking on overshoot to hit exact step-1 targets (verified on S16 0–300% slider).
2. **Pre-Touch Input Routing & Zero-Stale-Frame Rule**:
   - Check `InputRoute.choose` before placing the finger down (if `seek` already matches at the start, skip touching altogether; if screen-off input is blocked and wake is off, fail fast with `INPUT_BLOCKED_SCREEN_OFF`).
   - Pause background full-screen OCR (`VScreenPerception.setOcrPaused`) during the gesture and use `colorFastMode` (zero-allocation RGBA sampling) or `cropOnly` region OCR.
   - Never evaluate a hit against a frame older than the previous finger movement; 3 consecutive `1.5 s` read timeouts report `notRead`.
3. **Guaranteed Pointer Cleanup & After-Release Fact Check**:
   - `finally` always cancels the finger on abort/error and resumes background OCR.
   - Post-release analysis inspects pickup, follow-through, and snap-back (prioritizing accessibility text where available) and selects a 4-cell contact sheet for diagnostics.

## Consequences
- Fine sliders, charge meters, and drag-and-drop targets succeed reliably on-device without multi-turn LLM round trips.
