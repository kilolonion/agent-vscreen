package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.tr
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.2: the one table every vscreen command is declared in. The option check, the argument parser, the help text, the
 * MCP tool schemas and the command reference in API.md are all generated from it, so they cannot drift apart
 * (before 1.2 the option list was scraped from the Chinese help text with a regex).
 *
 * A command says: its positional syntax, its options (switch / value / duration), what it needs from the caller's
 * token ([Scope]), whether it works on the screen (then it runs in the screen queue, one at a time) and whether it may
 * run as a background task (`--async`).
 */
internal object CommandSpec {
    enum class Kind { SWITCH, VALUE, DURATION }

    /** What a token must be allowed to do. LOOK ⊂ OPERATE ⊂ SYSTEM. */
    enum class Scope(val key: String) { LOOK("look"), OPERATE("operate"), SYSTEM("system");
        companion object { fun of(s: String?): Scope? = values().firstOrNull { it.key == s } } }

    class Opt(val name: String, val kind: Kind, private val argZh: String?, private val argEn: String?, private val zh: String, private val en: String,
              /** Still accepted so old callers do not break, but not shown in help. */
              val deprecated: Boolean = false) {
        val arg: String? get() = if (kind == Kind.SWITCH) null else tr(argZh ?: "值", argEn ?: "value")
        val text: String get() = tr(zh, en)
        fun usage(): String = "--$name" + (arg?.let { " <$it>" } ?: "")
    }

    class Cmd(val name: String, private val posZh: String, private val posEn: String,
              private val zh: List<String>, private val en: List<String>,
              val opts: List<Opt> = emptyList(), val scope: Scope = Scope.OPERATE,
              /** Works on the background screen: runs in the screen queue, one command at a time. */
              val screen: Boolean = true,
              /** May take long: `--async` returns a task id at once. */
              val long: Boolean = false,
              /** One line for the MCP tool list. */
              private val sumZh: String = "", private val sumEn: String = "") {
        val positional: String get() = tr(posZh, posEn)
        val body: List<String> get() = if (I18n.en) en else zh
        val summary: String get() = tr(sumZh, sumEn).ifEmpty { body.firstOrNull().orEmpty() }
        /** Every option the command takes (its own, `--async` when it may run as a task). */
        val allOpts: List<Opt> get() = if (long) opts + ASYNC else opts
        fun usageLine(): String = (name + " " + positional).trim() + allOpts.filter { !it.deprecated }.joinToString("") { " [${it.usage()}]" }
        fun help(): List<String> = buildList {
            add("  " + usageLine())
            body.forEach { add("        $it") }
            allOpts.filter { !it.deprecated }.forEach { add("        ${it.usage()}${tr("：", ": ")}${it.text}") }
        }
    }

    private val ASYNC = Opt("async", Kind.SWITCH, null, null,
        "不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）", "do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)")

    val commands: List<Cmd> = listOf(
        Cmd("launch", "<应用名|包名>", "<app name|package>",
            listOf("打开应用（找不到就 apps 关键词）。副屏方向按应用声明的方向建；被系统缩放会自动处理。",
                "返回四段；【做了什么】里还会列出你以前给这个应用记的 note、存的段、前置/收尾步骤。"),
            listOf("open an app (if not found, apps <keyword>). The screen orientation follows what the app declares; system scaling is handled automatically.",
                "Returns four blocks; [Done] also lists the notes, saved segments and before/after steps you kept for this app."),
            listOf(Opt("landscape", Kind.SWITCH, null, null, "副屏按横屏建", "build the screen in landscape"),
                Opt("portrait", Kind.SWITCH, null, null, "副屏按竖屏建", "build the screen in portrait"),
                Opt("restart", Kind.SWITCH, null, null, "冷启动（先结束应用）", "cold start (stops the app first)")),
            long = true, sumZh = "打开应用并返回现在的画面", sumEn = "open an app and return the screen now"),
        Cmd("act", "[\"步骤; 步骤; …\"]", "[\"step; step; …\"]",
            listOf("做一段操作（最多 40 步，用 ; 或换行分开），返回四段。不带步骤 = 看一眼（等画面稳定，最多 3 秒）。",
                "步骤：",
                "  tap 目标 | press 目标 [时长，如 1.2s；纯数字是毫秒] | swipe x1 y1 x2 y2 [时长] | drag x1 y1 x2 y2 [x3 y3 …] [时长]",
                "  scroll up|down|left|right [#N | x y] | type 文字 | type+ 文字（追加） | key 键名 | back | enter",
                "  wait <条件> [or <条件>] [最长时长] | wait 2s | use 段名",
                "  目标 = x y | #N（上次【现在】列表里的编号）| 画面上的字（先找完全一样的，再找包含的；只写一个数＝点画面上的这个数字）",
                "  键名：back enter tab del space esc search menu up down left right paste copy cut page_up page_down，或数字 keycode（没有 home：副屏没有桌面）",
                "每个动作后可加 until <条件> [or <条件>] [最长时长，默认 5s]；没写就等画面稳定（最多 3 秒；画面只剩循环小动画也算稳定）。",
                "条件：text:字 | gone:字 | change[:x1,y1,x2,y2] | still[:区域]（完全不动）| stable（稳定，容许循环小动画）| color:x,y=#RRGGBB[~容差] | app:包名或界面名",
                "一步没达到预期，这段就停在这一步，后面的不做，结果是失败（退出码 1，outcome=missed/refused）：",
                "  until 到时间没等到；字画面上找不到；同样的字有好几处（AMBIGUOUS，会列出每一处的坐标，改用坐标）；#N 的元素已经不在原位（STALE_ELEMENT）；",
                "  type 没有获得焦点的输入框、或输入后框里没有你的字。type 前先点输入框，【现在】里它标“可输入 有焦点”。",
                "付费、不可撤销的按钮，以及用户设为“每次询问”的应用：手机上会弹出确认，用户点了允许才会做（最多等 60 秒；没答复是 CONFIRM_PENDING，告诉用户去手机上允许，再重发）。",
                "例：act \"tap 搜索; type 耳机; key enter until text:综合\"",
                "    act \"drag 300 900 800 900\" --watch 2s",
                "    act --stop \"text:到货\" --keep notify --for 2h"),
            listOf("do a stretch of steps (up to 40, separated by ; or newlines), returns four blocks. No steps = take a look (waits for the picture to settle, up to 3 s).",
                "steps:",
                "  tap <target> | press <target> [duration, e.g. 1.2s; a bare number is ms] | swipe x1 y1 x2 y2 [duration] | drag x1 y1 x2 y2 [x3 y3 …] [duration]",
                "  scroll up|down|left|right [#N | x y] | type <text> | type+ <text> (append) | key <name> | back | enter",
                "  wait <condition> [or <condition>] [max duration] | wait 2s | use <segment>",
                "  target = x y | #N (number in the last [Now] list) | text on the screen (exact match first, then contains; a single number = tap that number on the screen)",
                "  keys: back enter tab del space esc search menu up down left right paste copy cut page_up page_down, or a numeric keycode (no home: the screen has no launcher)",
                "After each action you may add until <condition> [or <condition>] [max duration, default 5s]; without it the picture is awaited to settle (up to 3 s; a picture left with only a small looping animation counts as settled).",
                "conditions: text:<word> | gone:<word> | change[:x1,y1,x2,y2] | still[:region] (no motion at all) | stable (settled, small looping animation allowed) | color:x,y=#RRGGBB[~tolerance] | app:<package or screen name>",
                "A step that does not reach what it expects stops the stretch there, skips the rest, and the result is a failure (exit code 1, outcome=missed/refused):",
                "  until timed out; text not found on the screen; the same text in several places (AMBIGUOUS, every place is listed with its point: use coordinates); the element of #N is no longer where it was (STALE_ELEMENT);",
                "  type found no focused input box, or your text is not in the box afterwards. Tap the input box before type; in [Now] it is marked \"input focused\".",
                "Payment and irreversible buttons, and apps the user set to \"ask every time\": the phone asks the user, and the step is done only after the user allows it (waits up to 60 s; no answer is CONFIRM_PENDING: tell the user to allow it on the phone, then send again).",
                "e.g. act \"tap Search; type headphones; key enter until text:All\"",
                "     act \"drag 300 900 800 900\" --watch 2s",
                "     act --stop \"text:In stock\" --keep notify --for 2h"),
            listOf(Opt("stop", Kind.VALUE, "条件", "condition", "整段一直盯着，满足就停下，后面的步骤不做", "watched for the whole stretch; when met it stops and skips the rest"),
                Opt("watch", Kind.DURATION, "时长", "duration", "步骤做完再看这么久（会动的画面）", "keep looking this long after the steps (moving pictures)"),
                Opt("fast", Kind.SWITCH, null, null, "动作之间不等画面稳定（游戏、连续点击）；最后一步仍会等", "do not wait for the picture to settle between actions (games, rapid taps); the last step still waits"),
                Opt("bare", Kind.SWITCH, null, null, "这一段不做这个应用记下的前置/收尾步骤", "this stretch skips the before/after steps kept for this app"),
                Opt("keep", Kind.VALUE, "notify|wake", "notify|wake", "本轮结束后继续盯 --stop 的条件，看到就通知用户（notify）或发一条事件给调用方（wake，用 events 取），然后结束；重启后还在", "after this turn keep watching the --stop condition; when seen, notify the user (notify) or send the caller an event (wake, fetched with events), then end; survives a restart"),
                Opt("for", Kind.DURATION, "时长", "duration", "--keep 盯多久（默认 1 小时，最长 12 小时）", "how long --keep watches (default 1 hour, at most 12 hours)"),
                Opt("save", Kind.VALUE, "名字", "name", "把这段步骤存成这个应用的段，以后 use 名字", "save these steps as a segment of this app, later: use <name>"),
                Opt("paused", Kind.SWITCH, null, null, "= --bare", "= --bare", deprecated = true),
                Opt("confirmed", Kind.SWITCH, null, null, "已不起作用：付费/不可撤销由用户在手机上确认", "no longer has any effect: payment / irreversible steps are confirmed by the user on the phone", deprecated = true)),
            long = true, sumZh = "在副屏上做一段步骤（点、滑、输入、等待），返回现在的画面；不带步骤 = 看一眼",
            sumEn = "run a stretch of steps on the screen (tap, swipe, type, wait) and return the screen now; no steps = look"),
        Cmd("note", "[文字] | before \"<步骤>\" | after \"<步骤>\" | delete <编号|before|after|段名>", "[text] | before \"<steps>\" | after \"<steps>\" | delete <number|before|after|segment>",
            listOf("给当前应用记笔记（不带参数 = 列出笔记、段和前置/收尾步骤）。下次 launch 会列出来，并标明是谁、在应用哪个版本时记的。",
                "before / after：每段 act 开始前先做 before 的步骤，做完、观察完再做 after 的步骤（--bare 跳过）。",
                "例：会自己走的画面（游戏、播放器）记 before = 继续的步骤、after = 暂停的步骤，你思考时画面就停着；每次进页面都弹的窗记 before = 关掉它的步骤。"),
            listOf("keep notes for the current app (no arguments = list notes, segments and before/after steps). The next launch lists them, with who wrote each and for which app version.",
                "before / after: every act first does the before steps, and after acting and looking does the after steps (--bare skips them).",
                "e.g. for a picture that moves by itself (a game, a player) keep before = the steps that resume and after = the steps that pause, so it stands still while you think; for a popup shown on every visit keep before = the steps that close it."),
            sumZh = "给当前应用记笔记、前置/收尾步骤", sumEn = "keep notes and before/after steps for the current app"),
        Cmd("events", "", "",
            listOf("取盯守（act --keep wake）看到条件时发来的事件。事件有序号，取走后在你下一次 events 时才删除（中途断了不会丢）。",
                "这个会话没有正在运行的 --keep wake 盯守时不等，直接说明。"),
            listOf("fetch events that watches (act --keep wake) sent when their condition was seen. Events are numbered and are deleted only at your next events call (nothing is lost if the connection drops in between).",
                "Without a running --keep wake watch in this session it does not wait and says so."),
            listOf(Opt("wait", Kind.DURATION, "时长", "duration", "没有事件时最多等这么久（默认不等，最长 120s）", "wait up to this long when there is none (default no wait, at most 120s)"),
                Opt("again", Kind.SWITCH, null, null, "把上一次取走的那批再给一遍", "return the previously fetched batch again")),
            scope = Scope.LOOK, screen = false),
        Cmd("task", "[任务号] | cancel <任务号>", "[task id] | cancel <task id>",
            listOf("查看用 --async 开始的任务：不带参数列出本会话的任务；带任务号返回它的状态，做完了就是它的完整结果；cancel 让它在下一步之前停下。"),
            listOf("look at tasks started with --async: no argument lists this session's tasks; with a task id it returns the state, and the full result once finished; cancel stops it before its next step."),
            listOf(Opt("wait", Kind.DURATION, "时长", "duration", "任务没做完时最多等这么久（最长 120s）", "wait up to this long while the task is unfinished (at most 120s)")),
            scope = Scope.LOOK, screen = false),
        Cmd("title", "[文字]", "[text]",
            listOf("在悬浮窗上显示你正在做什么（最多 40 字，给用户看；不带文字 = 清除；这一轮结束时自动清除）。"),
            listOf("show what you are doing on the floating window (up to 40 characters, for the user; no text = clear; cleared when this turn ends)."),
            screen = false),
        Cmd("done", "", "",
            listOf("这一轮做完了：副屏马上省电暂停、页面保留 10 分钟，别的会话可以接手（不 done 的话安静满 N 分钟自动结束，N 在 App 里设）。"),
            listOf("this turn is finished: the screen pauses to save power at once, the page is kept 10 minutes, and other sessions can take over (without done, it ends after N quiet minutes, set in the app).")),
        Cmd("keep", "| off <名字|all>", "| off <name|all>",
            listOf("列出 / 停止本轮之后的盯守（用 act --stop … --keep 开始）。"),
            listOf("list / stop watches that run after this turn (started with act --stop … --keep)."), screen = false),
        Cmd("stop", "[包名]", "[package]",
            listOf("关掉副屏上的应用。"),
            listOf("close the app on the screen."),
            listOf(Opt("force", Kind.SWITCH, null, null, "结束整个应用（主屏上的也会被结束）：手机上会请用户确认", "stop the whole app (also on the main screen): the phone asks the user to confirm"))),
        Cmd("release", "", "", listOf("立即释放副屏。"), listOf("release the screen now.")),
        Cmd("handoff", "in [包名] | out [包名]", "in [package] | out [package]",
            listOf("in：把用户主屏上的应用移到副屏接着做（返回四段；手机上会请用户确认）；out：交还到主屏，需要用户本人操作时用。"),
            listOf("in: move the app from the user's main screen to the background screen and carry on (returns four blocks; the phone asks the user to confirm); out: hand it back to the main screen when the user must act."),
            listOf(Opt("note", Kind.VALUE, "说明", "text", "out 时给用户的通知里写什么", "what the notification to the user says on out"))),
        Cmd("status", "", "",
            listOf("后台操作是否可用、副屏、权限、应用限制、正在盯守的条件、排队中的命令。"),
            listOf("whether background operation works, the screen, permissions, app rules, watches running, commands in the queue."),
            scope = Scope.LOOK, screen = false),
        Cmd("apps", "[关键词]", "[keyword]", listOf("已安装应用和包名。"), listOf("installed apps and packages."), scope = Scope.LOOK, screen = false),
        Cmd("spots", "[x1,y1,x2,y2]", "[x1,y1,x2,y2]",
            listOf("本地按颜色找色块（不调模型），列出中心坐标和标注图。"), listOf("find color spots locally (no model), listing centers and a marked picture."),
            listOf(Opt("color", Kind.VALUE, "#RRGGBB", "#RRGGBB", "只找这种颜色", "only this color")), scope = Scope.LOOK),
        Cmd("zoom", "x1,y1,x2,y2", "x1,y1,x2,y2",
            listOf("放大看一块区域（图边上标着副屏坐标）。"), listOf("zoom into a region (screen coordinates on the picture's edges)."),
            listOf(Opt("scale", Kind.VALUE, "倍数", "factor", "放大倍数（默认按区域大小定）", "zoom factor (default follows the region size)")), scope = Scope.LOOK),
        Cmd("collect", "", "",
            listOf("从当前位置往下一页页滚，收集每一行文字（去重）。"), listOf("scroll down page by page from here and collect every line of text (deduplicated)."),
            listOf(Opt("pages", Kind.VALUE, "页数", "count", "最多翻几页（默认 8，最多 40）", "pages to go through at most (default 8, at most 40)"),
                Opt("until", Kind.VALUE, "文字", "text", "看到这段文字就停", "stop when this text is seen")), long = true),
        Cmd("fix", "[code]", "[code]",
            listOf("修复 status 里列出的问题。会改系统设置或在主屏打开设置页的步骤：手机上会请用户确认。"),
            listOf("fix the problems listed in status. Steps that change system settings or open a settings page on the main screen: the phone asks the user to confirm."),
            listOf(Opt("open", Kind.SWITCH, null, null, "需要用户手动处理时，在主屏打开对应页面", "when the user must act by hand, open the page on the main screen")), scope = Scope.SYSTEM, screen = false),
        Cmd("diag", "", "",
            listOf("导出一份诊断（版本、状态、最近的日志和被吞掉的异常；不含你输入的文字和截图），返回文本，出问题时交给开发者。"),
            listOf("export a diagnostic bundle (versions, status, recent logs and swallowed exceptions; none of your typed text or screenshots) as text, to hand to the developer when something is wrong."),
            scope = Scope.LOOK, screen = false),
    )

    private val byName: Map<String, Cmd> = commands.associateBy { it.name }
    fun of(name: String?): Cmd? = name?.let { byName[it] }

    /** Words the transport answers itself (no screen, no table entry needed for options). */
    val TRANSPORT_WORDS = setOf("guide", "remembered", "help")

    /** Options every command takes (help, output shaping, reply language). */
    val GLOBAL_OPTIONS: Set<String> = setOf("help") + OffloadOutput.OUTPUT_FLAGS

    fun optionNames(sub: String): Set<String>? = of(sub)?.allOpts?.map { it.name }?.toSet()
    fun kindOf(sub: String, key: String): Kind? = of(sub)?.allOpts?.firstOrNull { it.name == key }?.kind
    val switches: Set<String> get() = commands.flatMap { it.allOpts }.filter { it.kind == Kind.SWITCH }.map { it.name }.toSet()
    val durations: Set<String> get() = commands.flatMap { it.allOpts }.filter { it.kind == Kind.DURATION }.map { it.name }.toSet()

    // ── help text ────────────────────────────────────────────────────────────

    private val head: String get() = tr(
        "vscreen — Agent-VScreen 后台操作：在看不见的副屏里操作其他应用，不占用户主屏。截图由你（调用方的模型）看，手机只报事实。\n" +
            "不需要 adb 或无线调试：特权走 Agent-VScreen 自己的 Shizuku 通道。不要去连 adb、配无线调试或改 adb 密钥。没有副屏时直接 launch，会自动建屏。\n\n" +
            "怎么用（一个循环）：看（launch / act 返回的【现在】和截图）→ 做（act 一小段步骤，最后一步带 until 写明你期待看到什么）→ 核对（结果 ok 吗？【注意】里有什么？）。\n" +
            "一段里只放你有把握连着做的步骤；不确定页面会变成什么样时，在那里断开，先看再做。\n\n" +
            "返回的四段（launch / act / handoff in）：\n" +
            "  【做了什么】 每一步做了什么、等到了什么或等到时间没等到、哪个停止条件触发了（只有事实）\n" +
            "  【这段时间】 从你上次拿到结果到现在（包括你思考的时间）：出现/消失/出现又消失的字，画面哪里变了多久；有变化时附拼图\n" +
            "  【现在】     前台界面、副屏尺寸、截图路径（边上标着副屏坐标）和编号元素：#N “字” (x,y) 可点/可输入/…；同一页上只列新出现或变了的元素（编号不变），不带步骤的 act 列出全部\n" +
            "  【注意】     每条以代码开头（help codes 有每个代码的意思和该怎么办）；没有就是“无”\n" +
            "结果是否成功看 ok / 退出码，不用读文字判断：0 = 整段按预期做完；1 = 停在了某一步或被拒绝（code 说明原因）；2 = 命令写错了，什么都没做。\n" +
            "HTTP / MCP 返回的 JSON 里同样的内容是结构化的：steps[]（每步的状态）、screen.elements[]、notes[]（代码 + 文字）。\n\n命令：",
        "vscreen — Agent-VScreen background operation: operate other apps on an invisible background screen without taking the user's screen. You (the caller's model) look at the screenshots; the phone only reports facts.\n" +
            "No adb or wireless debugging needed: privileges go through Agent-VScreen's own Shizuku channel. Don't connect adb, set up wireless debugging or change adb keys. With no screen, just launch: one is created automatically.\n\n" +
            "How to use it (one loop): look (the [Now] block and screenshot returned by launch / act) → act (a short stretch of steps, the last one with until saying what you expect to see) → check (is the result ok? what is in [Notes]?).\n" +
            "Put in one stretch only the steps you are sure can follow each other; where you cannot tell what the page will become, break there and look before acting.\n\n" +
            "The four blocks returned (launch / act / handoff in):\n" +
            "  [Done]       what each step did, what was waited for and whether it came or time ran out, which stop condition fired (facts only)\n" +
            "  [Meanwhile]  from your last result until now (including your thinking time): text that appeared / disappeared / flashed, where the picture changed and for how long; a contact sheet when it changed\n" +
            "  [Now]        front screen, screen size, screenshot path (screen coordinates on the edges) and numbered elements: #N \"text\" (x,y) tappable/input/…; on the same page only new or changed elements are listed (numbers stay), act with no steps lists all\n" +
            "  [Notes]      every line starts with a code (help codes explains each code and what to do); \"none\" when there are none\n" +
            "Whether it worked is in ok / the exit code, no need to read the text for it: 0 = the whole stretch went as expected; 1 = it stopped at a step or was refused (code says why); 2 = the command was written wrong and nothing ran.\n" +
            "The JSON returned over HTTP / MCP has the same content structured: steps[] (each step's status), screen.elements[], notes[] (code + text).\n\nCommands:")

    private val rules: String get() = tr(
        "用户接管或重启之后：下一次 launch 或 act 返回的【现在】就算重新看过画面，不需要别的命令（没有 resume）。\n" +
            "命令行的完整输出也存在 VSCREEN_SHOT_DIR 下的 last.txt（默认 TMPDIR/vscreen-shots/last.txt），输出太长或重定向出问题时读这个文件。\n\n" +
            "规则：\n" +
            "- 副屏不可用时命令直接失败，不会改到主屏执行。\n" +
            "- 命令一条一条排队执行：同一块副屏上不会有两条命令同时动。排队超过 60 秒返回 QUEUE_TIMEOUT，这条没有执行。\n" +
            "- 用户点“接管”时动作会等待（最多 15 秒，之后 USER_TAKEOVER）；交还后第一个 act 会先返回现在的画面（REREAD_REQUIRED），看清再继续。\n" +
            "- 用户在悬浮窗点“停止 AI”后，所有操作返回 USER_STOPPED：停下来，把返回里“怎么解除”那句转告用户，不要重试。\n" +
            "- 用户可以把应用设成“禁止操作”（APP_BLOCKED）、“只读”（APP_READ_ONLY）或“每次询问”（手机上确认）。这是用户的决定，不要绕过，也不要换个入口进同一个应用。\n" +
            "- 付费、不可撤销的步骤，stop --force、fix、handoff in：由用户在手机上确认（或用户事先给这个令牌开了免确认时段）。你自己加任何参数都不能代替用户确认。\n" +
            "- 涉及隐私（私信、通讯录、相册、余额）的步骤，先问用户，答复后再做。\n" +
            "- 一个副屏同一时间只属于一个会话（请求头 X-VScreen-Session，默认 default）。别的会话在用时返回 BUSY，写明归谁、多久后自动让出。\n" +
            "- --keep 在你不在时替你盯：告诉用户你在盯什么、盯多久、看到后会怎样、怎么停（keep off）。",
        "After a user takeover or a restart: the [Now] of the next launch or act counts as a fresh look; no other command is needed (there is no resume).\n" +
            "The full command-line output is also saved as last.txt under VSCREEN_SHOT_DIR (default TMPDIR/vscreen-shots/last.txt); read it when output is too long or redirection goes wrong.\n\n" +
            "Rules:\n" +
            "- When the background screen is unavailable, commands fail; they never run on the main screen instead.\n" +
            "- Commands run one at a time, in a queue: two commands never move the same screen at once. After 60 s in the queue a command returns QUEUE_TIMEOUT and has not run.\n" +
            "- While the user has taken over, actions wait (up to 15 s, then USER_TAKEOVER); the first act after the hand-back returns the screen now (REREAD_REQUIRED): look, then carry on.\n" +
            "- After the user taps \"Stop AI\" on the floating window, every action returns USER_STOPPED: stop, pass on the \"how to undo\" line to the user, don't retry.\n" +
            "- The user can set an app to \"blocked\" (APP_BLOCKED), \"read-only\" (APP_READ_ONLY) or \"ask every time\" (confirmed on the phone). That is the user's decision: don't get around it, and don't enter the same app another way.\n" +
            "- Payment and irreversible steps, stop --force, fix and handoff in are confirmed by the user on the phone (or the user gave this token a no-confirmation period beforehand). No option you add can stand in for the user's confirmation.\n" +
            "- Steps touching privacy (messages, contacts, photos, balances): ask the user first and act after the answer.\n" +
            "- A screen belongs to one session at a time (header X-VScreen-Session, default \"default\"). When another session is using it you get BUSY, with whose it is and when it frees up.\n" +
            "- --keep watches for you while you are away: tell the user what you watch, for how long, what happens when seen, and how to stop it (keep off).")

    /** `help all`. */
    fun fullHelp(): String = buildString {
        append(head).append('\n')
        commands.forEach { c -> c.help().forEach { append(it).append('\n') } }
        append("  guide\n        ").append(tr("用法速查（一页）。", "the one-page quick usage.")).append('\n')
        append("  help [all|codes|<").append(tr("命令", "command")).append(">]\n        ").append(tr("完整说明 / 代码表 / 单条命令。", "the full reference / the code table / one command.")).append('\n')
        append('\n').append(rules)
    }

    /** `help` (short). */
    fun quickHelp(): String = tr(
        "vscreen — Agent-VScreen：在看不见的副屏里操作应用，不占用户主屏（完整说明：help all；单条：help act；代码表：help codes）\n" +
            "  launch 应用名            打开应用，返回四段（做了什么 / 这段时间 / 现在 / 注意）\n" +
            "  act \"步骤; 步骤; …\"      做一段操作，返回四段；act 不带步骤 = 看一眼\n" +
            "        步骤：tap 目标 | press 目标 | swipe x1 y1 x2 y2 | drag x1 y1 x2 y2 … | scroll down | type 文字 | key back | wait 条件 | use 段名\n" +
            "        目标：x y | #N（【现在】列表的编号）| 画面上的字；动作后可加 until 条件（text:字 gone:字 change still stable color:x,y=#RRGGBB app:名）\n" +
            "        --stop 条件  --watch 2s  --fast  --bare  --keep notify|wake  --save 段名  --async\n" +
            "  note [文字] | note before|after \"步骤\" | note delete 编号\n" +
            "  keep | keep off 名字      查看 / 停止本轮之后的盯守\n" +
            "  events [--wait 30s]       取盯守（--keep wake）发来的事件\n" +
            "  task [任务号]             查看 --async 开始的任务\n" +
            "  title 文字 | done         告诉用户你在做什么（悬浮窗上显示）| 这一轮做完了（副屏让出，页面保留）\n" +
            "  stop | release | handoff in|out | status | apps 关键词 | spots | zoom 区域 | collect | fix | diag\n" +
            "成功与否看退出码 / ok：0 整段按预期做完；1 停在某一步或被拒绝（【注意】里的代码说明原因，help codes 查怎么办）；2 命令写错。\n" +
            "一步没达到预期（until 没等到、字没输进去、找不到或有歧义）这段就停在那一步，后面的不做，【注意】里写明现在的画面。\n" +
            "输出很长时完整结果也存在截图目录的 last.txt（路径见 help all），可以用 head / sed 分段看。",
        "vscreen — Agent-VScreen: operate apps on an invisible background screen without taking the user's screen (full guide: help all; one command: help act; codes: help codes)\n" +
            "  launch <app>              open an app, returns four blocks (Done / Meanwhile / Now / Notes)\n" +
            "  act \"step; step; …\"       do a stretch of steps, returns four blocks; act with no steps = take a look\n" +
            "        steps: tap <target> | press <target> | swipe x1 y1 x2 y2 | drag x1 y1 x2 y2 … | scroll down | type <text> | key back | wait <condition> | use <segment>\n" +
            "        target: x y | #N (number in the [Now] list) | text on the screen; after an action add until <condition> (text:word gone:word change still stable color:x,y=#RRGGBB app:name)\n" +
            "        --stop <condition>  --watch 2s  --fast  --bare  --keep notify|wake  --save <segment>  --async\n" +
            "  note [text] | note before|after \"steps\" | note delete <number>\n" +
            "  keep | keep off <name>    list / stop watches that run after this turn\n" +
            "  events [--wait 30s]       fetch events sent by watches (--keep wake)\n" +
            "  task [id]                 look at tasks started with --async\n" +
            "  title <text> | done       tell the user what you are doing (shown on the floating window) | this turn is finished (screen freed, page kept)\n" +
            "  stop | release | handoff in|out | status | apps <keyword> | spots | zoom <region> | collect | fix | diag\n" +
            "Success is in the exit code / ok: 0 the whole stretch went as expected; 1 it stopped at a step or was refused (the code in [Notes] says why, help codes says what to do); 2 the command was written wrong.\n" +
            "If a step does not reach what it expects (until never came, text was not typed in, not found or ambiguous), the stretch stops at that step, skips the rest, and [Notes] describes the screen now.\n" +
            "When output is long, the full result is also saved as last.txt in the screenshot folder (path in help all); read it in parts with head / sed.")

    /** `help <command>`; null when there is no such command. */
    fun helpFor(name: String): String? = of(name)?.help()?.joinToString("\n")

    // ── generated reference (API.md) and MCP schemas ─────────────────────────

    /** The command table as Markdown, for API.md (written by SpecDocTest when asked, checked by it otherwise). */
    fun markdown(): String = buildString {
        for (c in commands) {
            append("### `").append(c.usageLine()).append("`\n\n")
            c.body.forEach { l -> append(if (l.startsWith("  ") || l.startsWith("    ")) "    $l\n" else "$l\n") }
            val opts = c.allOpts.filter { !it.deprecated }
            if (opts.isNotEmpty()) { append("\n"); opts.forEach { append("- `").append(it.usage()).append("` ").append(it.text).append('\n') } }
            append("\n").append(tr("权限范围", "scope")).append(": `").append(c.scope.key).append("`")
            if (c.screen) append(tr(" · 在副屏队列里执行", " · runs in the screen queue"))
            append("\n\n")
        }
    }

    /** JSON schema of one command's arguments as an MCP tool input: `args` (the positional part) plus one property per option. */
    fun inputSchema(c: Cmd): JSONObject {
        val props = JSONObject()
        if (c.positional.isNotEmpty()) props.put("args", JSONObject().put("type", "string").put("description", c.positional))
        for (o in c.allOpts.filter { !it.deprecated }) props.put(o.name.replace('-', '_'), JSONObject()
            .put("type", if (o.kind == Kind.SWITCH) "boolean" else "string").put("description", o.text))
        props.put("session", JSONObject().put("type", "string").put("description", tr("可选：会话名", "optional: session name")))
        return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray())
    }

    /** The argv of a structured call (MCP typed tool / HTTP `{"command":"act","args":…,"options":{…}}`). */
    fun argvOf(c: Cmd, args: String?, options: JSONObject?): List<String> {
        val out = arrayListOf(c.name)
        args?.takeIf { it.isNotBlank() }?.let { a -> if (c.name == "act" || c.name == "title" || c.name == "launch") out += a else out += com.kilon.agentvscreen.api.ApiServer.splitCommand(a) }
        if (options != null) for (o in c.allOpts) {
            val k = listOf(o.name, o.name.replace('-', '_')).firstOrNull { options.has(it) } ?: continue
            if (o.kind == Kind.SWITCH) { if (options.optBoolean(k, false)) out += "--${o.name}" }
            else options.opt(k)?.toString()?.takeIf { it.isNotEmpty() && it != "null" }?.let { out += "--${o.name}"; out += it }
        }
        return out
    }
}
