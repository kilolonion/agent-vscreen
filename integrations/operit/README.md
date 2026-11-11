# Agent-VScreen ToolPkg for Operit

[中文](#中文) · [English](#english)

## 中文

给 Operit 用的 ToolPkg：装好后 Operit 的 AI 多了一个 `vscreen` 工具，可以在一块看不见的副屏上打开、查看和操作手机上的任何应用，不占用你正在用的主屏。它通过 HTTP 调用本机的 Agent-VScreen，和 MCP、命令行接入用的是同一套命令。

**前提**：手机上装好 Agent-VScreen，并按 App 首页完成配置（Shizuku 授权）。

**安装和使用**

1. 在 Operit 里导入 `agent-vscreen.toolpkg`（或从工具市场安装）。
2. 在 Agent-VScreen 的 **接入 agent → 3. 令牌** 里复制令牌（更推荐在 **权限 → 令牌** 里给 Operit 单独建一个，可以只给“只看”或“可操作”，随时撤销）。
3. 在 Operit 里对 AI 说：“这是 Agent-VScreen 的令牌：<令牌>，帮我保存。”AI 会调用 `vscreen_configure` 保存令牌并检测连接。你也可以直接在 Operit 的环境变量里填 `VSCREEN_TOKEN`。
4. 试一句：“用 vscreen 在虚拟屏打开设置，告诉我系统版本。”

**工具**

| 工具 | 作用 |
|---|---|
| `vscreen` | 执行一条 vscreen 命令（`launch 设置`、`act "tap 搜索; type 耳机"`、`act`、`status`、`help all` 等），返回文字和最多 4 张截图；没做成时末尾附 `code` 和 `next`，告诉 AI 下一步该看一眼、改命令、问用户还是稍后再试 |
| `vscreen_configure` | 保存令牌和地址（Operit 环境变量 `VSCREEN_TOKEN`、`VSCREEN_URL`），并检测连接 |
| `usage_advice` | 告诉 AI 什么时候用 vscreen、怎么开始、哪些操作要先问用户 |

- 默认地址 `http://127.0.0.1:18765`。
- 默认会话名是 `operit`。同一块副屏同一时间只属于一个会话，别的 agent 正在用时会返回 `BUSY`。
- 包版本 1.2.0，需要 Agent-VScreen 1.2.0 或更新：走 `/v2` 接口。连本机时用 `Authorization: Bearer`；Agent-VScreen 在别的设备上（填 Tailscale 地址）时自动用签名请求，令牌本身不在网络上传。
- 付款等高风险步骤由用户在手机上确认，AI 收到 `CONFIRM_PENDING` 会请你去手机上点确认。
- 令牌等于这台手机的操作权限，不要贴进公开的角色卡、分享的配置或截图。这个包里不含任何令牌。

**打包**：`./build.sh` 生成 `agent-vscreen.toolpkg`（zip：`manifest.json`、`dist/`、`resources/`）。

## English

A ToolPkg for Operit: once installed, Operit's AI gets a `vscreen` tool that opens, reads and operates any app on the phone on an invisible background screen, without taking the screen you are using. It calls the local Agent-VScreen over HTTP, with the same commands as the MCP and command-line integrations.

**Requires** Agent-VScreen on the phone, set up as its home page says (Shizuku permission).

**Install and use**

1. Import `agent-vscreen.toolpkg` in Operit (or install it from the tool market).
2. Copy the token under **Connect an agent → 3. Token** in Agent-VScreen (better: make one just for Operit under **Permissions → Tokens**, limited to look-only or operate, revocable any time).
3. Tell Operit's AI: "Here is the Agent-VScreen token: <token>, please save it." It calls `vscreen_configure` to save the token and test the connection. You can also set `VSCREEN_TOKEN` in Operit's environment variables.
4. Try: "Use vscreen to open Settings on the virtual screen and tell me the system version."

**Tools**

| Tool | What it does |
|---|---|
| `vscreen` | runs one vscreen command (`launch Settings`, `act "tap Search; type headphones"`, `act`, `status`, `help all`, …) and returns text and up to 4 screenshots; when it did not succeed it ends with `code` and `next`, telling the AI whether to look, fix the command, ask the user or retry later |
| `vscreen_configure` | saves the token and address (Operit environment variables `VSCREEN_TOKEN`, `VSCREEN_URL`) and tests the connection |
| `usage_advice` | tells the AI when to use vscreen, how to start, and what to ask the user first |

- Default address `http://127.0.0.1:18765`.
- Package version 1.2.0, needs Agent-VScreen 1.2.0 or later: it uses the `/v2` API. On the same phone it sends `Authorization: Bearer`; when Agent-VScreen runs on another device (a Tailscale address) it signs each request, so the token itself never travels.
- High-risk steps such as paying are confirmed by you on the phone; on `CONFIRM_PENDING` the AI asks you to confirm there.
- The default session is `operit`. A screen belongs to one session at a time; when another agent is using it you get `BUSY`.
- The token equals control of this phone: don't paste it into public character cards, shared configs or screenshots. This package contains no token.

**Pack**: `./build.sh` writes `agent-vscreen.toolpkg` (a zip of `manifest.json`, `dist/` and `resources/`).
