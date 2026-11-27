# ADR-001: Gate Accept-Ack (0x06) and Wakeup Anti-Avalanche

- **Status**: Accepted
- **Scope**: `core/Gate.kt`, `api/ApiServer.kt`, `core/VScreenDisplayService.kt`

## Context
Android OEMs aggressively freeze or stop background app processes when the screen is off or the app is in the background. When the app owns the public TCP port directly, incoming connections hang in the kernel accept queue until client timeouts fire.

## Decision
1. **Privileged Port Owner (`Gate`)**: Run a zero-buffering TCP proxy inside the privileged `VScreenDisplayService` process (UID 2000 / 0) that owns the public localhost port (`18765..18774`) and forwards raw bytes to the app's internal loopback port (`18775..18784`).
2. **Immediate `0x06` Accept-Ack**: After `ApiServer` accepts an internal Gate connection, it immediately writes a single `ACCEPT_ACK_BYTE` (`0x06`) before reading the HTTP request. `Gate` waits up to `300 ms` for `0x06` before forwarding client bytes; this reliably distinguishes a live app process from a frozen kernel socket without buffering or replaying HTTP bodies.
3. **Single-Flight Wakeup & Cooldown**: On missing `0x06` or connection refusal, `Gate` runs a single-flight wakeup (`am unfreeze` on API 34+, `am start-foreground-service` for `HostService`, plus `WakeReceiver` fallback). Three consecutive failures trigger a `60 s` cooldown returning HTTP `503 HOST_RESTARTED` so stalled callers fail fast instead of creating a wakeup storm.
4. **Disconnect Finger Cancel**: When a forwarded connection drops while touches are active, `Gate` cancels lingering agent touch pointers so a disconnect never turns a held gesture into an accidental tap.

## Consequences
- No HTTP request buffering or replay inside the privileged process (preserving TLS/signing transparency and zero-memory amplification).
- `Gate` status (`watchdogs`, `service_thread_deaths`, `hold` state) is exposed back to the app via Binder `GATE` op 3 so `Readiness` can surface remote failures without blocking.
