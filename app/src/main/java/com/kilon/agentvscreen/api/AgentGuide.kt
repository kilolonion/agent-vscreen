package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr

/**
 * Everything the app hands to an agent as text. 1.2: rebuilt as one scaffold in four layers, each short enough for
 * where it lives, each pointing to the next:
 *
 *   1. [memory]   what the agent keeps across conversations: "I can do this, here is when, start with `vscreen guide`".
 *   2. [guide]    one page, read at the start of a task: the loop (look → act → check), how to write a stretch, what a
 *                 reply means, what to do for each kind of result, when to stop and ask the user, a few recipes.
 *   3. `help <command>` / `help all`: the reference, generated from the command table.
 *   4. `help codes` and the `next` field of every failed reply: what to do about this exact result.
 *
 * Nothing here is about a particular app. The phone reports facts; the agent decides.
 * Each text exists in Chinese and English (I18n) and takes the address and token the agent will use.
 */
object AgentGuide {
    private fun local(base: String) = base == ApiConfig.baseUrl

    /** The loop in three lines (tool descriptions, where space is short). */
    fun loop(): String = tr(
        "用法是一个循环：① 看：launch 或不带步骤的 act，读【现在】和截图。② 做：act 一小段步骤，最后一步写 until <你期待看到的>。③ 核对：ok 为真就继续；为假时这段停在了某一步，按 code / next 处理（help codes），先看图再决定。",
        "Use it as a loop: (1) look: launch, or act with no steps; read [Now] and the screenshot. (2) act: a short stretch of steps, the last one with until <what you expect to see>. (3) check: ok true, go on; false, the stretch stopped at a step: follow code / next (help codes) and look at the picture before deciding.")

    /**
     * The note an agent keeps in its long-term memory, written as the agent's OWN ability: what it can do, when to
     * reach for it, how to start, what to do if it fails. Short (memories get truncated) and without the token.
     */
    fun memory(base: String = ApiConfig.baseUrl): String = tr(
        "【我的能力：在用户手机上后台操作应用】我可以用终端命令 `vscreen` 打开、查看和操作用户手机上的任何应用：点、滑、输入文字、按键、读屏和截图、等画面变化、盯着某个条件提醒用户。" +
            "它在一块看不见的副屏上进行，不打扰用户正在用的主屏，也不需要 adb、无线调试或 root（工具叫 Agent-VScreen，已装好" +
            (if (local(base)) "" else "，通过 Tailscale 连 $base") + "）。" +
            "什么时候用：用户让我在手机上做任何事（打开某个 App、查信息、比价、签到、看某页内容、测试应用……），或我需要看手机上某个应用的画面时，先用 vscreen，不要说做不到，也不要让用户自己去点。" +
            "怎么开始：每次任务开头先 `vscreen guide`（一页，讲清楚“看→做→核对”的循环和出错时怎么办），再 `vscreen launch <应用名>`。做完 `vscreen done`。" +
            "连不上时：如果找不到 vscreen 命令或令牌不对，请用户在 Agent-VScreen App 的“接入 agent”里重新复制“给 agent 的话”发给我。",
        "[My ability: operate apps on the user's phone in the background] I can use the shell command `vscreen` to open, view and operate any app on the user's phone: tap, swipe, type, press keys, read the screen and take screenshots, wait for the screen to change, and watch for a condition to alert the user. " +
            "It works on an invisible second screen, so it never disturbs the screen the user is using, and it needs no adb, wireless debugging or root (the tool is Agent-VScreen, already installed" +
            (if (local(base)) "" else "; reached over Tailscale at $base") + "). " +
            "When to use it: whenever the user asks me to do anything on the phone (open an app, look something up, compare prices, check in, read a page, test an app...), or I need to see an app's screen, I use vscreen first instead of saying I can't or asking the user to tap. " +
            "How to start: at the start of each task run `vscreen guide` (one page: the look → act → check loop and what to do when something fails), then `vscreen launch <app name>`. `vscreen done` when finished. " +
            "If it fails: if the vscreen command is missing or the token is rejected, I ask the user to copy the \"message for your agent\" again from the Agent-VScreen app (Connect an agent) and send it to me."
    )

    /** How a caller without the CLI proves its token: Bearer on this phone, a signature from another device. */
    private fun httpAuth(base: String): String = if (local(base)) tr("请求头 Authorization: Bearer <令牌>", "header Authorization: Bearer <token>")
        else tr("从其它设备来的请求要签名（令牌不上网络）：请求头 X-VScreen-Key: <令牌里 avs_ 后的 id，旧式令牌写 main>、X-VScreen-Time: <unix 秒>、X-VScreen-Nonce: <12 位以上随机字母数字>、" +
            "X-VScreen-Sign: sha256hex(K\\n时间\\nNonce\\nPOST\\n/v2/run\\nsha256hex(请求体)\\nK)，其中 K = sha256hex(令牌的密钥部分)",
            "requests from another device are signed (the token does not travel): headers X-VScreen-Key: <the id after avs_ in the token; main for an old-style token>, X-VScreen-Time: <unix seconds>, X-VScreen-Nonce: <12+ random letters/digits>, " +
            "X-VScreen-Sign: sha256hex(K\\ntime\\nnonce\\nPOST\\n/v2/run\\nsha256hex(body)\\nK), where K = sha256hex(the secret part of the token)")

    /** The same note for agents that call the HTTP API. */
    fun memoryHttp(base: String = ApiConfig.baseUrl): String = tr(
        "【我的能力：在用户手机上后台操作应用】我可以调用 Agent-VScreen 的 HTTP 接口打开、查看和操作用户手机上的任何应用（点、滑、输入、读屏截图、等画面变化）：" +
            "POST $base/v2/run，${httpAuth(base)}，内容 {\"cmd\": \"launch 设置\"}。回复是 JSON：ok / code / next 说明结果，steps、screen.elements、notes 是结构化内容，text 是同样内容的文字。它在看不见的副屏上进行，不打扰用户主屏，不需要 adb。" +
            "什么时候用：用户让我在手机上做任何事，或我需要看手机上某个应用的画面时，先用它，不要说做不到。每次任务开头先发 {\"cmd\": \"guide\"} 看用法。" +
            "令牌是接入时用户给我的；没有或被拒，就请用户在 Agent-VScreen App 的“接入 agent”里重新复制“给 agent 的话”。",
        "[My ability: operate apps on the user's phone in the background] I can call Agent-VScreen's HTTP API to open, view and operate any app on the user's phone (tap, swipe, type, read the screen and screenshots, wait for changes): " +
            "POST $base/v2/run, ${httpAuth(base)}, body {\"cmd\": \"launch Settings\"}. The reply is JSON: ok / code / next tell the result; steps, screen.elements and notes are structured; text is the same content in words. It works on an invisible second screen, never disturbs the user's screen, and needs no adb. " +
            "When to use it: whenever the user asks me to do anything on the phone, or I need to see an app's screen, I use it first instead of saying I can't. At the start of each task send {\"cmd\": \"guide\"} for the usage. " +
            "The token is the one the user gave me when connecting; if I don't have it or it is rejected, I ask the user to copy the \"message for your agent\" again from the Agent-VScreen app (Connect an agent)."
    )

    /** The note for agents that use the MCP tools. */
    fun memoryMcp(): String = tr(
        "【我的能力：在用户手机上后台操作应用】我有一组 MCP 工具（Agent-VScreen：vscreen_launch、vscreen_act、vscreen_status …，以及接受一整条命令行的 vscreen），可以打开、查看和操作用户手机上的任何应用：点、滑、输入、读屏截图、等画面变化、盯着某个条件。" +
            "它在看不见的副屏上进行，不打扰用户主屏，不需要 adb。什么时候用：用户让我在手机上做任何事，或我需要看手机上某个应用的画面时，先用它，不要说做不到。" +
            "怎么开始：每次任务开头调用 vscreen，命令填 guide 看用法；然后 vscreen_launch 打开应用，vscreen_act 做一小段并核对结果（structuredContent 里的 ok / code / next），做完调用 vscreen_done。",
        "[My ability: operate apps on the user's phone in the background] I have a set of MCP tools (Agent-VScreen: vscreen_launch, vscreen_act, vscreen_status …, and vscreen, which takes a whole command line) that open, view and operate any app on the user's phone: tap, swipe, type, read the screen and screenshots, wait for changes, watch for a condition. " +
            "It works on an invisible second screen, never disturbs the user's screen, and needs no adb. When to use it: whenever the user asks me to do anything on the phone, or I need to see an app's screen, I use it first instead of saying I can't. " +
            "How to start: at the start of each task call vscreen with the command guide for the usage; then vscreen_launch opens an app, vscreen_act does a short stretch and I check the result (ok / code / next in structuredContent); vscreen_done when finished."
    )

    /**
     * What the user sends to the agent (copied by the app). The user asks, so the agent does it: install, keep the note
     * in its own memory, then report where with `vscreen remembered` (the app shows it).
     */
    fun onboard(base: String = ApiConfig.baseUrl, token: String = ApiConfig.token): String = tr("""
请帮我接入 Agent-VScreen，让你以后能在我的手机上后台打开和操作应用（不打扰我正在用的屏幕）。按顺序做这三步：

1. 在你的终端里执行这一行（安装 vscreen 命令，地址和令牌会存好；这个令牌只给你用，不要写进别处）：
${ApiConfig.installLine(base, token)}

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则，用你自己保存长期信息的方式）。这是我的要求：以后的新对话里，你也要知道自己有这个能力，我让你在手机上做事时直接用它。
「${memory(base)}」

3. 存好以后执行：vscreen remembered "<你存在了哪里>"
然后执行 vscreen guide 读一遍用法，告诉我你把说明存在了哪里。
""", """
Please connect Agent-VScreen so that from now on you can open and operate apps on my phone in the background (without disturbing the screen I'm using). Do these three steps in order:

1. Run this line in your terminal (it installs the vscreen command and saves the address and token; the token is for you alone, don't write it anywhere else):
${ApiConfig.installLine(base, token)}

2. Save the text below in your long-term memory (or as a skill / rule, whatever you use to keep things across conversations). This is my request: in future conversations you should know you have this ability and use it directly when I ask you to do something on my phone.
"${memory(base)}"

3. When it is saved, run: vscreen remembered "<where you saved it>"
Then run vscreen guide to read the usage once, and tell me where you saved the note.
""").trim()

    /** Onboarding for an agent that only does HTTP (no shell). */
    fun onboardHttp(base: String = ApiConfig.baseUrl, token: String = ApiConfig.token): String = tr("""
请帮我接入 Agent-VScreen，让你以后能在我的手机上后台打开和操作应用（不打扰我正在用的屏幕）。

1. 它的接口：POST $base/v2/run，内容 {"cmd": "status"}。令牌：$token（只给你用）。${httpAuth(base)}。先发一次 status 确认能连上，再发 {"cmd": "guide"} 读用法。把地址和令牌保存好。
   回复是 JSON：ok（是否按预期做完）、code、next（出问题时该怎么办：look 先看画面 / fix_command 改命令 / ask_user 问我 / retry_later 稍后重试）、text、images、steps、screen、notes。可带 "request_id"：同一个 id 重发不会重复执行。

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则）。这是我的要求：以后的新对话里，你也要知道自己有这个能力，我让你在手机上做事时直接用它。
「${memoryHttp(base)}」

3. 存好以后发 {"cmd": "remembered <你存在了哪里>"}，然后告诉我你把它存在了哪里。
""", """
Please connect Agent-VScreen so that from now on you can open and operate apps on my phone in the background (without disturbing the screen I'm using).

1. Its API: POST $base/v2/run with body {"cmd": "status"}. Token: $token (for you alone). ${httpAuth(base).replaceFirstChar { it.uppercase() }}. Send status once to check the connection, then {"cmd": "guide"} to read the usage. Keep the address and token.
   The reply is JSON: ok (did it go as expected), code, next (what to do when it did not: look at the screen / fix_command / ask_user / retry_later), text, images, steps, screen, notes. You may send "request_id": resending the same id never runs the command twice.

2. Save the text below in your long-term memory (or as a skill / rule). This is my request: in future conversations you should know you have this ability and use it directly when I ask you to do something on my phone.
"${memoryHttp(base)}"

3. When it is saved, send {"cmd": "remembered <where you saved it>"}, then tell me where you saved it.
""").trim()

    /** Onboarding for an MCP client: the config plus the memory request. */
    fun onboardMcp(base: String = ApiConfig.baseUrl, token: String = ApiConfig.token): String = tr("""
请帮我接入 Agent-VScreen，让你以后能在我的手机上后台打开和操作应用（不打扰我正在用的屏幕）。

1. 把这段加进你的 MCP 配置（streamable HTTP）：
${ApiConfig.mcpJson(base, token)}
""" + (if (local(base)) "" else "（MCP 客户端不会给请求签名：从其它设备用 MCP 接入时，我需要在 Agent-VScreen › 其它设备 里关掉“只接受签名请求”。连接被拒绝时提醒我。）\n") + """
2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则）。这是我的要求：以后的新对话里，你也要知道自己有这个能力，我让你在手机上做事时直接用它。
「${memoryMcp()}」

3. 存好以后调用 vscreen，命令填 remembered <你存在了哪里>，然后告诉我你把它存在了哪里。
""", """
Please connect Agent-VScreen so that from now on you can open and operate apps on my phone in the background (without disturbing the screen I'm using).

1. Add this to your MCP config (streamable HTTP):
${ApiConfig.mcpJson(base, token)}
""" + (if (local(base)) "" else "(MCP clients do not sign requests: to use MCP from another device I have to turn off \"signed requests only\" under Other devices in Agent-VScreen. Remind me if the connection is refused.)\n") + """
2. Save the text below in your long-term memory (or as a skill / rule). This is my request: in future conversations you should know you have this ability and use it directly when I ask you to do something on my phone.
"${memoryMcp()}"

3. When it is saved, call vscreen with the command remembered <where you saved it>, then tell me where you saved it.
""").trim()

    /** `vscreen guide`: the one page, without the install line (whoever runs it is installed). */
    fun guide(): String = prompt().lines().filterNot { it.contains("/cli | ") }.joinToString("\n") + "\n"

    /**
     * The same guide as a generic skill file (SKILL.md with name/description frontmatter). Served at /skill without a
     * token, so it carries no token: the install line is left out (vscreen is installed by then).
     */
    fun skill(): String = tr(
        "---\nname: agent-vscreen\ndescription: 在手机上后台打开、查看、操作任何应用（点、滑、输入、截图、盯守）时使用。用 vscreen 命令在 Agent-VScreen 的看不见的副屏上操作，不占用户主屏。不要自己建虚拟屏，也不要为此去连 adb、配无线调试或用 am/input/screencap。\n---\n\n",
        "---\nname: agent-vscreen\ndescription: Use to open, view or operate any app on the phone in the background (tap, swipe, type, screenshot, watch). The vscreen command works on Agent-VScreen's invisible second screen without taking the user's screen. Do not build your own virtual display, and do not reach for adb, wireless debugging or am/input/screencap for this.\n---\n\n"
    ) + prompt(skill = true).lines().filterNot { it.contains("/cli | ") }.joinToString("\n") + "\n"

    /**
     * The one page. Its order is the order an agent needs things in: what this is, the loop, how to write a stretch,
     * how to read a reply, what to do per kind of result, when to stop for the user, recipes, where the rest is.
     */
    fun prompt(skill: Boolean = false): String = tr("""
## 手机后台副屏（Agent-VScreen）
你可以用 `vscreen` 命令在一块看不见的副屏上操作手机里的应用，用户照常用主屏。用户要你打开 / 查看 / 操作某个应用时用它。
""" + (if (skill) "不要自己建虚拟屏，也不要用 adb、am start --display、input、screencap 代替：副屏、点击、截图都由 vscreen 完成。\n" else "") + """没有 vscreen 命令时先执行：${ApiConfig.installLine()}
不需要 adb 或无线调试，不要去连。没有副屏时直接 launch，会自动建。

### 一、循环：看 → 做 → 核对
1. 看：`vscreen launch <应用名>`（打开并看），或 `vscreen act`（不带步骤 = 看一眼现在的画面）。读【现在】的编号元素，打开截图自己看。画面是什么意思由你判断，手机只报事实。
2. 做：`vscreen act "<步骤>; <步骤>"`。一段只放你有把握连着做的几步，最后一步加 `until <你期待看到的>`。不确定点完会变成什么样时就在那里断开，先看再做。
3. 核对：退出码 0（ok）= 整段按预期做完，看【现在】接着做。非 0 = 这段停在了某一步，后面的没做：读【注意】里的代码，按第四节处理。不要假设没做的步骤已经做了。

### 二、怎么写一段
- 目标三种写法：`tap 登录`（画面上的字）、`tap #3`（【现在】列表的编号）、`tap 540 1200`（坐标，截图边上有刻度）。字会变或有好几处一样的字时用编号或坐标。
- 步骤：tap | press（长按）| swipe x1 y1 x2 y2 | drag | scroll down/up | type 文字（先 tap 输入框）| key back/enter | wait 条件 | wait 2s | use 段名。
- 条件：text:字（出现）| gone:字（消失）| change（画面变了）| stable（稳定）| app:包名或界面名。
- 例：`vscreen act "tap 搜索; type 耳机; key enter until text:综合"`。
- 选项：`--fast` 动作之间不等画面（游戏、连点）；`--stop 条件` 整段盯着，出现就停；`--watch 3s` 做完再看一会儿；`--async` 长操作先返回任务号，之后 `vscreen task <号> --wait 60s` 取结果。

### 三、回复怎么读
四块：【做了什么】每步的事实；【这段时间】你上次看之后画面上出现 / 消失了什么（包括你思考的时间）；【现在】前台界面、截图路径、编号元素；【注意】以代码开头的提示。
HTTP / MCP 的回复里同样的内容是结构化的：ok、code、next、steps[]、screen.elements[]、notes[]。

### 四、没成功时怎么办（看代码的“下一步”，完整的表：`vscreen help codes`）
- 先看画面再决定（look）：STEP_MISSED 没等到期待的结果；NOT_FOUND 找不到字（回复里列了相近的字和坐标）；STALE_ELEMENT 编号对应的元素已经不在了（用新列表）；TYPE_FAILED / FOCUS_ELSEWHERE 字没进输入框（先点输入框）；REREAD_REQUIRED 用户刚接管过；NO_CHANGE 点了但画面没变（可能没点中）。同一种做法失败两次就换一种（换坐标、先滚动、返回上一页），不要原样重试。
- 改命令（fix_command）：AMBIGUOUS 有好几处一样的字（回复里列了每一处的坐标，改用坐标）；INVALID_STEP / INVALID_ARGS / UNKNOWN_OPTION 是你写错了，什么都没执行，按提示改。
- 停下来告诉用户（ask_user）：USER_STOPPED、USER_TAKEOVER、APP_BLOCKED、APP_READ_ONLY、USER_DENIED、CONFIRM_PENDING、SCOPE_DENIED、NOT_CONNECTED。把回复里的说明转告用户，等用户处理。不要重试，不要换个入口绕过。
- 稍后重试（retry_later）：BUSY、QUEUE_TIMEOUT、SERVER_BUSY、RATE_LIMITED。

### 五、这些事由用户决定
- 付费、下单、发送、删除等不可撤销的按钮，以及用户设为“每次询问”的应用：你照常发步骤，手机上会弹出确认，用户点了“允许”才会执行。你加任何参数都不能代替用户。等不到（CONFIRM_PENDING）就告诉用户去手机上点，然后重发。
- 涉及隐私的内容（私信、通讯录、相册、余额），先问用户再看。
- 需要用户本人做的一步（密码、验证码、人脸）：`vscreen handoff out` 把应用交到用户主屏，告诉用户做什么。

### 六、常用做法
- 在应用里搜索：launch → tap 搜索框 → type → key enter until text:<结果页会有的字>。
- 读很长的列表：`vscreen collect --pages 10`（自动翻页，返回去重后的文字）。
- 看不清的小字 / 图标：`vscreen zoom x1,y1,x2,y2`；画面上没有字可点（游戏、画布）：`vscreen spots` 找可点的色块。
- 等一件以后才发生的事：`vscreen act --stop "text:到货" --keep notify`（看到就通知用户），或 `--keep wake` 之后 `vscreen events --wait 60s` 取。告诉用户你在盯什么、盯多久。
- 每段前后都要做的固定动作（游戏的继续 / 暂停、反复弹出的窗口）：`vscreen note before "<步骤>"`、`vscreen note after "<步骤>"` 记一次，之后每段 act 自动做。
- 这个应用的经验（哪个入口、哪里容易点错）：`vscreen note <一句话>`，下次 launch 会列给你。重复用的一段：act 加 `--save 名字`，之后 `use 名字`。
- 让用户知道你在做什么：`vscreen title "在搜耳机"`。做完：`vscreen done`（副屏让出，页面保留）。
- 出了怪问题：`vscreen status` 看状态，`vscreen fix` 按提示修，`vscreen diag` 导出诊断给开发者。

完整说明：`vscreen help all`；单条命令：`vscreen help act`。
""", """
## Phone background screen (Agent-VScreen)
You can use the `vscreen` command to operate apps on the phone on an invisible second screen while the user keeps using their own. Use it whenever the user asks you to open / look at / operate an app.
""" + (if (skill) "Do not build your own virtual display, and do not use adb, am start --display, input or screencap instead: the screen, taps and screenshots all go through vscreen.\n" else "") + """If there is no vscreen command, first run: ${ApiConfig.installLine()}
No adb or wireless debugging is needed; don't set them up. With no screen, just launch: one is created.

### 1. The loop: look → act → check
1. Look: `vscreen launch <app name>` (opens and looks), or `vscreen act` (no steps = look at the screen now). Read the numbered elements in [Now] and open the screenshot yourself. You judge what the screen means; the phone only reports facts.
2. Act: `vscreen act "<step>; <step>"`. Put in one stretch only the few steps you are sure can follow each other, and end with `until <what you expect to see>`. Where you can't tell what a tap leads to, break there and look first.
3. Check: exit code 0 (ok) = the whole stretch went as expected; read [Now] and go on. Non-zero = the stretch stopped at a step and the rest was not done: read the code in [Notes] and follow section 4. Never assume the skipped steps happened.

### 2. Writing a stretch
- Three ways to name a target: `tap Login` (text on the screen), `tap #3` (number in the [Now] list), `tap 540 1200` (coordinates; the screenshot has rulers). When text changes or appears in several places, use the number or coordinates.
- Steps: tap | press (long press) | swipe x1 y1 x2 y2 | drag | scroll down/up | type text (tap the input box first) | key back/enter | wait <condition> | wait 2s | use <segment>.
- Conditions: text:word (appears) | gone:word (disappears) | change (the picture changed) | stable (settled) | app:package or screen name.
- Example: `vscreen act "tap Search; type headphones; key enter until text:Results"`.
- Options: `--fast` no waiting between actions (games, rapid taps); `--stop <condition>` watched for the whole stretch, stops when seen; `--watch 3s` keep looking after the steps; `--async` for long work: returns a task id, fetch the result with `vscreen task <id> --wait 60s`.

### 3. Reading a reply
Four blocks: [Done] the facts of each step; [Meanwhile] what appeared / disappeared since your last look (your thinking time included); [Now] the front screen, the screenshot path, numbered elements; [Notes] hints that start with a code.
Over HTTP / MCP the same content is structured: ok, code, next, steps[], screen.elements[], notes[].

### 4. When it did not work (follow the code's "next"; the full table: `vscreen help codes`)
- Look at the screen before deciding (look): STEP_MISSED the expected result never came; NOT_FOUND the text is not there (the reply lists similar text with coordinates); STALE_ELEMENT the numbered element is gone (use the new list); TYPE_FAILED / FOCUS_ELSEWHERE the text did not go into an input box (tap the box first); REREAD_REQUIRED the user just had the screen; NO_CHANGE tapped but nothing changed (possibly missed). After two failures of the same approach, change it (coordinates, scroll first, go back) instead of repeating it.
- Fix the command (fix_command): AMBIGUOUS the text is in several places (the reply lists each with coordinates: use those); INVALID_STEP / INVALID_ARGS / UNKNOWN_OPTION mean you wrote it wrong; nothing ran; correct it as the message says.
- Stop and tell the user (ask_user): USER_STOPPED, USER_TAKEOVER, APP_BLOCKED, APP_READ_ONLY, USER_DENIED, CONFIRM_PENDING, SCOPE_DENIED, NOT_CONNECTED. Pass the reply's explanation on and wait. Do not retry, and do not go around it another way.
- Retry later (retry_later): BUSY, QUEUE_TIMEOUT, SERVER_BUSY, RATE_LIMITED.

### 5. What the user decides
- Buttons that pay, order, send, delete or otherwise can't be undone, and apps the user set to "ask every time": send the step as usual; the phone asks the user, and it runs only after they tap Allow. No option you add can stand in for the user. If no answer comes (CONFIRM_PENDING), tell the user to tap it on the phone, then send the step again.
- Private content (messages, contacts, photos, balances): ask the user before looking.
- A step only the user can do (password, code, face): `vscreen handoff out` puts the app on the user's screen; tell them what to do.

### 6. Recipes
- Search inside an app: launch → tap the search box → type → key enter until text:<a word the results page will have>.
- Read a long list: `vscreen collect --pages 10` (pages through it, returns the text without repeats).
- Small text / icons you can't read: `vscreen zoom x1,y1,x2,y2`; nothing with text to tap (games, canvas): `vscreen spots` finds tappable color patches.
- Wait for something that happens later: `vscreen act --stop "text:In stock" --keep notify` (the user is notified when seen), or `--keep wake` and collect with `vscreen events --wait 60s`. Tell the user what you watch and for how long.
- Fixed actions around every stretch (resume / pause a game, a popup that keeps coming back): keep them once with `vscreen note before "<steps>"` and `vscreen note after "<steps>"`; every act then runs them.
- What you learned about this app (which entry to use, where taps go wrong): `vscreen note <one sentence>`; the next launch lists it. A stretch you repeat: add `--save name` to act, then `use name`.
- Let the user see what you are doing: `vscreen title "searching headphones"`. When finished: `vscreen done` (the screen is freed, the page kept).
- Something odd: `vscreen status` shows the state, `vscreen fix` repairs what it can, `vscreen diag` exports diagnostics for the developer.

Full reference: `vscreen help all`; one command: `vscreen help act`.
""").trim()
}
