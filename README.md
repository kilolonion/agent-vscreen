<p align="center"><img src="docs/logo.svg" width="96" alt="Agent-VScreen"></p>

# Agent-VScreen

<p align="center">
  <a href="https://github.com/kilolonion/agent-vscreen/releases/latest"><img src="https://img.shields.io/github/v/release/kilolonion/agent-vscreen" alt="latest stable release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/kilolonion/agent-vscreen" alt="license"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/Shizuku%20or%20root-supported-blue" alt="Shizuku or root">
  <img src="https://img.shields.io/badge/MCP-supported-8A2BE2" alt="MCP">
</p>

**A background second screen on your Android phone, for any AI agent: on the phone, on a computer or in the cloud.**

[中文](README.zh-CN.md) · English

Agent-VScreen opens and drives other apps on an invisible virtual display so your main screen stays untouched. A small floating window shows what the AI is doing and lets you take over or stop it at any moment. Model-agnostic and works over the `vscreen` CLI, MCP, or plain HTTP.

<!-- version:start -->
- Version on `main`: 2.0.1-beta1 (versionCode 75) · [Release APK](https://github.com/kilolonion/agent-vscreen/releases/tag/v2.0.1-beta1) · [Latest stable APK](https://github.com/kilolonion/agent-vscreen/releases/latest)
<!-- version:end -->
- Requirements: Android 11+ (arm64, Android 14+ recommended) · Package `com.kilon.agentvscreen` · Privileges: [Shizuku](https://shizuku.rikka.app/) / compatible forks or Root

<p align="center"><img src="docs/demo.gif" width="760" alt="While the user scrolls a web page on the main screen, an agent opens Clock on the background screen and starts a 10-minute timer; the floating window mirrors it live"></p>
<p align="center"><sub>You keep reading in Chrome while the agent opens Clock on the background screen and starts a 10-minute timer (waits sped up 6x).</sub></p>

<p align="center"><img src="docs/app.en.png" width="760" alt="Agent-VScreen Now, Connect, Records and Settings screens"></p>

<details>
<summary>More demos, multi-agent screenshot, and 2-minute getting-started video</summary>

<p align="center"><a href="docs/promo/tutorial.en.mp4?raw=true"><img src="docs/tutorial.en.png" width="760" alt="Getting-started video: grant access, connect your agent, give it a first job, take over, review what it did"></a></p>
<p align="center"><sub>2-minute getting-started video (English UI and voiceover): <a href="docs/promo/tutorial.en.mp4?raw=true">Download MP4</a></sub></p>

<p align="center"><img src="docs/takeover.gif" width="760" alt="The user long-presses the floating window to take over the background screen, scrolls and taps, then hands it back and the agent carries on"></p>
<p align="center"><sub>Take over any time: long-press the floating window to drive the background screen yourself, then hand it back. <a href="docs/promo/takeover.mp4?raw=true">Download MP4</a></sub></p>

<p align="center"><img src="docs/agents.png" width="860" alt="Minis, Eta, RikkaHub and Muse all driving a forum app on the background screen through vscreen"></p>
<p align="center"><sub>One install command: four different Android agents (Minis, Eta, RikkaHub, Muse) all connect and drive the background screen.</sub></p>

<p align="center"><img src="docs/tour.gif" width="760" alt="A tour of the Now, Connect, Records and Settings tabs of the app"></p>
<p align="center"><sub>App tour (<a href="docs/promo/tour.mp4?raw=true">Download MP4</a> · <a href="docs/promo/background.mp4?raw=true">Background demo MP4</a> · <a href="docs/promo/mix.mp4?raw=true">34s combined cut</a>). Music: "Wallpaper" by Kevin MacLeod (incompetech.com), <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>.</sub></p>

</details>

## Highlights

- **True background execution**: opens apps, taps, swipes, types, presses keys, and verifies every step on an isolated virtual display without taking over adb or wireless debugging.
- **Clear perception & timeline**: merges read-only accessibility with on-device OCR (PP-OCRv6 / ML Kit), reports screen changes and transient popups between turns, and supports zoom, replay, paged collection, and background watches.
- **Human-in-the-loop control**: color-coded floating window shows who owns the screen; hold 0.5s to take over or stop in one tap; supports pointing questions (`ask`), recorded lessons (`teach`), and main-screen handoffs for passwords/codes (`handoff`).
- **Per-agent permissions & strong confirmation**: each agent gets its own token and 6-row permission table (Look only / Steady / Easy / Full), apps can be blocked or read-only, and payments or high-risk actions require biometric/PIN confirmation on the phone.
- **Any agent, any model**: no AI model runs on the phone; anything speaking CLI, MCP, or HTTP can connect.

## Quick start

### 1. Install & setup

1. Download and install the APK, then open Agent-VScreen.
2. In **Setup**, choose one permission source:
   - **Shizuku / compatible forks (default)**: start Shizuku (or a compatible `API_V23` fork such as Sui or AXManager), then tap **Grant**. Restart the provider after each reboot.
   - **Root**: on a rooted phone (or when official Shizuku is restricted on Android 17+), tap **Use Root instead (no Shizuku needed)** and approve the prompt.
3. Tap **Turn it on** to grant overlay, notifications, read-only accessibility, and battery exemption; on OEM ROMs (Honor, Huawei, Xiaomi, OPPO, vivo), also allow auto-start and background activity.

<a id="verify-a-downloaded-apk"></a>
<details>
<summary>Verify a downloaded APK signature</summary>

Before installing, verify with [`apksigner`](https://developer.android.com/tools/apksigner) from Android SDK Build Tools and match the official release certificate (`CN=Agent-VScreen`) SHA-256 digest:

```sh
apksigner verify --print-certs downloaded.apk
```

`56:B1:48:24:54:9E:69:80:17:EB:A9:07:CB:C3:34:7E:E3:A4:BA:30:90:7E:FB:D6:06:8E:A5:1F:F4:49:57:56`

`apksigner` prints the digest in lowercase without colons. Compare the certificate digest (not the APK file checksum) and do not install if it differs.

</details>

### 2. Connect an agent

Open **Connect › Connect a new agent**, pick where the agent runs and how it connects, then tap **Copy message for your agent** and send it as is. CLI, HTTP, and Operit default to **10-minute one-time onboarding codes (`avc_...`)** so long-lived secrets stay out of chat logs and shell history, and copied credentials are marked `IS_SENSITIVE` to hide clipboard previews.

| Mode | Transports | Notes |
|---|---|---|
| **Local** (default) | `vscreen` CLI / MCP / HTTP / [Operit ToolPkg](integrations/operit/) | Listens on `127.0.0.1:18765` only; unreachable off-device |
| **Private remote** | Built-in Tailscale (recommended, supports Headscale) / Tailscale app / SSH | Private tailnet only; non-built-in remote calls require signed requests by default |
| **Cloud Public MCP** (Experimental, off by default) | Tailscale Funnel / Cloudflare Tunnel / ngrok + OAuth 2.1 | No unsolicited popups (enter 8-char pairing code on phone + biometric/PIN); `system` never exposed; auto-closes after 24h without authenticated requests |

For full protocol details, security boundaries, ability list, and known limits, see [API.md](API.md).

## Docs

- [API.md](API.md) (Chinese): CLI, MCP, and HTTP reference, ability list, sessions, watches, security model, remote/public MCP, and known limits.
- [docs/REFERENCE.md](docs/REFERENCE.md): every command option and result code (generated from source).
- [docs/CHANGELOG.md](docs/CHANGELOG.md): full release history ([中文](docs/CHANGELOG.zh-CN.md)).
- [docs/TASKS.md](docs/TASKS.md) · [docs/RELEASE-PROCESS.md](docs/RELEASE-PROCESS.md): pre-release device test checklist and release signing gates.

## Build from source

Requires JDK 21 and the Android SDK (compileSdk 37):

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

To bundle the built-in Tailscale/ngrok (`tsbridge/`) and Cloudflare Tunnel (`cloudflared/`) channels, run `./tsbridge/build.sh` and `./cloudflared/build.sh` first. See [`keystore.properties.example`](keystore.properties.example) for local signing and [docs/RELEASE-PROCESS.md](docs/RELEASE-PROCESS.md) for the release pipeline.

## License & Thanks

MIT. Bundled OCR models (`app/src/main/assets/vscreen-ocr/`) are from PaddleOCR (Apache-2.0). The optional textless-icon detector (`app/src/main/assets/vscreen-detector/model.onnx` and [agent-vscreen-models](https://github.com/kilolonion/agent-vscreen-models)) is a separate AGPL-3.0 data file, enabled only after user consent.

Thanks: [LINUX DO](https://linux.do) · [OpenMinis](https://github.com/OpenMinis)
