# ADR-010: Background Thread Isolation, Unified LoopWatchdog, and Liveness Diagnostics

- **Status**: Accepted
- **Scope**: `core/LoopWatchdog.kt`, `core/ThreadGuard.kt`, `host/AppLogger.kt`, `host/HostService.kt`, `host/Liveness.kt`, `host/Readiness.kt`, `core/VScreenDisplay.kt`, `core/Gate.kt`, `core/VScreenDisplayService.kt`

## Context
Unprotected background loops on `Dispatchers.Main` or raw worker threads previously caused silent thread death (`ready: true, degraded: []`), full-process crashes, or busy-wait loops when `Thread.sleep` was wrapped in `quiet()`, while `ThreadGuard` truncated thread names at the first `-` and collapsed all `vscreen-*` crashes into one bucket.

## Decision
1. **Strict Error Boundary Rules**:
   - `AppLogger.quiet()` and `LoopWatchdog` NEVER swallow `VirtualMachineError` (e.g., `OutOfMemoryError`); fatal JVM errors always propagate.
   - `HostService` coroutines run with a `CoroutineExceptionHandler` and inner `try/catch` (re-throwing only `CancellationException` and `VirtualMachineError`), never crashing `Dispatchers.Main`.
   - `while (true)` loops never wrap `Thread.sleep` in `quiet()`; `InterruptedException` exits cleanly without busy-waiting.
2. **Unified `LoopWatchdog` Across App & Privileged Processes**:
   - Supervises critical background loops (`vscreen-http-accept`, `vscreen-pub-accept`, `vscreen-liveness`, `vscreen-gate-beat`, `vscreen-tailnet-watch`, `minis-vscreen-frames`, `vscreen-gate-accept`, `vscreen-reaper`, `vscreen-fingers`, `vscreen-sound`, `vscreen-root-watch`) with exponential backoff (`1s → 2s → 5s → 30s`), circuit-breaker tripping after 5 crashes within 60 s, and generation guards preventing stale `finally` blocks from clobbering restarted loops.
   - `VScreenDisplay.ensurePumpAlive` recreates a dead frame pump `HandlerThread` and rebinds listeners cleanly.
3. **Observable Health & Accurate Thread Attribution**:
   - `ThreadGuard.normalizeThreadName` preserves semantic `vscreen-*` prefixes while stripping only numeric pool/worker suffixes and URL parameters.
   - `Liveness.json()` exposes `watchdogs` and `swallowed_total`; `Gate.statusJson()` reports remote `watchdogs` and `service_thread_deaths`; `Readiness.evaluate()` surfaces any tripped or unexpectedly dead critical watchdog as a non-blocking `watchdog_unhealthy` warning in `degraded`.

## Consequences
- Transient background exceptions self-heal cleanly with bounded backoff, and persistent loop failures become immediately visible in `status` and `diag`.
