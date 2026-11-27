# ADR-002: Control Epoch, Server-Side InputFence, and Takeover Rules

- **Status**: Accepted
- **Scope**: `core/Control.kt`, `core/InputFence.kt`, `core/Takeover.kt`, `core/UserTouch.kt`, `core/VScreenDisplayService.kt`

## Context
A command sent by an agent before the user taps "Takeover" or "Stop AI" may already be in flight across threads or IPC. Without an atomic cross-process barrier, late touch or key events can land on a screen the user now owns.

## Decision
1. **Wall-Clock Monotonic Control Epoch (`Control.epoch`)**: Initialize `epoch` from wall-clock milliseconds so epochs remain strictly increasing across app restarts relative to an already-running privileged service. Increment `epoch` on every takeover, stop, hand-back, and display close.
2. **Dual-Side Enforcement (`Control.admitInput` + `InputFence`)**:
   - Every agent input path (shell touch/key/swipe/gesture and accessibility node action) calls `Control.admitInput` before acting, checking `ThermalGuard.locked` first, then stopped/takeover state and `Call.epoch`.
   - `VScreenDisplayService` enforces a per-display `InputFence` in the privileged process itself. `InputFence.set` writes `agentAllowed = false` before updating `epoch` and finally the new allowed flag so concurrent readers never admit a stale epoch.
3. **User Input Separation (`Control.asUser` / `InputFence.USER_FINGER = 9`)**: User interactions in the floating window and fullscreen page run inside `Control.asUser` using dedicated pointer ID `9`, bypassing the agent fence while cancelling only agent fingers on handoff.
4. **Takeover Semantics (`TakeoverRules`)**: User takeover survives turn end, pauses idle release, rejects other sessions, and marks a `REREAD_REQUIRED` debt on hand-back so the agent must observe the updated screen before mutating it again.

## Consequences
- Zero in-flight agent inputs can leak after user takeover or stop, even under thread scheduling delays.
- `InputFence` and `TakeoverRules` remain pure Kotlin with zero Android dependencies for fast JVM unit testing.
