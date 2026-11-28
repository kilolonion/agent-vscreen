# ADR-003: Six-Row Policy Table, L1–L6 Strong Confirmation, and Real-Touch Verification

- **Status**: Accepted
- **Scope**: `api/Policy.kt`, `host/Risk.kt`, `host/Consent.kt`, `host/StrongConfirm.kt`, `ui/StrongConfirmActivity.kt`

## Context
Notification action buttons can be triggered accidentally or clicked by malicious accessibility services, and coarse two-level autonomy toggles cannot express per-agent risk boundaries for payments, destructive actions, and system settings.

## Decision
1. **Per-Agent Six-Row Policy (`Policy`)**: Every agent token carries independent rows (`operate`, `caution`, `destroy`, `money`, `system`, `rate`) initialized from presets (`look`, `careful`, `free`, `free_system`) with optional per-row overrides.
   - `money` is hard-clamped to at most `ASK` (never `ALLOW`).
   - `system` is hard-clamped to `DENY` for all `publicOnly` tokens.
   - Tightening takes effect immediately and drops temporary elevations; widening any row requires biometric/PIN confirmation.
2. **User-Owned App Rules, Generic Payment Detection**: Per-app rules (`BLOCK`/`READONLY`/`ASK`) are set only by the user; no built-in package list decides for them. Payment buttons and payment-context confirmation taps escalate to `Consent.MONEY`.
3. **Six-Layer Strong Confirmation (`StrongConfirm` + `StrongConfirmActivity`)**:
   - No API endpoint, MCP method (`tasks/update` rejected), notification button, or floating overlay can approve a strong confirmation.
   - `StrongConfirmActivity` is non-exported, enforces `DEFAULT_DISPLAY` (display 0) on `onCreate`/`onNewIntent`/`onResume`, enables `FLAG_SECURE`, uses a native sibling button bar with `filterTouchesWhenObscured = true`, rejects synthetic/virtual/accessibility (`FLAG_IS_ACCESSIBILITY_EVENT = 0x800`) input events (L6), and requires biometric/device PIN for high-sensitivity approvals (no hold-to-confirm fallback for public access or pairing).
   - Third-party app screen labels displayed on confirmation cards are sanitized via `ScreenText.clean` and explicitly attributed as untrusted screen text.

## Consequences
- Prompt injection, rogue local apps, and compromised external accessibility services cannot self-approve payments, privilege elevation, or public pairings.
