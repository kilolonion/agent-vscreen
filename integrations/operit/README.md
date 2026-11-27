# Agent-VScreen ToolPkg for Operit

[中文](#中文) · [English](#english)

## 中文

给 Operit 用的 ToolPkg：装好后 Operit 的 AI 多了一个 `vscreen` 工具，可以在一块看不见的副屏上打开、查看和操作手机上的任何应用，不占用你正在用的主屏。它通过 HTTP 调用本机的 Agent-VScreen，和 MCP、命令行接入用的是同一套命令。

**前提**：手机上装好 Agent-VScreen，并按 App 首页完成配置（Shizuku / 兼容分支或 Root 授权）。

**安装和使用**

1. 在 Operit 里导入 `agent-vscreen.toolpkg`（或从工具市场安装）。
2. 在 Agent-VScreen 的 **接入** 页点 **接入新的 agent**，名字填 Operit，选 **这台手机上**（或 **其它设备上** 经 Tailscale）和 **HTTP**，复制里面的 10 分钟一次性接入码（`avc_...`；也可在 **设置 › 令牌** 手动创建长期令牌）。该接入码仅单次有效，不会把长期密钥留在聊天记录里，兑换后的令牌在接入页随时可作废。
3. 在 Operit 里对 AI 说：“这是 Agent-VScreen 的一次性接入码：<接入码>，帮我配置并检测连接。”AI 会调用 `vscreen_configure`（`code` 参数）经本机/Tailnet 的 `POST /v2/pair/exchange` 兑换长期令牌，自动保存到 Operit 的环境变量 `VSCREEN_TOKEN` 并检测连接。你也可以直接传入长期令牌或在环境变量里填 `VSCREEN_TOKEN`。
4. 试一句：“用 vscreen 在虚拟屏打开设置，告诉我系统版本。”

**工具**

| 工具 | 作用 |
|---|---|
| `vscreen` | 执行一条 vscreen 命令（`launch 设置`、`act "tap 搜索; type 耳机"`、`act`、`status`、`help all` 等），返回文字和最多 8 张截图；没做成时末尾附 `code` 和 `next`，告诉 AI 下一步该看一眼、改命令、问用户还是稍后再试 |
| `vscreen_configure` | 兑换 10 分钟一次性接入码（`code`，`avc_` 开头）或保存已有令牌（`token`）与地址（`base_url`，对应 Operit 环境变量 `VSCREEN_TOKEN`、`VSCREEN_URL`），并检测连接 |
| `usage_advice` | 告诉 AI 什么时候用 vscreen、怎么开始、哪些操作要先问用户 |

- 默认地址 `http://127.0.0.1:18765`。
- 默认会话名是 `operit`。同一块副屏同一时间只属于一个会话，别的 agent 正在用时会返回 `BUSY`。
- 包版本见 `manifest.json`，需要 Operit 1.10.0 或更新、Agent-VScreen 1.2.0 或更新（示教、指给我看、步骤里的通配和流程要 1.3.0；端口自动查找要 1.4.0；10 分钟一次性接入码兑换要 2.0.1-beta1+）：走 `/v2` 接口。连本机时用 `Authorization: Bearer`；Agent-VScreen 在别的设备上（填 Tailscale 地址）时自动用签名请求，令牌本身不在网络上传。
- 付款等高风险步骤由用户在手机上确认，AI 收到 `CONFIRM_PENDING` 会请你去手机上点确认。
- 令牌等于这台手机的操作权限，不要贴进公开的角色卡、分享的配置或截图。这个包里不含任何令牌。

**打包**：`./build.sh` 生成 `agent-vscreen.toolpkg`（zip：`manifest.json`、`dist/`、`resources/`）。

## English

A ToolPkg for Operit: once installed, Operit's AI gets a `vscreen` tool that opens, reads and operates any app on the phone on an invisible background screen, without taking the screen you are using. It calls the local Agent-VScreen over HTTP, with the same commands as the MCP and command-line integrations.

**Requires** Agent-VScreen on the phone, set up as its home page says (Shizuku / compatible fork or Root permission).

**Install and use**

1. Import `agent-vscreen.toolpkg` in Operit (or install it from the tool market).
2. On the **Connect** tab of Agent-VScreen, tap **Connect a new agent**, name it Operit, choose **On this phone** (or **Another device** over Tailscale) and **HTTP**, and copy the 10-minute one-time onboarding code (`avc_...`; manual permanent tokens under **Settings › Tokens** also work). The code is single-use so no permanent secret stays in chat history, and the redeemed token can be revoked on the Connect tab at any time.
3. Tell Operit's AI: "Here is the Agent-VScreen onboarding code: <code>, please configure it and test the connection." It calls `vscreen_configure` (`code` parameter), exchanges the code once via local/Tailnet `POST /v2/pair/exchange`, saves the permanent token to Operit's `VSCREEN_TOKEN` environment variable, and tests the connection. You can also pass a static token or set `VSCREEN_TOKEN` manually.
4. Try: "Use vscreen to open Settings on the virtual screen and tell me the system version."

**Tools**

| Tool | What it does |
|---|---|
| `vscreen` | runs one vscreen command (`launch Settings`, `act "tap Search; type headphones"`, `act`, `status`, `help all`, …) and returns text and up to 8 screenshots; when it did not succeed it ends with `code` and `next`, telling the AI whether to look, fix the command, ask the user or retry later |
| `vscreen_configure` | exchanges a 10-minute one-time onboarding code (`code`, starting with `avc_`) or saves an existing token (`token`) and address (`base_url`) into Operit environment variables (`VSCREEN_TOKEN`, `VSCREEN_URL`), then tests the connection |
| `usage_advice` | tells the AI when to use vscreen, how to start, and what to ask the user first |

- Default address `http://127.0.0.1:18765`.
- See `manifest.json` for the package version. Requires Operit 1.10.0 or later and Agent-VScreen 1.2.0 or later (lessons, ask-the-user, wildcards and flow in steps need 1.3.0; finding a moved port needs 1.4.0; 10-minute onboarding code exchange needs 2.0.1-beta1+): it uses the `/v2` API. On the same phone it sends `Authorization: Bearer`; when Agent-VScreen runs on another device (a Tailscale address) it signs each request, so the token itself never travels.
- High-risk steps such as paying are confirmed by you on the phone; on `CONFIRM_PENDING` the AI asks you to confirm there.
- The default session is `operit`. A screen belongs to one session at a time; when another agent is using it you get `BUSY`.
- The token equals control of this phone: don't paste it into public character cards, shared configs or screenshots. This package contains no token.

**Pack**: `./build.sh` writes `agent-vscreen.toolpkg` (a zip of `manifest.json`, `dist/` and `resources/`).
