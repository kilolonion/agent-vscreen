# ADR-008: Privileged-Process Ownership of Dual-Record `PLAY_AUDIO` Muting

- **Status**: Accepted
- **Scope**: `core/PlayAudio.kt`, `core/VScreenMute.kt`, `core/VScreenDisplayService.kt`, `core/ExecAllowlist.kt`

## Context
Background apps launched on the virtual display play audio through the phone speaker while the user is using the main screen. However, if the app process is killed or crashes while an app is muted, audio can stay permanently muted. Moreover, Android's `appops` stores `PLAY_AUDIO` separately at both the package level and the UID level, and muting via `sh -c` with unvalidated package strings introduces command-injection risk.

## Decision
1. **Privileged Service Owns Mute State (`VScreenDisplayService.Sound`)**: The longer-lived privileged service tracks which packages it muted, persists the list safely (with `Os.lstat` symlink checks and `PackageNames.isValid` filtering on `/data/local/tmp/agent-vscreen-muted.json`), and automatically restores audio on screen release, app death, or service `DESTROY_CODE`.
2. **Main-Screen Foreground Protection**: Apps currently in the foreground on the main screen (`display 0`), Agent-VScreen itself, protected system apps, and user-allowlisted apps (`sound plays <app> on`) are never muted.
3. **Dual-Record Package + UID Verification (`PlayAudio`)**: Both muting and restoring apply to package AND UID `PLAY_AUDIO` records; an app is considered restored only when both records read back `allow`.
4. **Zero `sh -c` Execution**: All `cmd appops get/set` invocations are constructed as validated direct argv arrays (`PlayAudio.silenceArgvs`, `giveBackArgvs`, `readArgv`) vetted by `PackageNames.isValid` and `ExecAllowlist`.

## Consequences
- Eliminates lingering muted apps after crashes/force-stops and closes the UID 2000→0 shell command-injection surface.
