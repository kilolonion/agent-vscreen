package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.tr

/**
 * Everything the app hands to an agent as text: the onboarding message the user sends, the memory note the agent keeps,
 * the short guide and the skill file. Each text exists in Chinese and English (I18n) and takes the address the agent
 * will use, so the same message works on this phone (127.0.0.1) and from another device over Tailscale.
 */
object AgentGuide {
    private fun local(base: String) = base == ApiConfig.baseUrl

    /**
     * 1.1.7: the note an agent keeps in its long-term memory, written as the agent's OWN capability (1.1.6 described an
     * app, so agents filed it as trivia): what it can do, when to reach for it, how to start, what to do if it fails.
     * Short (memories get truncated) and without the token.
     */
    fun memory(base: String = ApiConfig.baseUrl): String = tr(
        "【我的能力：在用户手机上后台操作应用】我可以用终端命令 `vscreen` 打开、查看和操作用户手机上的任何应用：点、滑、输入文字、按键、读屏和截图、等画面变化、盯着某个条件提醒用户。" +
            "它在一块看不见的副屏上进行，不打扰用户正在用的主屏，也不需要 adb、无线调试或 root（工具叫 Agent-VScreen，已装好" +
            (if (local(base)) "" else "，通过 Tailscale 连 $base") + "）。" +
            "什么时候用：用户让我在手机上做任何事（打开某个 App、查信息、比价、签到、看某页内容、测试应用……），或我需要看手机上某个应用的画面时，先用 vscreen，不要说做不到，也不要让用户自己去点。" +
            "怎么开始：`vscreen guide` 看用法；常用 `vscreen launch <应用名>`、`vscreen act \"tap 搜索; type 耳机; key enter\"`、`vscreen act`（看一眼现在的画面）、做完 `vscreen done`。" +
            "连不上时：如果找不到 vscreen 命令或令牌不对，请用户在 Agent-VScreen App 的“接入 agent”里重新复制“给 agent 的话”发给我。",
        "[My ability: operate apps on the user's phone in the background] I can use the shell command `vscreen` to open, view and operate any app on the user's phone: tap, swipe, type, press keys, read the screen and take screenshots, wait for the screen to change, and watch for a condition to alert the user. " +
            "It works on an invisible second screen, so it never disturbs the screen the user is using, and it needs no adb, wireless debugging or root (the tool is Agent-VScreen, already installed" +
            (if (local(base)) "" else "; reached over Tailscale at $base") + "). " +
            "When to use it: whenever the user asks me to do anything on the phone (open an app, look something up, compare prices, check in, read a page, test an app...), or I need to see an app's screen, I use vscreen first instead of saying I can't or asking the user to tap. " +
            "How to start: `vscreen guide` shows the usage; common ones are `vscreen launch <app name>`, `vscreen act \"tap Search; type headphones; key enter\"`, `vscreen act` (look at the screen now), and `vscreen done` when finished. " +
            "If it fails: if the vscreen command is missing or the token is rejected, I ask the user to copy the \"message for your agent\" again from the Agent-VScreen app (Connect an agent) and send it to me."
    )

    /** The same note for agents that call the HTTP API. */
    fun memoryHttp(base: String = ApiConfig.baseUrl): String = tr(
        "【我的能力：在用户手机上后台操作应用】我可以调用 Agent-VScreen 的 HTTP 接口打开、查看和操作用户手机上的任何应用（点、滑、输入、读屏截图、等画面变化）：" +
            "POST $base/v1/run，请求头 Authorization: Bearer <令牌>，内容 {\"cmd\": \"launch 设置\"}。它在看不见的副屏上进行，不打扰用户主屏，不需要 adb。" +
            "什么时候用：用户让我在手机上做任何事，或我需要看手机上某个应用的画面时，先用它，不要说做不到。先发 {\"cmd\": \"guide\"} 看用法。" +
            "令牌是接入时用户给我的；没有或被拒，就请用户在 Agent-VScreen App 的“接入 agent”里重新复制“给 agent 的话”。",
        "[My ability: operate apps on the user's phone in the background] I can call Agent-VScreen's HTTP API to open, view and operate any app on the user's phone (tap, swipe, type, read the screen and screenshots, wait for changes): " +
            "POST $base/v1/run with header Authorization: Bearer <token> and body {\"cmd\": \"launch Settings\"}. It works on an invisible second screen, never disturbs the user's screen, and needs no adb. " +
            "When to use it: whenever the user asks me to do anything on the phone, or I need to see an app's screen, I use it first instead of saying I can't. Send {\"cmd\": \"guide\"} first for the usage. " +
            "The token is the one the user gave me when connecting; if I don't have it or it is rejected, I ask the user to copy the \"message for your agent\" again from the Agent-VScreen app (Connect an agent)."
    )

    /** The note for agents that use the MCP tool. */
    fun memoryMcp(): String = tr(
        "【我的能力：在用户手机上后台操作应用】我有一个 MCP 工具 vscreen（Agent-VScreen），可以打开、查看和操作用户手机上的任何应用：点、滑、输入、读屏截图、等画面变化、盯着某个条件。" +
            "它在看不见的副屏上进行，不打扰用户主屏，不需要 adb。什么时候用：用户让我在手机上做任何事，或我需要看手机上某个应用的画面时，先用它，不要说做不到。" +
            "怎么开始：调用 vscreen，命令填 guide 看用法；常用 launch <应用名>、act \"tap 搜索; type 耳机\"、act（看一眼）、done。",
        "[My ability: operate apps on the user's phone in the background] I have an MCP tool named vscreen (Agent-VScreen) that opens, views and operates any app on the user's phone: tap, swipe, type, read the screen and screenshots, wait for changes, watch for a condition. " +
            "It works on an invisible second screen, never disturbs the user's screen, and needs no adb. When to use it: whenever the user asks me to do anything on the phone, or I need to see an app's screen, I use it first instead of saying I can't. " +
            "How to start: call vscreen with the command guide for the usage; common ones are launch <app name>, act \"tap Search; type headphones\", act (look), done."
    )

    /**
     * What the user sends to the agent (copied by the app). The user asks, so the agent does it: install, keep the note
     * in its own memory, then report where with `vscreen remembered` (the app shows it).
     */
    fun onboard(base: String = ApiConfig.baseUrl): String = tr("""
请帮我接入 Agent-VScreen，让你以后能在我的手机上后台打开和操作应用（不打扰我正在用的屏幕）。按顺序做这三步：

1. 在你的终端里执行这一行（安装 vscreen 命令，地址和令牌会存好）：
${ApiConfig.installLine(base)}

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则，用你自己保存长期信息的方式）。这是我的要求：以后的新对话里，你也要知道自己有这个能力，我让你在手机上做事时直接用它。
「${memory(base)}」

3. 存好以后执行：vscreen remembered "<你存在了哪里>"
然后告诉我你把它存在了哪里。
""", """
Please connect Agent-VScreen so that from now on you can open and operate apps on my phone in the background (without disturbing the screen I'm using). Do these three steps in order:

1. Run this line in your terminal (it installs the vscreen command and saves the address and token):
${ApiConfig.installLine(base)}

2. Save the text below in your long-term memory (or as a skill / rule, whatever you use to keep things across conversations). This is my request: in future conversations you should know you have this ability and use it directly when I ask you to do something on my phone.
"${memory(base)}"

3. When it is saved, run: vscreen remembered "<where you saved it>"
Then tell me where you saved it.
""").trim()

    /** Onboarding for an agent that only does HTTP (no shell). */
    fun onboardHttp(base: String = ApiConfig.baseUrl): String = tr("""
请帮我接入 Agent-VScreen，让你以后能在我的手机上后台打开和操作应用（不打扰我正在用的屏幕）。

1. 它的接口：POST $base/v1/run，请求头 Authorization: Bearer ${ApiConfig.token}，内容 {"cmd": "status"}。先发一次 status 确认能连上，再发 {"cmd": "guide"} 读用法。把地址和令牌保存好。

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则）。这是我的要求：以后的新对话里，你也要知道自己有这个能力，我让你在手机上做事时直接用它。
「${memoryHttp(base)}」

3. 存好以后发 {"cmd": "remembered <你存在了哪里>"}，然后告诉我你把它存在了哪里。
""", """
Please connect Agent-VScreen so that from now on you can open and operate apps on my phone in the background (without disturbing the screen I'm using).

1. Its API: POST $base/v1/run with header Authorization: Bearer ${ApiConfig.token} and body {"cmd": "status"}. Send status once to check the connection, then {"cmd": "guide"} to read the usage. Keep the address and token.

2. Save the text below in your long-term memory (or as a skill / rule). This is my request: in future conversations you should know you have this ability and use it directly when I ask you to do something on my phone.
"${memoryHttp(base)}"

3. When it is saved, send {"cmd": "remembered <where you saved it>"}, then tell me where you saved it.
""").trim()

    /** Onboarding for an MCP client: the config plus the memory request. */
    fun onboardMcp(base: String = ApiConfig.baseUrl): String = tr("""
请帮我接入 Agent-VScreen，让你以后能在我的手机上后台打开和操作应用（不打扰我正在用的屏幕）。

1. 把这段加进你的 MCP 配置（streamable HTTP）：
${ApiConfig.mcpJson(base)}

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则）。这是我的要求：以后的新对话里，你也要知道自己有这个能力，我让你在手机上做事时直接用它。
「${memoryMcp()}」

3. 存好以后调用 vscreen，命令填 remembered <你存在了哪里>，然后告诉我你把它存在了哪里。
""", """
Please connect Agent-VScreen so that from now on you can open and operate apps on my phone in the background (without disturbing the screen I'm using).

1. Add this to your MCP config (streamable HTTP):
${ApiConfig.mcpJson(base)}

2. Save the text below in your long-term memory (or as a skill / rule). This is my request: in future conversations you should know you have this ability and use it directly when I ask you to do something on my phone.
"${memoryMcp()}"

3. When it is saved, call vscreen with the command remembered <where you saved it>, then tell me where you saved it.
""").trim()

    /** `vscreen guide`: the short usage, without the install line (whoever runs it is installed). */
    fun guide(): String = prompt().lines().filterNot { it.contains("/cli | ") }.joinToString("\n") + "\n"

    /**
     * The same guide as a generic skill file (SKILL.md with name/description frontmatter). Served at /skill without a
     * token, so it carries no token: the install line is left out (vscreen is installed by then).
     */
    fun skill(): String = tr(
        "---\nname: agent-vscreen\ndescription: 在手机上后台打开、查看、操作任何应用（点、滑、输入、截图、盯守）时使用。用 vscreen 命令在 Agent-VScreen 的看不见的副屏上操作，不占用户主屏。不要自己建虚拟屏，也不要为此去连 adb、配无线调试或用 am/input/screencap。\n---\n\n",
        "---\nname: agent-vscreen\ndescription: Use to open, view or operate any app on the phone in the background (tap, swipe, type, screenshot, watch). The vscreen command works on Agent-VScreen's invisible second screen without taking the user's screen. Do not build your own virtual display, and do not reach for adb, wireless debugging or am/input/screencap for this.\n---\n\n"
    ) + prompt(skill = true).lines().filterNot { it.contains("/cli | ") }.joinToString("\n") + "\n"

    fun prompt(skill: Boolean = false): String = tr("""
## 手机后台副屏（Agent-VScreen）
你可以用 `vscreen` 命令在看不见的副屏上操作手机里的应用，用户照常用主屏。用户要你打开/查看/操作某个应用时用它，不要去动主屏。
""" + (if (skill) "- 不要自己建虚拟屏，也不要用 adb、am start --display、input、screencap 代替 vscreen：副屏、点击、截图都由 vscreen 完成。\n" else "") + """
- 没有 vscreen 命令时先执行：${ApiConfig.installLine()}
- 不需要 adb 或无线调试（它走自己的 Shizuku 通道）：不要去连 adb、配无线调试或改 adb 密钥。status 里 display_id 为空只是还没建屏，launch 会自动建。
- `vscreen launch <应用名>` 打开；`vscreen act "<步骤>; <步骤>"` 做一段：tap x y | tap #N | tap 字 | press | swipe | drag | type 字 | key back | scroll down | wait <条件> | use 段名；每步可加 until <条件>（text:字 gone:字 change still color:x,y=#色 app:名）。
- 每次返回四块：做了什么 / 这段时间发生了什么 / 现在（编号元素 + 截图路径）/ 注意。截图要你自己看，画面什么意思由你判断，手机只报事实。
- 例：`vscreen act "tap 搜索; type 耳机; key enter until text:综合"`；`vscreen act` = 看一眼。
- 实时游戏：`vscreen note pause "<暂停步骤>"` 和 `note resume "<继续步骤>"` 记下后，每段 act 自动 继续→动作→观察→暂停。
- 之后继续盯：`vscreen act --stop "text:到货" --keep notify`（通知用户）或 `--keep wake`（之后用 `vscreen events --wait 60s` 取）。
- 一步没达到预期（until 没等到、字没输进去）这段就停在那步，后面不做，注意块写现在的画面：看图再决定。type 前先点输入框，等它标“有焦点”。
- 注意块里出现 CONFIRM_REQUIRED（付费/不可撤销：先问用户，同意后加 --confirmed）、APP_BLOCKED / APP_READ_ONLY、USER_TAKEOVER、USER_STOPPED（用户点了停止，返回里写了用户怎么解除，转告用户）时，停下告诉用户，不要重试。
- `vscreen title "在搜耳机"` 让用户在悬浮窗上看到你在做什么；做完 `vscreen done`，副屏让出、页面保留。
- 全部用法：`vscreen help all`。
""", """
## Phone background screen (Agent-VScreen)
You can use the `vscreen` command to operate apps on the phone on an invisible second screen while the user keeps using their own screen. Use it whenever the user asks you to open / look at / operate an app; never touch the user's screen.
""" + (if (skill) "- Do not build your own virtual display, and do not use adb, am start --display, input or screencap instead of vscreen: the screen, taps and screenshots all go through vscreen.\n" else "") + """
- If there is no vscreen command, first run: ${ApiConfig.installLine()}
- No adb or wireless debugging needed (it has its own Shizuku channel): do not connect adb, set up wireless debugging or change adb keys. An empty display_id in status only means no screen yet; launch creates one.
- `vscreen launch <app name>` opens an app; `vscreen act "<step>; <step>"` runs a segment: tap x y | tap #N | tap text | press | swipe | drag | type text | key back | scroll down | wait <condition> | use <saved segment>; any step can add until <condition> (text:… gone:… change still color:x,y=#rgb app:name).
- Every reply has four blocks: what was done / what happened meanwhile / now (numbered elements + screenshot path) / notes. Look at the screenshot yourself: you judge what the screen means, the phone only reports facts.
- Example: `vscreen act "tap Search; type headphones; key enter until text:Results"`; `vscreen act` alone = look.
- Real-time games: after `vscreen note pause "<pause steps>"` and `note resume "<resume steps>"`, every act runs resume → actions → look → pause.
- Keep watching afterwards: `vscreen act --stop "text:In stock" --keep notify` (notifies the user) or `--keep wake` (collect later with `vscreen events --wait 60s`).
- When a step misses its expectation (until not met, text not typed), the segment stops at that step and skips the rest; the notes block shows the screen now: look before deciding. Tap the input box before type and wait until it is marked focused.
- When the notes show CONFIRM_REQUIRED (payment / irreversible: ask the user, then add --confirmed), APP_BLOCKED / APP_READ_ONLY, USER_TAKEOVER or USER_STOPPED (the user pressed stop; the reply says how the user can lift it, pass that on), stop and tell the user; do not retry.
- `vscreen title "searching headphones"` shows the user what you are doing on the floating window; `vscreen done` when finished releases the screen and keeps the page.
- Full usage: `vscreen help all`.
""").trim()
}
