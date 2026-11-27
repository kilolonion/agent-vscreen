# ADR-009: Virtual Display Flag Fallback Ladder, Parking, Screen-Off Input Routing, and Thermal Protection

- **Status**: Accepted
- **Scope**: `core/VScreenDisplayService.kt`, `core/BackgroundOperation.kt`, `core/VScreenAwake.kt`, `core/InputReach.kt`, `core/InputRoute.kt`, `host/HeatPolicy.kt`, `host/ThermalGuard.kt`

## Context
Android versions and OEM ROMs vary widely in supported `VirtualDisplay` flags, off-screen touch dispatch (`Display not interactive`), and thermal throttling behavior. Hardcoding OEM/device names breaks maintainability and misses untested devices.

## Decision
1. **Capability-Driven Virtual Display Flag Ladder**: Request display creation along a deterministic fallback ladder preserving `OWN_DISPLAY_GROUP` ahead of `ALWAYS_UNLOCKED` (paired with `TRUSTED` so independent display wake works on API 33+), falling back gracefully without OEM brand checks.
2. **Hidden Parking Display & Reactive Wake Coordinator**: `handoff park` moves tasks onto a hidden parking display (closed at turn end/stop/takeover). `VScreenAwake` subscribes reactively to `BackgroundOperation.state`, `activeTurns`, and `PhoneScreenMode` (`ALLOW_SLEEP` / `KEEP_ON` / `WAKE_WHEN_NEEDED`), while passive read-only commands (`CommandSpec.isPassiveReadOnly`) never start turns or acquire wake locks.
3. **Off-Screen Input Reachability & Routing (`InputReach` + `InputRoute`)**: Probe display interactivity via `IS_DISPLAY_INTERACTIVE` (protocol 19) with `dumpsys input` fallback (`parseNonInteractive`, returning `UNKNOWN` on unrecognized output). When `BLOCKED`, element actions route through verified accessibility nodes (`via=a11y`), while coordinate/gesture/key actions either wake briefly (`WAKE_WHEN_NEEDED`, bounded by `WakeBudget`, `via=wake`) or refuse explicitly with `INPUT_BLOCKED_SCREEN_OFF`.
4. **Two-Stage Thermal Latch & User Override (`HeatPolicy` + `ThermalGuard`)**:
   - Trigger hard stop at `>= 45.0°C` or thermal status `>= 3`, persisting latch synchronously before any process kill.
   - Normal cooldown unlocks manually from the home power sheet after `< 43.0°C` and status `< 3` for `60 s` (tightening to `< 39.0°C` / `< 2` / `180 s` on recurrence within 1 hour), resuming into `limited` mode.
   - Users may disable hard stop (`heat_stop_enabled = false`) after explicit risk confirmation while retaining `limited`/`ocrPaused` load shedding and one-shot danger alerts.

## Consequences
- Behavior adapts dynamically to real OS capabilities across Android 11–17+ without device-model special cases.
