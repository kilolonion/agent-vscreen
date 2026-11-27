# Agent-VScreen 接口说明

Agent-VScreen 是一个独立的 Android 应用。它在一块看不见的副屏（虚拟屏）上打开和操作别的应用，用户的主屏不受打扰，屏幕上只有一个悬浮小窗显示进度。它不依赖任何特定的 agent 软件：任何 agent 都可以通过命令行、HTTP 或 MCP 调用它。

<!-- version:start -->
- 包名 `com.kilon.agentvscreen`，`main` 分支版本 2.0.1-beta1（versionCode 75）。官方测试版与正式版同签名可覆盖安装；安装前可按 [README 的说明](README.zh-CN.md#验证下载的-apk)验证签名。
<!-- version:end -->
- 需要 Android 11 及以上、arm64。
- **2.0.x**：守门人（`Gate`）托管公开本机端口并在 App 被冻结或强停后自动唤醒；特权服务（协议 19）全事务码调用方 UID 强校验与 `ExecAllowlist` 零 `sh -c` 白名单；10 分钟一次性接入码（`avc_...` / `POST /v2/pair/exchange`）与敏感剪贴板（`IS_SENSITIVE`）；每 agent 独立六行权限表（`Policy`）与防遮挡独立强确认页（`StrongConfirm`）；实验性公网 MCP 接入（Tailscale Funnel / Cloudflare Tunnel / ngrok + OAuth 2.1 + 手机输入配对码确认 + 30 秒刷新容错 + `2026-07-28` HTTP 400 回退）；Shizuku 兼容分支动态发现与 Android 16/17 兼容；回复协议 v3 与闭环手势；任务中保持亮屏；完整版本记录见 [docs/CHANGELOG.zh-CN.md](docs/CHANGELOG.zh-CN.md)。
- **1.4.x**：支持已 root 手机直接用 Root（默认仍用 Shizuku）、统一看图命令 `look`、应用静音 `sound`、端口自动顺延、按 agent 隔离定时预约与盯守。
- **1.3.0 / 1.2.0**：`ask` / `teach` / `schedule`、结构化 `/v2` 接口与多令牌签名认证，旧接口 `/v1` 仍保留兼容（升级要点见第 9 节）。
- 每条命令的参数和每个结果代码的完整列表在 [docs/REFERENCE.md](docs/REFERENCE.md)，它由代码里的命令表和代码表生成，和 `vscreen help all`、`vscreen help codes` 的内容一致。

## 1. 安装和首次配置

特权来源二选一：默认用 **Shizuku / 兼容分支**（不需要 root，通过三层动态发现自动识别官方 Shizuku、Sui、AXManager、Nightzuku、Shevery 及其他实现 `moe.shizuku.manager.permission.API_V23` 协议的分支），已 root 的手机也可以直接用 **Root**（优先 `su 2000` 以 shell 身份运行，失败时回退 uid 0；Android 16/17 上已加固 `AttributionSource` 兼容并在首次配置页提供显式 Root 回退入口）。Agent-VScreen 不占用 adb，不改无线调试的开关和端口，也不重启 adbd；特权进程内的屏幕服务（协议 19）对全部 Binder 事务强制核验调用方 UID，命令执行一律经纯 JVM 白名单（`ExecAllowlist`）校验且运行时不调用 `sh -c`；Shizuku 模式下若 `Shizuku.newProcess` 反射受限且屏幕服务已连接，自动透明降级为服务端 `EXEC`。你的 agent 如果自己在用 adb 或 Shizuku，照常可以用。

1. 安装 APK，打开 Agent-VScreen。
2. 在“首次配置”里选择权限来源：
   - **Shizuku / 兼容分支**：先启动 Shizuku（或已安装的兼容分支 / Sui），再点 **授权**，在弹窗里点允许。手机每次重启后要重新启动对应服务。动态发现的特权管理器包名会自动免于回合间冻结（`--hold`）与主副屏移交（`handoff`）。
   - **Root**：手机已用 Magisk、KernelSU、APatch 等 root（或 Android 17+ 上官方 Shizuku 受限）时，点 **改用 Root（无需 Shizuku）**，在 root 管理器弹窗里授权。不需要装 Shizuku；获得授权后，打开 App 时会自动启动 Root 服务。
3. 点 **自动开启**。它会通过所选的权限来源依次做下面几件事，每一项都只把 Agent-VScreen **加进去**，不会移除或替换别的应用的设置：
   - 允许悬浮窗；
   - 允许通知；
   - 把它的无障碍服务加进已开启的列表。这个服务是只读的：不做手势，也不开触摸浏览，你已经开着的其它无障碍服务保持不变；
   - 加入电池白名单、允许后台运行。
4. 在底部“接入 › 新建接入”里按向导生成 10 分钟一次性接入码或配置并 **复制给 agent 的话**，发给你的 agent，见第 2 节。

也可以在 **设置 › 权限来源** 切换。切换会关闭当前副屏；新来源就绪后，下一条命令会自动重连。

**电池白名单和后台运行会自动补上**：有些系统会收回这些豁免。检查时（打开首页、服务启动、`status`）发现缺项，会通过所选的权限来源自动补上（最多每 20 秒一次）。自动配置失败时再手动设置。

**系统后台管理**（荣耀、华为、小米、OPPO、vivo 等才有）：系统不提供读取这一页设置的方法，所以你打开过这个页面后，这一项就算完成。1.1.6 起，只有**亮屏使用时**又被冻结，这一项才会重新亮起。厂商系统在熄屏后一段时间常会冻结应用，那时没人在用手机，1.1.5 把这种冻结也算进去，于是每次回到 App 都显示没配置。`status` 的 `host` 里，`frozen_count` 是全部冻结次数，`frozen_screen_on_count` 是亮屏时的冻结次数。

配置全部完成后，“首次配置”这一块会折叠成一行。

**无障碍自动恢复**：你在一键配置里开过无障碍以后，如果它被系统断开（部分系统在强行停止或更新应用后会把它移出列表），App 会在下一条命令或服务启动时自己重新加上，每分钟最多一次，仍然只是追加。有两种情况它会停手：

- 刚自动打开又被关掉：当作你不想开，不再自动打开；
- 系统拦截写入（荣耀、华为等）：记住，不再尝试，改为提示你手动打开。

两种情况都可以在首页再点一次一键配置重新开始。当前状态写在 `vscreen status` 的 `accessibility_auto` 里。

无障碍建议打开，它有两个作用：

- **让悬浮窗不被系统藏起来**（1.1.1）。安卓有一条规则：只要有页面要求“隐藏非系统悬浮窗”，所有屏幕上的普通悬浮窗都会被藏起来。设置、安装器、权限弹窗和很多银行应用都会这样要求，而 agent 常常在副屏上打开这些页面，于是悬浮窗一闪就没了。开了无障碍以后，悬浮窗挂在无障碍服务下面，这条规则管不到它。
- 读到没有文字的图标按钮，中文输入可以直接写进输入框。

不开无障碍也能用：悬浮窗退回普通悬浮窗，遇到上面那类页面时会被系统暂时藏起来；这时可以点通知里的 **查看副屏** 打开全屏页。元素列表只来自文字识别，中文输入改用剪贴板粘贴。

**后台保活（1.1.3）**：agent 在前台时，Agent-VScreen 总是在后台。系统或厂商的后台管理可能冻结它：冻结时连接还能建立，但命令一直没有回应，直到你打开 Agent-VScreen。为此 App 做了这几件事：

- 服务运行时在主屏左上角放一个 1 像素、完全透明、不接收触摸的悬浮窗，让系统把它当作“用户可感知”的应用，不去冻结；
- 每秒自检一次。亮屏时被冻结过（1.1.6 起熄屏时的不算），首页会出现 **后台运行受限**，点 **去设置** 会打开系统的后台管理页（荣耀/华为是“应用启动管理”，改成手动管理，允许自启动、关联启动和后台活动）；
- 前台服务被系统拒绝时（比如手机在后台重启了 App），会等所选的权限来源就绪后通过它重试；打开一次 App 也会补上；
- `vscreen status` 的 `host` 里有 `foreground_service`、`frozen_count`、`last_frozen_s`、`keep_alive_window`、`swallowed_total`、`watchdogs`（以及 `gate.watchdogs`、`gate.service_thread_deaths`）；被冻结过时 `degraded` 里会有 `frozen_in_background`，关键后台循环异常停止或触发熔断时会有 `watchdog_unhealthy`；
- 命令行先用 5 秒探一下 App：没有回应就直接说明“多半被冻结了”，不再干等。

**端口被占用与自动顺延（1.4.0）**：如果是还没退出的旧 Agent-VScreen 进程，App 会自己重试接管。如果是别的程序（或分身空间里的另一份 Agent-VScreen）占着默认端口 `18765`，服务会自动顺延绑定 `18765..18774` 中第一个空闲端口；`vscreen` 命令行和 Operit 工具包会通过 `GET /v2/ping?me=<令牌id>&n=<随机数>` 自动校验并找到持有当前令牌的端口，无需手动改端口。

### 语言（1.1.7）

设置里的 **语言** 可以选跟随系统（系统是中文就用中文，其他用英文）、中文或 English。它决定 App 界面、悬浮窗、通知，也决定给 agent 的一切文字：给 agent 的话、能力说明、`/skill`、`/memory`、`help`、命令返回和错误说明。英文模式下四段的标题是 `[Done]` `[Meanwhile]` `[Now]` `[Notes]`，错误是 `[Error] CODE: …`；命令名、参数、步骤和条件的写法两种语言完全一样。已安装的 `vscreen` 命令行带着语言标记（`X-VScreen-Version` 形如 `1.1.7-en`），切换语言后下一次调用会自动换成新语言的脚本和 skill。

## 2. 三种接入方式

三种方式背后是同一套命令，同一个结果。在 App 底部“接入 › 新建接入”里会自动为这个 agent 创建待兑换令牌（CLI / HTTP / Operit 默认签发 10 分钟单次有效的 `avc_...` 一次性接入码，不把长期密钥放进聊天记录或命令历史）并复制 **给 agent 的话** 发给它，它会自己装好、兑换并记住。也可以在“设置 › 令牌”里单独管理每个令牌的权限、免确认和撤销（见第 7 节）。

### 2.1 命令行 `vscreen`（推荐）

```sh
# 推荐：使用向导签发的 10 分钟一次性接入码（avc_...），经环境变量传参不落命令历史
VSCREEN_CODE=<一次性接入码> curl -fsS http://127.0.0.1:18765/cli | sh -s -- install
# 兼容：直接使用长期令牌（手工在“设置 › 令牌”创建的令牌）
curl -fsS http://127.0.0.1:18765/cli | sh -s -- install <令牌>

vscreen guide                 # 一页用法：看 → 做 → 核对
vscreen launch 设置
vscreen act "tap 搜索; type 蓝牙; key enter until text:蓝牙"
vscreen act                   # 看一眼现在的画面
vscreen done
```

- 传入 `VSCREEN_CODE=avc_...`（或参数为 `avc_` 开头）时，安装脚本会先探测本机有效端口，再通过 `POST /v2/pair/exchange` 的 JSON 请求体一次性兑换长期令牌并写入私有配置文件（不把接入码或令牌放进 URL 查询串或进程参数）。
- 需要 `curl`。有 `sha256sum`、`shasum` 或 `openssl` 之一时，命令行给每个请求签名，令牌本身不发送，回复也校验（见 7.2）。都没有时退回到发送令牌，这种方式只在本机可用。
- 退出码：0 整段按预期做完；1 停在某一步或被拒绝；2 命令写错，什么都没执行；3 连不上或回复校验失败。
- 截图下载到 `$VSCREEN_SHOT_DIR`（默认 `$TMPDIR/vscreen-shots`），文字里是本地路径。完整输出另存为同目录的 `last.txt`。
- 每条命令带一个请求号。连接中途断了，命令行用同一个请求号重试一次，拿到的是第一次执行的结果，不会执行两遍。
- App 更新后命令行会自己换成新版。新脚本通过签名请求取回并校验，校验不过不会安装。第一次安装的那一行 `curl … | sh` 没有校验可做，它信任的是你当时连的那个地址。
- 环境变量：`VSCREEN_URL`、`VSCREEN_TOKEN`、`VSCREEN_CODE`、`VSCREEN_SESSION`、`VSCREEN_SHOT_DIR`、`VSCREEN_SHOTS=none`、`VSCREEN_NO_UPDATE`。

### 2.2 MCP

把 App 里复制的 MCP 配置加进 agent（Streamable HTTP，地址 `POST /mcp`；`GET`/`DELETE /mcp` 在鉴权前直接返回 `405 Allow: POST`）。

- **双栈协议支持（`2026-07-28` 无状态 + 传统 `initialize`）**：
  - **Modern 无状态路径（`2026-07-28`）**：支持 `server/discover`（返回 `supportedVersions` 与 `_meta["io.modelcontextprotocol/serverInfo"]`）、`tools/list`、`tools/call` 与 `tasks/get` / `tasks/result` / `tasks/cancel` / `tasks/list`；`server/discover` 与 `tools/list` 固定返回确定性工具顺序并携带 `resultType: "complete"`、`ttlMs: 3600000`、`cacheScope: "public"`；严格校验 SEP-2243 `Mcp-Method` / `Mcp-Name` 请求头与 JSON-RPC body 一致（`tools/call` 核对 `params.name`，`tasks/get|cancel|update|result` 核对 `params.taskId`，不匹配返回 HTTP `400` + JSON-RPC `-32020`）；调用 `tasks/*` 未声明 `io.modelcontextprotocol/tasks` 客户端扩展返回 HTTP `400` + `-32021`（附 `data.requiredCapabilities`）；不识别的现代版本号返回 `-32022`（附 `data: {supported, requested}`）；现代路径不输出 `Mcp-Session-Id`。
  - **`io.modelcontextprotocol/tasks` 扩展**：仅当请求 `_meta["io.modelcontextprotocol/clientCapabilities"].extensions` 显式声明 `"io.modelcontextprotocol/tasks"` 时，异步任务（`bg_<id>`）、手机提问（`ask_<id>`）、手机强确认（`cfm_<id>`）与后台盯守（`keep_<name>`）返回扁平 `CreateTaskResult`（顶层 `resultType: "task"`、`taskId`、`ttlMs`、`pollIntervalMs`）；按配对授权实例 `(grantId ?: tokenId, taskId)` 严格隔离权限。`tasks/get` 在 `status == "completed"` 时直接内联 `"result"`（包含 `isError: true` 的工具拒绝/过期结果），仅内部执行异常才进入 `status == "failed"`；`ask_*` 与 `cfm_*` 等待期间始终报告 `working`（不用 `input_required`），并禁止 `tasks/update`（强确认永不经 MCP 批准）。多步 `act` 强确认批准后仅从经页态校验的安全续执行点继续（无法安全续跑或页面已变时停住不重放已生效前置步骤）；任务取消与续跑使用统一原子状态机，不覆写终态。
  - **Legacy 路径（`2025-06-18` / `2025-03-26` / `2024-11-05`）**：完整保留传统 `initialize` 握手与 `Mcp-Session-Id` 兼容。
- **工具与 Schema**：`vscreen`（参数 `command` 是一整条命令行）和按命令分开的 `vscreen_launch`、`vscreen_act`、`vscreen_status`、`vscreen_fn` 等。Schema 遵循 JSON Schema 2020-12（可重复选项如 `--on` 声明 `oneOf: [string, string[]]`）并附完整工具注解（`readOnlyHint` / `destructiveHint` / `idempotentHint` / `openWorldHint`）。
- **结果与防注入隔离**：`content` 是文字和截图；`structuredContent` 是结构化结果（`ok`、`code`、`next`、`steps`、`screen`、`screen_data`、`notes`）；`isError` 跟随结果代码。所有来自第三方应用的不可信屏幕文本均集中在顶层 `screen_data`（带 `"untrusted": true`），并在文本回复中经 `<screen-data>` 围栏与引号清洗与系统段落隔离。
- **进度与请求号**：调用带 `_meta.progressToken` 且请求头 `Accept` 含 `text/event-stream` 时，通过标准 `Transfer-Encoding: chunked` SSE 流每步发送 `notifications/progress`。传入的 `requestId` 超过 64 字符自动截断，并由服务端幂等去重表保活（锁内先命中既有 key、仅插入新 key 时淘汰最老已完成项且绝不驱逐执行中请求）。
- **取消**：断开这次请求的连接，这段操作会在当前这一步之后停下。
- MCP 客户端不会签名。内置 Tailscale 接受令牌，无需关掉“只接受签名请求”；其它直接接入方式默认要求签名，见 7.2。

### 2.3 HTTP

```
POST /v2/run
Authorization: Bearer <令牌>            （本机；其它设备见 7.2 的签名）
{"cmd": "act \"tap 搜索; type 蓝牙\"", "session": "a", "request_id": "任意唯一字符串"}
```

请求体三种写法任选：`{"cmd": "一整条命令行"}`、`{"args": ["act", "tap 搜索"]}`、`{"command": "act", "args": "tap 搜索", "options": {"fast": true}}`。

回复（HTTP 状态总是 200，结果看 `ok`）：

```json
{
  "ok": false, "exit": 1, "code": "STEP_MISSED", "next": "look",
  "session": "a", "request_id": "…",
  "outcome": "missed",
  "steps": [{"n": "1", "step": "tap 搜索", "status": "done", "x": 540, "y": 180, "changed": true},
            {"n": "2", "step": "type 蓝牙", "status": "missed", "code": "TYPE_FAILED"}],
  "screen": {"app": "com.android.settings", "activity": "…", "width": 1080, "height": 2400,
             "image": "http://127.0.0.1:18765/v2/shot/vscreen_….jpg",
             "elements": [{"n": 1, "text": "搜索", "x": 540, "y": 180, "clickable": true}], "elements_total": 23},
  "notes": [{"code": "TYPE_FAILED", "text": "…", "next": "look"}],
  "meanwhile": ["…"], "done": ["…"],
  "text": "和命令行一样的四段文字", "images": [{"name": "…", "url": "…"}]
}
```

- `ok` 为真只表示整段按预期做完。停在某一步时 `ok` 为假，`code` 是那一步的代码，`next` 是建议的下一步：`look`（先看画面）、`fix_command`（改命令）、`ask_user`（停下问用户）、`user_on_phone`（去手机端处理）、`retry_later`（稍后重试）、`none`。
- `request_id`：同一个令牌用同一个 id 重发，10 分钟内只执行一次，后来的请求拿到第一次的结果并带 `"replayed": true`。
- 请求头 `Accept: text/event-stream`：回复变成事件流，每做完一步一条 `step` 事件，最后一条 `result` 事件是上面的 JSON。
- 请求头 `X-VScreen-Lang: zh|en`：这一次请求用哪种语言回复，不影响别的调用方。
- 断开连接：正在执行的命令在当前这一步之后停下（带 `request_id` 时先等 3 秒，看调用方是否用同一个 id 重连）。
- 长操作可以加 `--async`：马上返回任务号，之后 `task <号> --wait 60s` 取结果，`task cancel <号>` 取消。
- **一次性接入码兑换（仅本机与 Tailnet `ApiServer`，永不暴露于公网 `PublicServer`）**：
  - `POST /v2/pair/exchange`，请求体 `{"code": "avc_..."}`，成功返回 `200` 及 `{"ok": true, "token": "avs_...", "token_id": "...", "name": "...", "level": "..."}` 并原子激活待兑换令牌。
  - 仅接受 `POST`（非 POST 返回 `405`），拒绝 URL 查询参数 `?code=`（返回 `400 BAD_REQUEST`，不消耗接入码）与浏览器 `Origin`（返回 `403 BROWSER_REFUSED`）；无效、已过期（10 分钟）或重复使用的接入码返回 `401 CODE_INVALID_OR_EXPIRED`。
- 其它地址：`GET /v2/ping`（不要令牌，含手机时间）、`GET /v2/help`、`GET /v2/codes`、`GET /v2/shot/<名字>`、`GET /v2/events?session=&wait=30s&again=1`。
- 服务端与 HTTP 解析限制（`HttpCore`）：同时最多 24 个连接（超出返回 503 `SERVER_BUSY`），请求头 16 KB，请求体 1 MB；读头 10 秒、读体 20 秒均以单调时钟严格收紧 socket 截止，防慢速连接占池；拒绝 `Content-Length` 与 `Transfer-Encoding` 并存、冲突 `Content-Length`、带符号/溢出 chunk 长度与超限 trailer；带 `Origin` 的请求（网页发起）一律拒绝；远程鉴权失败按通道独立计数封禁（键表上限 500 条），绝不在工作线程 `sleep`。

### 2.4 让其它设备连进来（默认关闭）

服务默认只在本机 `127.0.0.1` 上监听。要从电脑或另一台手机上的 agent 调用，有三种办法。

在底部“接入 › 远程访问”（或“新建接入”选 **其它设备** 时）打开 Tailscale 设置，选一种：关（默认）、**内置**、**外部 App**。

**办法一：内置 Tailscale（1.1.6，推荐）**

不用在手机上装 Tailscale App。App 里自带 Tailscale 官方的 tsnet 组件，只在 Agent-VScreen 自己的进程里联网，不占用系统的 VPN（你原来的 VPN 照常用），也不需要 root。

1. 选 **内置 Tailscale**，几秒后出现 **在浏览器里登录 Tailscale**，用你电脑上 Tailscale 的同一个账号登录。登录状态会记住，以后不用再登。
   - 1.1.7：如果一直没出现登录按钮（状态停在 NeedsLogin），内置节点每 20 秒会重新向 Tailscale 要一次登录链接，页面上也有 **重新获取登录链接**。等了 15 秒以上还没有，页面会写明原因：多半是手机连不上 `controlplane.tailscale.com`，换个网络或打开能访问它的代理就会出现；也可以用 auth key。
   - 也可以在“高级”里填一个 auth key 代替浏览器登录；key 只在第一次注册时用，注册成功后就从手机上删掉。
   - “高级”里还可以填自建的控制服务器（例如 Headscale）。1.1.8：地址可以只写域名（`hs.example.com`，会补成 `https://hs.example.com`），要和 Tailscale App 里“登录服务器”填的一样；每个服务器的登录状态分开保存（换服务器不会带着旧登录，换回来也不用重登）；填了 auth key / 预授权 key 会清掉这个服务器的旧状态，用 key 重新注册。Headscale 的注册页一般给出一条命令，在服务器上运行就行。使用自建控制服务器时，内置节点向 Android 提供的 DNS 并发查询。一个网络先报告域名不存在时，节点会短暂等待其他网络返回地址。请确认手机所在网络可解析自建域名。常规解析或连接失败时，Tailscale 客户端还可能通过其 DERP 节点查询该域名。连不上时页面会写出服务器名和节点报告的错误（`status` 的 `remote` 里也有 `control` 和 `login_error`）。
   - 出口节点：内置节点是用户态的，只转发其它设备发给 Agent-VScreen 的请求，不接管手机自己的上网，所以没有出口节点。手机需要靠出口节点上网时（比如经家里的旁路由），让 Tailscale App 一直开着，选“外部 App”模式即可。
2. 手机会以一台单独的设备 `agent-vscreen` 出现在你的 Tailscale 网络里，页面上会显示它的地址（`100.x.x.x`）和名字。
3. 连好后，同一处往下点 **复制给 agent 的话**，发给那台设备上的 agent：里面的安装命令、MCP 配置和能力说明都已经是 Tailscale 地址。手动调用的话，在电脑上（已登录同一个 Tailscale 账号）：

```sh
VSCREEN_URL=http://100.x.x.x:18765 VSCREEN_TOKEN=<令牌> vscreen status
```

- 只有你 Tailscale 网络里的设备连得进来，局域网和公网都连不进来；令牌照样要。
- 请求先到内置节点，再转给本机的 `127.0.0.1:18765`。转发时带一个每次启动随机生成的暗号，手机上别的应用无法冒充远程请求。首页会显示“最近来访”是哪个 Tailscale 账号和设备。
- 不上传日志给 Tailscale。
- 限制：
  - 安装包因此大了约 9 MB（组件解压后约 21 MB，只有 arm64）；
  - App 被系统冻结时，远程请求也会等（和本机调用一样，见第 1 节的后台设置）；
  - 发起调用的那台电脑仍要在 Tailscale 网络里（若需连接无法加入 tailnet 的云端客户端，见下方“办法四：公网 MCP”）。
- `vscreen status` 的 `remote` 写明当前模式、状态、地址和最近来访。
- 点 **退出 Tailscale 登录** 会让这台设备退出你的网络；管理后台里的设备条目要你自己删。

**办法二：手机上已装的 Tailscale App**

1. 手机和另一台设备都装 Tailscale，登录同一个账号。
2. 选 **Tailscale App**。服务会多监听一个手机的 Tailscale 地址（`100.x.x.x`）；地址变了会在 20 秒内跟着换。
3. 同样在“其它设备上（Tailscale）”下面复制给 agent 的话，或在另一台设备上用 `VSCREEN_URL=http://100.x.x.x:18765 …` 调用。

- 只有 Tailscale 网络里的设备连得进来，局域网和公网都连不进来。
- 流量由 Tailscale 加密，令牌不会在 Wi-Fi 上明文传。

**办法三：SSH 端口转发**

不用改 App 的任何设置。如果手机上有 Termux 并开着 `sshd`，在另一台设备上运行：

```sh
ssh -p 8022 -L 18765:127.0.0.1:18765 <手机地址>
```

之后在那台设备上照常用 `http://127.0.0.1:18765`。

**办法四：公网 MCP（2.0，实验性，默认关闭）**

面向无法加入私有 Tailscale 网络的云端 MCP 客户端（如 claude.ai、ChatGPT 自定义连接器或无浏览器的云端主机），可在 **接入 › 云端（公网 MCP · 实验性）** 手动开启：

- **独立隔离入口**：仅监听本机 `127.0.0.1:18785`（`PublicServer`），由通道代理注入常量时间校验的 `X-AVS-Public` 隔离密钥与真实客户端来源头 `X-AVS-Public-Src`（按 IPv4 `/32` 或 IPv6 `/64` 分桶限流；Cloudflare 源站会剥离伪造头并严格校验 `Cf-Connecting-IP` 字面量）。只暴露 OAuth 发现（`/.well-known/oauth-protected-resource`、`/.well-known/oauth-protected-resource/mcp`、`/.well-known/oauth-authorization-server`）、注册/授权/设备码/换票端点（`/oauth/register`、`/oauth/authorize`、`/oauth/authorize/status`、`/oauth/device_authorization`、`/oauth/device`、`/oauth/token`）和 `POST /mcp`（`GET`/`DELETE /mcp` 在认证前直接返回 `405 Allow: POST`），其余路径一律 `404`（包括 `/v2/pair/exchange`）。本地令牌与 Tailnet 头在公网入口一律拒收；公网 500/502 错误仅返回脱敏通用提示，不暴露内部异常类名或堆栈细节。
- **三种通道**：**Tailscale Funnel**（官方 Tailscale 账号，域名会出现在公开证书透明度日志中）、**Cloudflare Tunnel**（自有域名，支持 Headscale）或 **ngrok**（免费固定域名）。使用 Cloudflare Tunnel / ngrok 时，HTTPS 在其服务端终止后再转发至手机。
- **OAuth 2.1 + CIMD + 主动输入配对码**：支持授权码（强制 PKCE S256、RFC 8707 `resource` 绑定与 RFC 9207 `iss` 参数校验）、DCR（校验 `application_type` 与重定向 URI）、**CIMD（Client ID Metadata Documents，`client_id_metadata_document_supported: true`）**与 RFC 8628 设备码（含仅设备码客户端重启持久化）。CIMD URL 仅接受默认 443 端口且带非空路径的 `https://` 公网域名，先解析全部 DNS 记录拦截回环/私网/链路本地/CGNAT（含 Tailscale `100.64.0.0/10`）/IPv6 ULA/组播等地址，随后**直连已校验的 IP 列表并以原始主机名完成 TLS SNI 与证书主机名校验**（杜绝 DNS 重绑定），禁用重定向、限时 5 秒、限 64 KB JSON、按来源限流 10 次/小时并按 `Cache-Control` 缓存（上限 64 条 / 24h）。同一 CIMD URL 的每次独立配对均生成独立授权实例（`GrantRecord`）与内部令牌，权限档位、MCP 任务可见性与撤销互不串联。外部发起配对时**静默入队（上限 32 条），手机不会自动弹窗或发通知**；用户打开手机上的 Agent-VScreen 输入 8 位配对码后才弹出强确认卡（CIMD 客户端显示绿色「已验证元数据文档来源域名」，DCR 客户端显示灰色「未验证（动态注册，名称由对方填写）」警示；仅接受锁屏生物识别/PIN，禁用长按）。可选开启**严格配对窗口**（默认拒绝一切新客户端注册与配对，仅在手动打开的 3 分钟窗口内放行并在批准后自动关闭）。
- **令牌、容错与生命周期**：访问令牌 1 小时过期，刷新令牌 30 天轮换并内置**内存级 30 秒单次重试宽限**（移动弱网丢失响应时允许同一旧刷新令牌重试一次并返回同组新令牌；客户端一旦使用新令牌、超过 30 秒或二次重放即撤销宽限并立即吊销**该配对授权实例**的全部令牌，不影响共享同一 CIMD URL 的其他独立配对）；`/oauth/token` 按来源限流，无效/已撤销 Bearer 仅记入调用方来源桶不锁死合法客户端；公网客户端**永不开放 `system` 权限**；连续 **24 小时无已认证请求**（仅计有效 Bearer 的 `/mcp` 与 HTTP 200 的 `/oauth/token`，扫描和未认证流量不续期）自动关闭，App 版本更新后自动恢复关闭。

**私有远程安全设置**（“接入 › 远程访问”下面）：

- **只接受签名请求**（默认开）：除了经内置 Tailscale 转发的请求，其它远程请求必须按 7.2 签名。`vscreen` 命令行会自动签名。
- **只允许这些设备**：填设备名、登录名或 100.x 地址；留空表示网络里的所有设备都可以连（仍然要令牌）。
- **自建控制服务器的域名后缀**：用 Headscale 等自建服务器、MagicDNS 名字不以 `.ts.net` 结尾时填这里，否则用名字访问会被当成不认识的 Host 拒绝。
- “外部 App”模式只在系统里类型为 VPN 的网络上找 100.64.0.0/10 的地址。运营商给移动网络分配的同网段地址不会被当成 Tailscale 地址。
- 同一来源一分钟内验证失败 8 次，暂停接受它的请求 5 分钟，并发通知告诉你。

## 3. 命令

完整列表、每个参数和权限范围见 [docs/REFERENCE.md](docs/REFERENCE.md) 或 `vscreen help all`。

- **核心**：`launch`、`act`、`note`、`keep`、`events`、`task`、`title`、`done`、`stop`、`release`、`handoff`、`status`、`apps`。
- **问用户**（1.3.0）：`ask`、`teach`。
- **声音与看图**（1.4.0）：`look`（含原 `zoom`、`frames` 兼容别名）、`sound`。
- **辅助**：`spots`、`collect`、`schedule`、`fn`（Android 16+ AppFunctions 只读探测）、`fix`、`diag`、`guide`、`help`。

`launch` 和 `act` 返回固定的四段：【做了什么】、【这段时间】（从上次返回到现在，包括 agent 思考的时间）、【现在】（编号元素和截图）、【注意】（每条以代码开头）。手机只报事实，画面是什么意思由调用方的模型看图判断。

### 3.0 能力清单（由源码自动生成）

这份清单和 agent 收到的说明来自同一张能力表（`Abilities.kt`），由测试自动写入，不要手改。

<!-- abilities:start -->
- **用户要我在手机上做任何事：打开某个应用、查信息、比价、看某页内容、填表、发帖、测试应用……**：`vscreen launch <应用>` 打开并看，`vscreen act "<步骤>"` 点、滑、输入、按键，每次都返回截图和元素列表
- **我要为一件会用到手机的事设定时：用户说了定时、每天、每周、到点（签到、打卡、抢购、定时查看），或我自己建了定时任务、周期任务、cron、自动化、提醒**：两边都要设：我的定时只叫醒我，叫不醒手机。同一时间用 `vscreen schedule "<时间>" --name <名字>` 给手机预约，它会在每次之前拉起服务、检查权限，没准备好就提醒用户；改时间、取消时两边一起改
- **要等一件以后才发生的事：到货、降价、有回复、直播开始、排队轮到、下载完成；或我想隔一会儿再看一次画面**：`vscreen act --stop "text:到货" --keep notify` 让手机一直盯着，看到就通知用户（`--keep wake` 改成叫醒我，用 `vscreen events` 取）
- **要读完一个很长的列表或整页内容（全部订单、聊天记录、搜索结果、评论）**：`vscreen collect --pages 10` 自动翻页，返回去重后的每一条
- **不确定用户指的是画面上哪个东西（哪个商品、哪个按钮、哪条消息），或要用户选一个**：`vscreen ask "你说的是哪一个？"` 让用户在手机上的副屏画面里点出来（也可以 `--choose`、`--text`）
- **一个流程说不清、试了几次都不对，或用户自己更清楚怎么做**：`vscreen teach "要演示什么"` 请用户在副屏上亲手做一遍，存成一段，之后 `act "use 段名 参数"` 照做
- **同一个应用会反复用：有固定入口、每次都弹的窗、要先暂停的画面，或对机器节奏敏感**：`vscreen note` 给这个应用记经验、存步骤（`note save`）、设前置/收尾步骤和仿人节奏，下次 launch 会列出来
- **有一步只能用户本人做（密码、验证码、人脸、扫码），或要用的应用用户正在用**：`vscreen handoff out` 把应用交到用户主屏并告诉他做什么；`vscreen handoff in` 把主屏上的应用接到副屏
- **应用没声音，或用户要让某个应用静音、恢复声音**：`vscreen sound` 查看，`sound mute|unmute <应用>` 静音或恢复
- **切换或暂存应用**：`vscreen tasks` 查任务；`launch --keep` 切回原页；`handoff park/unpark` 暂存或取回
- **要做的事超出我的许可（如卸载、系统操作）**：`vscreen permissions` 查权限，`vscreen elevate <类别> --why 原因` 申请
- **画面上的字太小、是图标，或者根本没有字（游戏、画布、图表），或想回看刚才发生了什么**：`vscreen look` 要图：现在的画面、放大一块、回看录像都用它；`vscreen spots` 找可点的色块
- **连不上手机、命令一直失败或出了怪问题**：先分清是没开还是坏了：没在运行、服务关着或收起是用户的选择，转告用户打开即可，不用修；`vscreen status` 里 ready 为假时才是故障，`vscreen fix` 修它列出的项，也恢复被留下静音的应用
<!-- abilities:end -->

**在副屏切换与暂存应用**：用 `vscreen tasks` 查看副屏应用的包名、任务 ID 和前台状态；`vscreen launch <应用> --keep` 把已有任务切到前台，不重启应用（不在副屏上时正常启动）。用 `vscreen handoff park <应用>` 把任务移到隐藏停放屏，再用 `vscreen handoff unpark <应用>` 移回副屏前台；`tasks` 不列出停放的任务，当前目标仍是被停放的应用时 `look` 和 `act` 返回 `APP_PARKED`，本轮结束（`done` 或空闲超时）、Stop 或用户接管时会关闭停放任务。

### 3.1 一步没达到预期，这段就停在那一步

后面的步骤是照着预期的画面写的，画面不对就不再往下做。这时结果是失败（退出码 1，`ok` 为假）。会让一段停下的情况：

| 代码 | 情况 |
|---|---|
| `STEP_MISSED` | `until` 或 `wait` 到时间也没等到 |
| `NOT_FOUND` | 画面上没有你写的字。回复里列出相近的字和输入框，各带坐标 |
| `AMBIGUOUS` | 你写的字在画面上有好几处。什么都不点，回复里列出每一处和坐标。只有一处可点、其余是普通文字时不算歧义 |
| `STALE_ELEMENT` | `#N` 指的元素已经不在原来的位置。同一页上它只是挪了位置时会点新位置并说明；页面变了就什么都不点 |
| `TYPE_FAILED` / `FOCUS_ELSEWHERE` | 没有获得焦点的输入框，或输入后框里没有你的字 |
| `INPUT_BLOCKED_SCREEN_OFF` | 主屏熄屏时系统禁止副屏接收触摸，且该步无可用无障碍节点或未开启“需要时点亮” |
| `CONFIRM_PENDING` / `USER_DENIED` | 需要用户在手机上确认的一步，用户没答复或拒绝了（见第 7 节） |

`NO_CHANGE` 是提示，不让一段停下：一个动作之后，观察窗口内没检测到画面变化（见 3.2），常见原因是没点中。比较的是采样画面，忽略遮罩区域和微小差异，所以它不能确认这一步一定没生效，看图核对。这一步的回复末尾会写“画面没有变化”。

### 3.2 等待

- 没写 `until` 的动作等画面“稳定”，最多 3 秒。**稳定**的意思是：画面连续 0.5 秒完全不动，或者至少 1.2 秒以来只剩幅度小而均匀的循环变化（转圈、光标闪烁、轮播）。1.1 里要求完全不动，带动画的页面每一步都会等满 3 秒。
- 没写 `until` 的动作，首次等待结束后（画面稳定了，或 3 秒到时间仍在变），画面和动作前一样时再看最多 1.5 秒，等反应慢的应用。回复里的时刻都从动作完成算起，分三种结果：
  - **晚到的变化**：这段时间里画面变了，就再等它稳定（最多 3 秒），回复写首次变化和结束两个时刻，例如“画面在动作 2.0s 后才变化，2.6s 时稳定”；到时间还在变就写“画面在动作 2.0s 后才变化，等到 5.0s 还在变”。
  - **停止**：观察时或随后等稳定时满足了 `--stop` 的条件，回复写“X 时停止条件 …”，这一段停在这一步，后面的步骤不做。这时即使画面没变、末尾带“画面没有变化”，也不给 `NO_CHANGE`。
  - **没有变化**：1.5 秒内都没检测到变化，才写“画面没有变化”并给 `NO_CHANGE`。所以真没点中的一步要多等约 1.5 秒；变化晚于这段时间的，仍会报 `NO_CHANGE`。
- 写了 `until` 的动作、`--fast` 下的中间动作，以及 `type` 和编辑键，不做这项检查；`--fast` 的最后一步按上面的普通规则做。
- `still` 条件仍然是“完全不动”。`stable` 是上面这种“稳定”。
- 感知由新画面驱动：副屏出了新的一帧就看一次，画面不动时每半秒看一次。
- `--fast`：动作之间不等，最后一步仍然等。用在游戏和连续点击上。

### 3.3 目标的写法

- `tap 登录`：画面上的字，先找完全一样的，再找包含的；
- `tap #3`：【现在】列表里的编号；
- `tap 100 200`：坐标；
- `tap 1`：只写一个数，就是点画面上的这个数字。

### 3.4 每段前后的固定步骤

`note before "<步骤>"` 和 `note after "<步骤>"` 记下之后，这个应用上每一段带步骤或带 `--watch` 的 `act` 会先做前置步骤，最后做收尾步骤（比如游戏的继续和暂停）。`--bare` 跳过它们。1.1 的 `note pause` / `note resume` 自动对应到 `after` / `before`，已经记下的内容不用重写。`note realtime` 取消了，改用 `--fast`。

笔记带着是谁记的、记的时候应用是哪个版本。应用升级后，`launch` 列出旧笔记时会注明“可能已过时”。

### 3.5 排队

同一块副屏上的命令一条一条执行，按到达的顺序排队。排队超过 60 秒返回 `QUEUE_TIMEOUT`，这条没有执行。不占用副屏的命令不排队（`status`、`apps`、`fn`、`events`、`task`、`title`、`permissions`、`elevate`、`keep`、`sound`、`ask`、`teach`、`schedule`、`fix`、`diag`、`guide`、`help`）。

### 3.6 按键

`key` 接受 back、enter、tab、del、space、esc、search、menu、方向键、paste、copy、cut、page_up、page_down、数字 keycode，以及修饰键组合（如 `key ctrl+a`、`key ctrl+c`、`key shift+tab`、`key alt+f4`；系统全局快捷键如 `meta+*`、`alt+tab` 会被拒绝以免影响主屏）。没有 home 和多任务键：副屏上没有桌面，这两个键会作用到用户的主屏。

### 3.7 1.3.0 新增

- **`ask "问题"`**：把问题放到手机上。默认让用户在副屏画面上点一下（`--point`），返回坐标和那里的元素；`--area` 框选，`--choose 甲|乙|丙` 选择题，`--text` 文字回答。默认等 50 秒（`--wait`，最长 5 分钟），没答复返回 `ASK_PENDING`，之后重发同一个问题接着等；`--async` 立刻返回任务，最长等 15 分钟直到用户回答。用户拒绝时返回 `ASK_DECLINED`。
- **`teach "要演示什么"`**：请用户在副屏上演示一遍。用户在审阅页保存后，返回名字、应用、步骤和空位数。用户也可以不经请求自己录。点在画面上的字记成 `tap 字`，每步之后的结果记成 `until`；输入的字变成空位 `{1}`、`{2}`，密码不保存。
- **`act "use 名字 值1 值2"`**：重放存下的一段，按顺序填空位。和别的步骤一样，没达到 `until` 就停在那一步。
- **`schedule "时间"`**：预约 agent 来用手机的时间。写法 `"07:00"`、`"07:00,19:30"`、`"weekdays 08:30"`、`"mon,wed,fri 21:00"`、`"every 2h from 08:00 to 22:00"`；只约一次写 `"today 10:30"`、`"tomorrow 07:00"`。`--name` 起名，`--for` 每次保持多久，`schedule off 名字` 取消。每个窗口开始前 3 分钟拉起服务、检查所选的权限来源和连接，没准备好就通知用户，结果作为事件发给本会话。
- **`frames`（1.4.0 起合入 `look --last …`，旧名仍可用）**：回看录像。默认是上一次返回的【这段时间】，每个动作前一张、圈出点的位置；`--last 30s`、`--from 14:03:10 --to …`、`--every 0.5s`、`--count`、`--detail high`。
- **`status`** 开头多了 `ready`（现在能不能用，不能时说原因）、`remote`（内置 Tailscale 的真实连接状态、中继、断开次数）；`host` 里多了 `previous_exit`（上一个 App 进程为什么结束）。
- 命令行在命令执行中发现手机上的 App 重启了，返回 `APP_RESTARTED`；一分钟没回应返回 `NOT_READY`，不再一直等。

### 3.8 1.4.0 新增

- **`look`**：统一主动看图入口。不带参数返回当前画面；`look x1,y1,x2,y2` 放大一块区域（原 `zoom`）；`look --last 30s` / `--from … --to …` 回看录像（原 `frames`）。支持 `--detail low|normal|high`、`--marks on|off`、`--layout auto|single|strip|3x4`、`--page`（单次最多 8 张图、合计约 3 MB）。回复默认开启图片去重（画面无变化时不重复附图，需要看图时显式调 `look`）。
- **`sound`**：应用静音管理。`sound`（或 `sound list`）查看当前被静音的应用、手动静音列表、副屏允许出声列表及副屏自动静音开关；`sound mute|unmute <应用>` 全局静音或恢复；`sound plays <应用> on|off` 允许特定应用在副屏出声；`sound screen on|off` 开关副屏自动静音。`fix` 不带参数时也会自动检查并恢复异常遗留静音的应用。
- **步骤增强**：
  - `check "<说明>" [unless <条件>]`：停在这一步把当前画面交回给 agent 核对（如核对收件人、金额）；若满足 `unless` 条件则自动放行继续。
  - `type "文字" slow [200ms]`：按指定间隔逐字输入，适配边打字边联想的搜索框或网页监听器。
  - `use 段名 参数… [from N]`：重放保存的段或示教段（首次从头跑通后自动记录起点页面，之后调用前先核对起点；`from N` 可从第 N 步续跑）。
- **主屏/副屏归属保护（`APP_WITH_USER`）**：当用户正在主屏使用同一应用（或应用刚被 `handoff out` 交到主屏）时，阻止后台误操作并提示等待 `handback` 事件，或先 `ask` 征得同意后带 `--asked` 另开。

## 4. 会话和“回合”

- **会话**：一块副屏同一时间只属于一个会话。会话名取请求头 `X-VScreen-Session`、参数 `session` 或环境变量 `VSCREEN_SESSION`，默认是 `default`。另一个会话的回合还没结束时，会返回 `BUSY`（同一个会话自己的命令则排队执行）。返回里写明：
  - 副屏归哪个会话；
  - 那个会话是不是正在执行命令；
  - 还要安静多久才自动让出；
  - 现在就要接手的办法：那个会话执行 `vscreen done` 或 `vscreen release`。

  回合结束后，任何会话都能接手，页面保留。
- **用户自己使用副屏**：用户可以在首页点 **打开应用**，无需 agent 发命令。这会开启用户自己的回合；用户使用期间 agent 会收到 `BUSY`，交还后 agent 才能接手。
- **回合**：从某个会话发出第一条工作命令（如 `launch`、`act`、`look`、`collect`、`spots`）起算回合开始，之后安静满 N 分钟算回合结束（N 默认 3，可以在 App 里改成 1、3、5、10 或 30）。
  - **只读查询不重开回合**：不触碰副屏的被动查询命令（`status`、`diag`、`apps`、`fn`、`permissions`、`events`、`task` 查询、`sound` 查询、`keep` 查询、`schedule` 查询、`guide`、`remembered`、`help`）在会话无进行中回合时不开启新回合也不获取亮屏锁；回合进行中时照常顺延安静计时。
  - 回合进行中：副屏保持唤醒，后台录制不停，所以下一次的【这段时间】能覆盖 agent 思考的这段时间；若在设置中开启“任务中保持亮屏”或“需要时点亮”，冷启动建屏后会立即持有亮屏锁，回合结束、`done` 或停止后立刻释放。
  - 回合结束后：副屏省电暂停，10 分钟后释放；下一条工作命令会自动唤醒或重建副屏。
  - `vscreen done`：这一轮做完了，回合马上结束，不用等 N 分钟。
- **熄屏输入可达性与路由（`status.screen_off`）**：
  - `screen_off.input` 为 `"ok"` / `"blocked"` / `"unknown"`（主屏亮着时为 `"ok"`），表示主屏熄灭时系统是否允许副屏接收触摸注入。
  - 当系统在熄屏时拦截副屏触摸（`"blocked"`）时：按文字或 `#编号` 定位且存在对应无障碍节点的点击、长按、滚动和文字输入会自动切换为无障碍节点动作，并在步骤回执及 `steps[].via` 中标注 `via=a11y`；纯坐标点按、偏移点击、滑动、拖拽、按键与闭环手势在设置为“需要时点亮”（`wake_when_needed`）时会短暂点亮主屏至锁屏页执行（标注 `via=wake`，受频控与过热限流约束），否则返回 `INPUT_BLOCKED_SCREEN_OFF`。
  - `screen_off.keep_screen_on` 为 `{"enabled": bool, "mode": "allow_sleep"|"keep_on"|"wake_when_needed", "held": bool, "woke": n}`，其中 `enabled` 为兼容字段（等价于 `mode != "allow_sleep"`）。
- **过热保护状态（`status.thermal` 与 `THERMAL_STOP`）**：
  - `status` 与 `diag` 及 `THERMAL_STOP` 错误载荷均包含结构化对象 `thermal: {"battery_c": number|null, "status": 0..6|null, "phase": "normal"|"locked_hot"|"cooling"|"ready", "hard_stop_enabled": bool, "retry_after_s": number|null}`。
  - 默认开启“过热自动停止”时，电池达到 45.0°C 或系统热状态 ≥ 3（严重）触发两段式锁存：离开危险区后电池 < 43.0°C 且热状态 < 3 持续 60 秒即可在首页电源菜单手动恢复并进入降负载态（1 小时内再次触发则收紧为 < 39.0°C、热状态 < 2、持续 180 秒）。用户也可在 **设置 › 过热保护** 确认风险提示后关闭自动停止（保留降负载、危险区单次高优提醒与系统自带热保护）。
- **标题**：`vscreen title "在比价耳机"` 让用户在悬浮窗上看到你在做什么，最多 40 字，回合结束时清除。

## 5. 盯守和事件

`act --stop "条件" --keep notify|wake [--for 2h]`：本轮之后在手机上继续盯这个条件，看到后：

- `notify`：发一条系统通知给用户；
- `wake`：把一条事件放进这个会话的事件队列。命令行和 MCP 用 `vscreen events --wait 60s` 取，HTTP 用 `GET /v2/events?wait=60s`。

1.2.0 的变化：

- **事件至少送达一次**。`events` 取走的一批会保留到你下一次 `events`，下一次调用等于确认上一批收到了。回复丢了就用 `events --again` 把上一批再取一遍。每条事件有只增不减的 `#编号`，用它去重。
- **事件和盯守都写在磁盘上**。App 被系统杀掉再起来，没取走的事件还在，没到期的盯守会接着盯（副屏本身不会恢复：盯守会等到它的会话重新 `launch`）。
- **广播默认不发**。1.1 把事件广播给手机上所有应用，内容里有画面上看到的字。现在只有在 App 的“设置 › 事件广播”里填了接收应用的包名，才会把广播 `com.kilon.agentvscreen.action.EVENT` 定向发给那一个应用。
- `events --wait` 只在这个会话有正在运行的 `--keep wake` 盯守、待答复的 `ask`/`teach` 或用户正接管/暂停本会话副屏时才等待。
- `keep` 分两组列出“正在盯”和“已结束”。`keep off <名字>` 对两组都有效，`keep off all` 全部停掉并清空。

## 6. 悬浮窗和全屏

有副屏时，悬浮窗出现在所有应用上方。画面铺满一张圆角卡片，外面是一圈会呼吸的边框，颜色表示现在谁在用副屏：

| 边框 | 状态 |
|---|---|
| 流动的彩虹色 | AI 正在操作 |
| 浅蓝，慢慢呼吸 | AI 待命（回合中，在思考）；标题上显示还剩多久进入省电 |
| 绿色 | 你接管了 |
| 红色 | 你按了停止 |
| 灰色 | 空闲、省电暂停 |

左上角的标题显示 agent 用 `title` 设的文字，没有设时显示状态。

**手势**：

- **单指拖动**：移动小窗。松手时手指还在动，小窗会顺势滑一段（惯性）；快速甩动只移动位置，不改变小窗大小。
- **推到左右边缘**：松手（或惯性滑出）时小窗有一半以上越过左/右屏幕边缘，会滑入边缘折成胶囊标签；点一下标签滑出展开，或把标签往里拉直接跟手展开。
- **双指捏合**：唯一改变小窗大小的方式。以两指中点为锚点，宽度在 120dp 到屏幕的 85% 之间。
- **单指按住 0.5 秒不动**：接管。按住时会出现一个绿色的圈慢慢填满。轻点画面只会提示这几个手势，不会误触。
- **接管后**：在画面上点、长按、滑动都会落到副屏上。这时拖左上角的标题可以移动小窗。

**按钮**：

1.1.6 起，每个按钮只做一件事，位置也固定，快速连点不会误停 AI：

- **右上角 ⤢ 全屏**。
- **右上角 ×**：任何时候都在，点一下就生效。关闭只关乎你看不看，**不会**停住或锁住 AI。
  - AI 在操作、待命或你接管时：只收起小窗，AI 照常在后台工作（接管会自动交还）。通知栏会显示“AI 在后台工作（小窗已收起）”，点 **显示小窗** 可以拿回来；agent 的下一轮开始时小窗也会自己回来。`status` 里 `window_hidden_by_user` 为 true。
  - 没有任务时（空闲）或你已经停止了 AI：直接关掉副屏。agent 下次 launch 会建新的。
- **左下角“■ 停止 AI”**（红色、带字）：只在 AI 操作中或待命时显示。按下后：
  - agent 的每条操作都返回 `USER_STOPPED`，返回里写明你几点停的，以及你可以在哪里解除，agent 会被要求原样转告你；
  - 悬浮窗中间写着“已停止 AI”的说明；
  - 通知栏出现一条常驻的 **已停止 AI** 通知，带 **允许继续** 按钮；
  - App 首页顶部出现“你停止了 AI”，带 **允许继续**；
  - 关掉悬浮窗或副屏不会解除停止状态，通知会一直在，直到你在通知、悬浮窗（交还 AI）或首页里放开。agent 自己解除不了。
- **交还 AI**（绿色）：接管或停止后显示。agent 的下一条操作会先返回 `REREAD_REQUIRED`，让它看清现在的画面再继续。
- 接管、停止、交还都是“换手”：换手之前收到的命令，之后的任何点击、输入、滑动都不会再做（返回 `CONTROL_CHANGED`，先看一眼再重发）。这条在 App 和屏幕服务两边各查一次：正在做的长按、滑动会立刻以“取消”结束，不会变成一次点击。
  - agent 拿到任何一次带【现在】的返回（launch、act，或不带步骤的 act），就算重新看过画面，之后的动作照常执行。launch 打开的是新页面，不会因此被拒（1.1.5）。
  - 悬浮窗上的 × 只是释放副屏，同时清掉这个标记，agent 之后直接 launch 就行。
  - 没有 `resume` 命令。agent 写 resume 时会得到说明：看画面用不带步骤的 act；用户点了“停止”时，只有用户能交还。

**全屏页**是原生页面。打开全屏页时悬浮窗会先藏起来，离开全屏页后再出现：

- 沉浸式，画面按比例放大，带同样的边框；
- 长按 0.5 秒接管；
- 接管后，底栏有返回、打字（输入到副屏里有焦点的输入框）、回车和交还 AI；
- 没有接管时，底栏有接管和“移到主屏”（把副屏上的应用挪到你的主屏）。

通知栏里的 **查看副屏** 也能打开全屏页。

如果你在停止状态下关掉了副屏，通知栏的“已停止 AI”和 App 首页顶部的提示都会一直在，点“允许继续”才会放开。

## 7. 权限和安全

### 7.1 每个 agent 一个令牌

App 的“接入 › 新建接入”或“设置 › 令牌”里可以新建令牌，形如 `avs_<id>_<密钥>`；向导为 CLI / HTTP / Operit 新建 agent 时先创建 `pending` 待兑换条目并签发 10 分钟单次有效的内存接入码（`avc_...`，上限 16 条 FIFO，仅存 SHA-256 摘要，重启自动作废），经本机/Tailnet `POST /v2/pair/exchange` 兑换后才原子激活长期令牌；通过公网 MCP 配对的客户端也会生成独立的 `publicOnly` 令牌条目（本地与 Tailnet 入口拒收）。每个令牌有：

- **名字**：出现在操作日志、确认页面和“最近调用方”里。
- **权限范围与分项权限表**：对应 `look`（只看：状态、应用列表、截图分析）、`operate`（打开和操作应用）、`system`（另外允许 `fix` 改系统设置等；公网客户端永不开放 `system`）。范围不够或类别设为“不允许”时返回 `SCOPE_DENIED`，什么都不执行。
- **单独撤销**：不影响别的 agent，撤销时同步作废尚未兑换的一次性接入码与关联公网 OAuth 令牌。
- **自己的会话**：不同令牌的 agent 即使用同一个会话名，也互不相干，抢不了对方的副屏。
- App 只保存新令牌密钥与公网 OAuth 令牌的 SHA-256 摘要，原始凭证不落盘也不进日志。

原来的那一个令牌叫主令牌，照旧可用，权限是 `system`。

### 7.2 签名请求

令牌不上网络的调用方式。K = sha256hex(令牌的密钥部分；主令牌就是整个令牌)。

```
X-VScreen-Key:   <令牌 id；主令牌写 main>
X-VScreen-Time:  <unix 秒，和手机相差不超过 300 秒>
X-VScreen-Nonce: <8 到 64 位字母数字，每个请求不同>
X-VScreen-Sign:  sha256hex( K \n Time \n Nonce \n METHOD \n 路径和查询串 \n sha256hex(请求体) \n K )
```

- 被截获的请求不能重放（时间窗加 Nonce），不能改动（请求体和路径都在签名里）。
- 对签名请求的回复带 `X-VScreen-Proof: sha256hex( K \n Nonce \n resp \n sha256hex(回复体) \n K )`，调用方可以确认回复来自这台手机、没被改过。事件流回复没有这个头。
- 网址里的 `?token=` 不再接受：网址会留在日志和历史记录里。
- 本机（127.0.0.1）仍然接受 `Authorization: Bearer <令牌>`。经内置 Tailscale 进来的请求（1.3.0 起）也只要令牌：这条线路本身已经加密并认证了设备。其它私有网络远程请求默认必须签名。公网 MCP 入口仅接受 OAuth 2.1 颁布的短期 Bearer 访问令牌。

### 7.3 权限表与强确认（2.0）

2.0 起每个接入的 agent 拥有独立的**六行权限表（`Policy`）**，可在接入向导或 agent 详情页选择档位预设（**只看** / **稳妥** / **省心（默认）** / **全权**）或逐行**自定义**。收紧权限立刻生效（并同步收回相关临时许可），放宽任何一行都必须经手机指纹/锁屏强确认；agent 可通过 `vscreen permissions` 查看当前权限表，并用 `vscreen elevate <类别|档位> --why <原因>` 向用户申请临时或永久提权：

| 权限类别 | 典型操作 | 只看 | 稳妥 | 省心（默认） | 全权 |
|---|---|---|---|---|---|
| **应用操作**（`operate`） | 启动应用、点击、输入、滑动 | 不允许 | 允许 | 允许 | 允许 |
| **敏感操作**（`caution`） | 可恢复删除、购买中间步骤、`stop --force`、`handoff in` | 不允许 | 问我 | 允许 | 允许 |
| **不可撤销操作**（`destroy`） | 卸载应用、清除数据、永久删除、注销账号、恢复出厂 | 不允许 | 问我 | 问我 | 问我 |
| **支付**（`money`） | 付款、提交订单、转账、充值（**不可设为“允许”**） | 不允许 | 问我 | 问我 | 问我 |
| **系统操作**（`system`） | 修复权限、系统级命令（**公网客户端永不开放**） | 不允许 | 不允许 | 不允许 | 允许 |
| **高频操作**（`rate`） | 每分钟超过阈值次操作（设为“允许”时仅记录并通知） | 问我 | 问我 | 允许 | 允许 |

应用级规则（禁止 / 只读 / 每次确认）独立于 agent 权限表生效。**主流金融与支付类应用**（钱包、银行、证券与交易所等，不含高频通用社交应用）默认启用「每次确认」（`FinanceApps`），启动时附带 `FINANCE_APP_RISK` 风险提示，且用户仅可通过生物识别强确认将其放宽为显式允许；同时在支付金额 + 支付语境页面点击“确认/确定/继续”时自动升级为支付强确认。`act --human` / `note human on`（仿人节奏）明确定位为针对快速连续输入或动画防抖敏感应用的**操作稳定性手段**，不提供按风控策略自动开启能力。

**独立强确认页（`StrongConfirm` / `StrongConfirmActivity`）**：

- 2.0 移除了旧版通知栏直接点“允许”的快捷按钮，所有确认均在独立强确认页内完成。该页面启用 `FLAG_SECURE`、防遮挡触摸过滤（`filterTouchesWhenObscured`）与防无障碍代点保护；涉及第三方应用按钮文字时明确标注“应用画面显示文字”以区分系统文案。
- **必须生物识别/PIN 强确认**：放宽权限、放宽金融应用默认规则、支付、开启公网接入、公网新客户端配对（须由用户在手机上输入 8 位配对码后才打开确认页）。
- **页内轻确认**：除支付与放宽权限外的普通高风险/高频提示可在确认页内单点确认，并可选择仅本次、本轮任务内或今天内有效。
- 命令遇到待确认动作时最多等待 60 秒：允许则继续执行；拒绝返回 `USER_DENIED`；超时返回 `CONFIRM_PENDING` 或 `NEEDS_CONFIRM`（附 `next=user_on_phone`），请求保留有效期内确认后原样重发即可通过。1.1 的 `--confirmed` 参数无效。
- 用户在 agent 付款时全程盯着，可在 agent 详情页临时开启 **免确认支付 30 分钟**（需先指纹确认，放行照常记录操作日志）。

### 7.3.1 令牌范围按这一次调用算

同一条命令，做的事不同，需要的范围也不同：

- `act` 不带步骤、或只有 `wait`：`look`。只看令牌没有自己的副屏，它看的是当前正在用的那块副屏，不管属于哪个会话。
- `act` 带动作步骤、`--keep`、`--save`：`operate`。
- `stop --force`、`handoff in`：`system`。`stop` 和 `handoff out`：`operate`。
- 公网 OAuth 凭证的实际生效范围始终取“配对时批准的范围”与“手机上当前权限档位”两者中较小的一方。

### 7.4 操作日志

每条命令记一行：时间、令牌名、来源设备（公网调用另记 `source="public"` 与真实来源地址 `src_addr`）、会话、命令、结果代码、耗时、请求号。`type` 输入的文字只记字数。确认的每次答复、令牌的创建和撤销、配对入队/认领/批准、验证失败和封禁也记在里面。在 App 底部“记录 › 操作日志”里看、复制、清空。文件最大 1 MB，满了保留上一份。

### 7.5 其它与特权边界

- **屏幕文字防提示词注入边界（`ScreenText`）**：所有来自第三方应用的屏幕元素、系统 Toast、输入框回读、应用名与 AppFunctions 描述均统一剥离换行（`\r`/`\n`）、控制字符与 Unicode Bidi 覆盖符，并中性化伪造段落标记（`【...】` / `</screen-data>`）；文本回复中的屏幕内容以成对引号与 `<screen-data>…</screen-data>` 数据围栏隔离，结构化 JSON 提供带 `"untrusted": true` 的顶层 `screen_data` 对象，且 `notes[]` 分离受信任 `system_text` 与 `screen_excerpts`。
- **特权服务边界（协议 19）**：运行在 Shizuku/Root 进程中的 `VScreenDisplayService` 对全部 Binder 事务码强制校验调用方 UID（仅允许本应用 UID，`DESTROY_CODE` 另允许 `0`/`2000`）；`EXEC` 事务全部经纯 JVM 白名单（`ExecAllowlist`）与包名/进程名校验器（`PackageNames`）审查，拒绝所有 shell 解释器（`sh`/`su`/`bash`/`toybox`/`busybox`）、任意 `cwd` 与任意环境变量，运行时零 `sh -c`；`/data/local/tmp` 持久化状态文件均做符号链接防护。
- **敏感剪贴板保护**：App 复制令牌、一次性接入码、登录链接及副屏粘贴输入（`VScreenTyping.pasteText`）时均对 `ClipData` 标记 `android.content.extra.IS_SENSITIVE = true`，在 Android 13+ 系统剪贴板浮层中隐藏明文预览，且 App 内 Snackbar 不回显明文。
- 截图随副屏清理：副屏释放 2 分钟后，在那之前的截图都会删掉。
- 密码框只报字符数，内容不会出现在任何回复里。
- 中文等非 ASCII 文字在无障碍没开、输入框读不到时，仍然通过标记为敏感的剪贴板粘贴输入。粘贴后剪贴板会恢复原内容，读不到原内容时清空。
- `vscreen diag` 导出一份诊断：版本、设备、特权提供方与连接状态、最近的命令（代码和耗时）、被吞掉的异常计数、应用日志末尾。不含截图、输入的文字和令牌；通过诊断页外发 GitHub Issue 前还会经 `Feedback.redact` 二次脱敏内网/Tailnet IP、域名、邮箱与类密钥字符串。

## 8. 已知限制

- **真机与模拟器验证范围**：发版前按 [docs/TASKS.md](docs/TASKS.md) 与 [docs/RELEASE-PROCESS.md](docs/RELEASE-PROCESS.md) 执行真机与模拟器矩阵验证（含 API 34/35 强门禁及 API 36 前瞻观测）；各厂商 ROM 的后台保活策略、熄屏供电与 `PLAY_AUDIO` 静音行为仍存在机型差异。
- **Android 17+ 与 Shizuku 兼容分支**：官方 Shizuku 与 Sui 已实测验证，其他 `API_V23` 兼容分支（如 AXManager、Nightzuku、Shevery）按协议动态发现；若未来 Android 17+ 上官方 Shizuku 受限，可改用兼容分支或在首次配置/设置中切换至 Root 模式。
- **守门人（`Gate`）与应用进程职责边界**：2.0.0-beta2 起公开本机端口已移入特权服务进程（`core/Gate.kt`，见 [docs/SPIKE-FRONT-DOOR.md](docs/SPIKE-FRONT-DOOR.md)），App 被冻结或强停后 `Gate` 会在 3 秒内自动拉起并转发；但 OCR、无障碍树、悬浮窗和强确认界面仍运行在 App 进程中。
- 内置 Tailscale/ngrok 代理（`libtsbridge.so`）与 Cloudflare Tunnel（`libcloudflared.so`）打包在 APK `jniLibs` 中。按需下载做不到：Android 10 起不允许执行下载到应用数据目录里的程序。
- 第一次安装命令行的 `curl … | sh` 没有校验（见 2.1）。
- 开启高精度 OCR（PP-OCRv6，fp16 权重合计约 11.5 MB）时，冷启动后第一次识别文字要加载模型，第一条命令会慢几秒。
- 如果系统拦截了自动开启无障碍服务，请到 **系统设置 › 无障碍** 手动打开一次。
- 使用 Shizuku 或兼容分支时，手机重启后要重新启动对应服务。所选权限来源不可用时，需要副屏的命令可能返回 `NOT_CONNECTED`，回执会按当前提供方说明原因。

## 9. 从 1.1 升级

| 1.1 | 1.2.0 |
|---|---|
| `POST /v1/run`，停在某一步也返回 `ok: true` | `POST /v2/run`，停在某一步是 `ok: false`、`exit: 1`，带 `code` 和 `next`。`/v1` 行为不变，再保留一个版本，回复带 `Deprecation: true` |
| 从文字里找【错误】判断结果 | 看 `ok` / `code` / `next`，结构化内容在 `steps`、`screen`、`notes` |
| `?token=` | 不再接受。用请求头，或签名 |
| 一个令牌 | 主令牌照旧；可以给每个 agent 新建带权限范围的令牌 |
| `--confirmed` | 不起作用。用户在手机上确认 |
| `--paused` | 改名 `--bare`（旧名仍可用） |
| `note pause` / `note resume` / `note realtime` | `note after` / `note before` / `act --fast`（旧的记录自动对应） |
| 事件取走即删，广播给所有应用 | 保留到下一次 `events`，`--again` 重取；广播只发给用户指定的应用 |
| 别的命令在执行时返回 `BUSY` | 排队，最长 60 秒 |
| `key home` | 没有了 |
| MCP 一个工具 `vscreen` | 另有按命令分开的工具和 `structuredContent` |

已安装的 `vscreen` 命令行会在第一次调用时自己换成新版。agent 存过的旧说明（skill 或记忆）建议换成新版：重新发一次“给 agent 的话”，或让它执行 `vscreen guide`。

## 命令行的完整输出（1.1.5）

每次命令的完整输出都会另存为截图目录（`$VSCREEN_SHOT_DIR`，默认 `$TMPDIR/vscreen-shots`）下的 `last.txt`。输出很长时，可以用 `head`、`sed -n` 分段读。

有些沙箱（如 proot）在把很长的输出用 `>` 重定向到文件时，会把进程杀掉（signal 9）。这是沙箱的限制，改读 `last.txt` 就行。
