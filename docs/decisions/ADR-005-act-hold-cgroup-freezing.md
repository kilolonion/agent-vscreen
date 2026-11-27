# ADR-005: Inter-Turn App Freezing (`act --hold`) Verification and API 35 Guardrails

- **Status**: Accepted
- **Scope**: `cmd/ActHold.kt`, `core/Gate.kt`, `core/VScreenDisplayService.kt`, `host/ShizukuManager.kt`

## Context
Fast-moving apps (real-time games, timed prompts) continue advancing while the LLM thinks between turns. Freezing the foreground app between `act` turns converts real-time flows into turn-based ones, but naive freezing can freeze critical system/privileged processes or silently fail on OEM kernels.

## Decision
1. **Upfront Denylist & Dynamic Privilege Protection**: Before attempting freeze, `ActHold` rejects system processes, persistent apps, active IMEs, active media sessions, own package, and all dynamically discovered Shizuku/fork manager packages (`ShizukuManager.discoveredManagerPackages()`). `Gate.hold` enforces the same denylist independently inside the privileged process and validates `/data/local/tmp/avs-holds.txt` against symlinks.
2. **Synchronous Verification Before Reply**: On API 34+, `ActHold` locates the display's foreground app PIDs using direct argv calls (never `sh -c`), freezes via `Gate`, and synchronously inspects `cgroup.events` (`frozen 1`) and `wchan` (treating `wchan == 0` as unverified) before sending the agent reply.
3. **Guaranteed Unfreeze & Max Hold Cap**: Every incoming command runs `ActHold.unholdBeforeCommand` first, and `Gate` enforces a hard `120 s` maximum hold timer so an app never stays frozen if the agent disconnects.
4. **API 35+ Reality Disclosure**: Starting on Android 15, freezing an actively refreshing app may cause the system binder/ANR watchdog to kill it; documentation and command help surface this trade-off explicitly, and `ActHold` aborts/trips cleanly when the OS terminates held processes.

## Consequences
- Turn-based pauses are verifiable facts rather than best-effort guesses, and privileged managers/system UI can never be deadlocked by `--hold`.
