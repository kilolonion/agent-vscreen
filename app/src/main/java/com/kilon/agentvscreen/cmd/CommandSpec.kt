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
              private val sumZh: String = "", private val sumEn: String = "",
              /**
               * 1.2.0-beta2: what THIS call needs, when it differs from [scope] (act without action steps only looks;
               * stop --force and handoff in reach beyond the background screen). Null = [scope].
               */
              val scopeOf: ((OffloadArgs) -> Scope)? = null,
              private val scopeNoteZh: String = "", private val scopeNoteEn: String = "",
              /**
               * 2.0.0-beta11 (review): the lowest level that may call it and whether it changes anything are two things.
               * `elevate` is callable while looking only (else a viewer could never ask), yet it notifies the phone and
               * may change the agent's table, so it is not read-only.
               */
              val sideEffects: Boolean = false) {
        /** For MCP's readOnlyHint: callable while looking only and changes nothing. */
        val readOnly: Boolean get() = scope == Scope.LOOK && !sideEffects
        val scopeNote: String get() = tr(scopeNoteZh, scopeNoteEn)
        fun scopeFor(args: OffloadArgs): Scope = scopeOf?.invoke(args) ?: scope
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

    /** An act that only looks: no steps or only waits, and nothing kept, saved, held, or rule-driven. */
    private fun looksOnly(a: OffloadArgs): Boolean {
        if (a.get("keep") != null || a.get("save") != null || a.hasFlag("hold") || a.getAll("on").isNotEmpty()) return false
        val body = a.positional.joinToString(" ").trim()
        if (body.isEmpty()) return true
        return runCatching { Steps.parseAll(body).all { it.kind == StepKind.WAIT || it.kind == StepKind.SLEEP } }.getOrDefault(false)
    }

    private val ASYNC = Opt("async", Kind.SWITCH, null, null,
        "不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）", "do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)")

    val commands: List<Cmd> = listOf(
        Cmd("launch", "<应用名|包名>", "<app name|package>",
            listOf("打开应用（找不到就 apps 关键词）。默认按应用调整副屏；--keep 切回原页且不重建。",
                "返回四段；【做了什么】里还会列出你以前给这个应用记的 note、存的段、前置/收尾步骤。"),
            listOf("open an app (if not found, apps <keyword>). By default the screen fits the app; --keep switches to the existing page without rebuilding.",
                "Returns four blocks; [Done] also lists the notes, saved segments and before/after steps you kept for this app."),
            listOf(Opt("landscape", Kind.SWITCH, null, null, "副屏按横屏建", "build the screen in landscape"),
                Opt("portrait", Kind.SWITCH, null, null, "副屏按竖屏建", "build the screen in portrait"),
                Opt("restart", Kind.SWITCH, null, null, "冷启动（先结束应用）", "cold start (stops the app first)"),
                Opt("asked", Kind.SWITCH, null, null, "用户正在主屏用这个应用时，已经问过用户并得到同意", "the user is using this app on the main screen and has agreed when asked"),
                Opt("keep", Kind.SWITCH, null, null, "副屏保持现在的方向和尺寸：不重建、不按应用调整（应用没在上面时也一样）；已经有它的页面就切回原页面。不能和 --landscape/--portrait/--restart 一起用",
                    "keep the screen's orientation and size as they are: no rebuild and no fitting it to the app (also when the app is not on it yet); an existing page of it is brought back. Not with --landscape/--portrait/--restart")),
            long = true, sumZh = "打开应用并返回现在的画面", sumEn = "open an app and return the screen now"),
        Cmd("act", "[\"步骤; 步骤; …\"]", "[\"step; step; …\"]",
            listOf("做一段操作（最多 40 步，用 ; 或换行分开），返回四段。不带步骤 = 看一眼（等画面稳定，最多 3 秒）。",
                "步骤：",
                "  tap 目标 | press 目标 [时长，如 1.2s；纯数字是毫秒] | swipe x1 y1 x2 y2 [时长] | drag x1 y1 x2 y2 [x3 y3 …] [时长]",
                "  闭环手势（按着边看边松手）：press 目标 hold until <条件> [最长]（默认 5s，上限 10s）| drag x1 y1 x2 y2 […] [时长] hold until <条件> [最长] | drag x1 y1 x2 y2 seek until <条件> [最长]（两点直线小步移动，默认 15s，上限 30s）；手势里的 text:/gone: 必须带 in x1,y1,x2,y2；不做 swipe … until，翻找用 repeat { swipe … } until text:…",
                "  scroll up|down|left|right [#N | x y] | type 文字 | type+ 文字（追加） | type \"文字\" slow [200ms]（逐字输入） | key 键名 | key ctrl+a（组合键：ctrl/shift/alt + 键） | back | enter",
                "  wait <条件> [or <条件>] [最长时长] | wait 2s | use 段名",
                "  目标 = x y | #N（上次【现在】列表里的编号）| 画面上的字（先找完全一样的，再找包含的；只写一个数＝点画面上的这个数字）",
                "  字里可写 *（任意字，整段字要对上：*蓝牙* 含蓝牙，共*条 以共开头以条结尾），text:/gone: 条件也一样；字@N = 从上往下第 N 处（@-1 最后一处）；字 +dx,+dy = 字旁边那一点（图标、开关）；坐标可写屏幕比例 50% 80%（换屏幕尺寸也点同一处）",
                "  流程：try 一步 | try { 几步 }（没找到、没达到预期就跳过接着做）| if <条件> [最长] { … } [else { … } | else 一步]（看现在的画面，默认等 1.5s）| repeat 3 { … } | repeat [最多次数，最多 50] { … } until <条件>（每轮后看，默认最多 10 次，上限 50 次）。括号里可以换行、可以嵌套",
                "  键名：back enter tab del space esc search menu up down left right paste copy cut page_up page_down，或数字 keycode（没有 home：副屏没有桌面）",
                "每个动作后可加 until <条件> [or <条件>] [最长时长，默认 5s]（until text: 的字要是动作后才会新出现的字，动作前已在画面上的字不算）；没写就等画面稳定（最多 3 秒；画面只剩循环小动画也算稳定）；这次等待结束后（稳定了或到时间）画面和动作前一样时，再看最多 1.5 秒（type、编辑键和 --fast 的中间步骤不看），时刻从动作完成算起：变了就等它稳定，说“画面在动作 X 后才变化，Y 时稳定”（或“等到 Y 还在变”）；其间满足 --stop 条件就停在这一步，后面不做，不给 NO_CHANGE；仍没变才说“画面没有变化”（NO_CHANGE，只表示这段时间里没检测到变化）。",
                "条件：text:字（动作后新出现的字；* 可以一个字也不配，9* 也匹配 9%）| gone:字 | change[:x1,y1,x2,y2] | still[:区域]（完全不动）| stable（稳定，容许循环小动画）| color:x,y=#RRGGBB[~容差] | app:包名或界面名",
                "一步没达到预期，这段就停在这一步，后面的不做，结果是失败（退出码 1，outcome=missed/refused）：",
                "  until 到时间没等到；字画面上找不到；同样的字有好几处（AMBIGUOUS，会列出每一处的坐标，改用坐标）；#N 的元素已经不在原位（STALE_ELEMENT）；",
                "  type 没有获得焦点的输入框、或输入后框里没有你的字。type 前先点输入框，【现在】里它标“可输入 有焦点”。",
                "支付按钮（付款、提交订单、转账）、用户设为“每次确认”的应用，以及这个 agent 的权限表里设为“问我”的类别（不可撤销操作、敏感操作等）：手机上会弹出确认，用户允许后才会执行；设为“不允许”的类别直接返回 SCOPE_DENIED（最多等 60 秒；没答复是 CONFIRM_PENDING，告诉用户去手机上允许，再重发）。",
                "例：act \"tap 搜索; type 耳机; key enter until text:综合\"",
                "    act \"drag 300 900 800 900\" --watch 2s",
                "    act \"drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s\"",
                "    act \"drag 100 800 980 800 seek until text:70 in 400,600,700,700\"",
                "    act \"press 540 1600 hold until text:9* in 300,1400,800,1500 8s\"",
                "    act --on \"text:限时优惠 => tap 980,420\" \"tap 确定 until text:已确定\"",
                "    act --on \"text:点我 => tap 点我 x3\" --watch 10s",
                "    act \"tap 开始 until text:进行中\" --hold",
                "    act --stop \"text:到货\" --keep notify --for 2h"),
            listOf("do a stretch of steps (up to 40, separated by ; or newlines), returns four blocks. No steps = take a look (waits for the picture to settle, up to 3 s).",
                "steps:",
                "  tap <target> | press <target> [duration, e.g. 1.2s; a bare number is ms] | swipe x1 y1 x2 y2 [duration] | drag x1 y1 x2 y2 [x3 y3 …] [duration]",
                "  closed-loop gestures (judge while holding): press <target> hold until <cond> [max] (default 5s, max 10s) | drag x1 y1 x2 y2 […] [duration] hold until <cond> [max] | drag x1 y1 x2 y2 seek until <cond> [max] (2-point line in small steps, default 15s, max 30s); text:/gone: inside hold/seek must include in x1,y1,x2,y2; no swipe … until — use repeat { swipe … } until text:…",
                "  scroll up|down|left|right [#N | x y] | type <text> | type+ <text> (append) | type \"<text>\" slow [200ms] (one character at a time) | key <name> | key ctrl+a (shortcut: ctrl/shift/alt + key) | back | enter",
                "  wait <condition> [or <condition>] [max duration] | wait 2s | use <segment>",
                "  target = x y | #N (number in the last [Now] list) | text on the screen (exact match first, then contains; a single number = tap that number on the screen)",
                "  words may hold * (any characters; the whole text must match: *Wi-Fi* holds Wi-Fi), in text:/gone: conditions too; words@N = the N-th place from the top (@-1 the last); words +dx,+dy = the point that far beside them (an icon, a switch); coordinates may be shares of the screen, 50% 80% (same place on any screen size)",
                "  flow: try <step> | try { steps } (not found or not as expected: skip and go on) | if <condition> [max] { … } [else { … } | else <step>] (the screen as it is, waits up to 1.5 s by default) | repeat 3 { … } | repeat [at most, max 50] { … } until <condition> (checked after each round, default 10, max 50). Braces may span lines and nest",
                "  keys: back enter tab del space esc search menu up down left right paste copy cut page_up page_down, or a numeric keycode (no home: the screen has no launcher)",
                "After each action you may add until <condition> [or <condition>] [max duration, default 5s] (until text: must match text that newly appears after the action; text already on screen beforehand does not count); without it the picture is awaited to settle (up to 3 s; a picture left with only a small looping animation counts as settled); if after that wait (settled or timed out) it matches the picture before the action, it looks up to 1.5 s longer (not for type, edit keys or the in-between steps of --fast), timing from when the action finished: a change then is waited out and said as \"the screen changed late, X after the action, and settled at Y\" (or \"was still changing when time was up at Y\"); a --stop condition met meanwhile stops the stretch there, skips the rest and gives no NO_CHANGE; only a picture still unchanged is \"the screen did not change\" (NO_CHANGE: no change was seen in that time).",
                "conditions: text:<word> (newly appearing after action; * may match zero chars: 9* also matches 9%) | gone:<word> | change[:x1,y1,x2,y2] | still[:region] (no motion at all) | stable (settled, small looping animation allowed) | color:x,y=#RRGGBB[~tolerance] | app:<package or screen name>",
                "A step that does not reach what it expects stops the stretch there, skips the rest, and the result is a failure (exit code 1, outcome=missed/refused):",
                "  until timed out; text not found on the screen; the same text in several places (AMBIGUOUS, every place is listed with its point: use coordinates); the element of #N is no longer where it was (STALE_ELEMENT);",
                "  type found no focused input box, or your text is not in the box afterwards. Tap the input box before type; in [Now] it is marked \"input focused\".",
                "Buttons that actually spend money (pay, place order, transfer) and apps the user set to \"confirm every time\" (plus the classes this agent's permission table sets to \"Ask me\", such as irreversible or sensitive actions): the phone asks the user, and the step is done only after the user allows it; classes set to \"Not allowed\" return SCOPE_DENIED (waits up to 60 s; no answer is CONFIRM_PENDING: tell the user to allow it on the phone, then send again).",
                "e.g. act \"tap Search; type headphones; key enter until text:All\"",
                "     act \"drag 300 900 800 900\" --watch 2s",
                "     act \"drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s\"",
                "     act \"drag 100 800 980 800 seek until text:70 in 400,600,700,700\"",
                "     act \"press 540 1600 hold until text:9* in 300,1400,800,1500 8s\"",
                "     act --on \"text:Promo => tap 980,420\" \"tap OK until text:Done\"",
                "     act --on \"text:Tap me => tap Tap me x3\" --watch 10s",
                "     act \"tap Start until text:Running\" --hold",
                "     act --stop \"text:In stock\" --keep notify --for 2h"),
            listOf(Opt("stop", Kind.VALUE, "条件", "condition", "整段一直盯着，满足就停下，后面的步骤不做", "watched for the whole stretch; when met it stops and skips the rest"),
                Opt("on", Kind.VALUE, "条件 => 一步", "cond => step", "短程规则（仅本次 act 生效，可写 1–3 条，不跨轮）：条件成立时执行一步（只允许 tap / key back / scroll / wait 时长，可选 x1–x3，默认 x1；条件不能是 stable / still；text: / gone: 连续 2 次独立读屏才触发，间隔 500ms；规则不能触发付款或“每次确认”应用）", "short-range rule for this act only (1–3 rules, never persisted): when cond holds, run one step (tap / key back / scroll / wait duration only; optional x1–x3, default x1; cond cannot be stable / still; text: / gone: requires 2 consecutive independent readings with 500ms cooldown; rules never auto-confirm payments or ask-every-time apps)"),
                Opt("hold", Kind.SWITCH, null, null, "回复发出前暂停副屏前台应用并核实，下一条命令前自动恢复（最长 120 秒，仅 Android 14+；一直要就 note hold on）。注意：Android 15 起，暂停正在频繁刷新的应用时，系统可能直接结束它；暂停只对按帧计时的应用有效，按真实时钟计时的应用恢复后会直接跳过这段时间；暂停期间网络连接可能超时", "pause and verify the virtual screen's foreground app before sending the reply, resuming before the next command (max 120s, Android 14+ only; set default with note hold on). Note: starting on Android 15, pausing an actively refreshing app may cause the system to kill it; pausing only stops frame-timed apps (wall-clock apps skip ahead on resume) and active network connections may time out"),
                Opt("watch", Kind.DURATION, "时长", "duration", "步骤做完再看这么久（会动的画面）", "keep looking this long after the steps (moving pictures)"),
                Opt("fast", Kind.SWITCH, null, null, "只跳过动作之间的画面稳定等待（游戏、连续点击）；最后一步以及 repeat 内每轮的 until / 稳定等待仍会执行", "skip settle waits only between actions (games, rapid taps); the last step and each repeat iteration's until / settle wait still run"),
                Opt("bare", Kind.SWITCH, null, null, "这一段不做这个应用记下的前置/收尾步骤", "this stretch skips the before/after steps kept for this app"),
                Opt("human", Kind.SWITCH, null, null, "仿人节奏：每个动作前随机停 0.2–2.6 秒，点击在元素内略偏、按住和滑动的时长与位置有波动，打字分 1–3 个字一段（对节奏敏感的应用用；条件判断不变）。一直要就 note human on", "human pacing: a random 0.2–2.6 s pause before each action, taps slightly off-centre inside the element, varied hold and swipe lengths, typing 1–3 characters at a time (for apps sensitive to machine timing; conditions are unchanged). To keep it on: note human on"),
                Opt("keep", Kind.VALUE, "notify|wake", "notify|wake", "本轮结束后继续盯 --stop 的条件，看到就通知用户（notify）或发一条事件给调用方（wake，用 events 取），然后结束；重启后还在", "after this turn keep watching the --stop condition; when seen, notify the user (notify) or send the caller an event (wake, fetched with events), then end; survives a restart"),
                Opt("for", Kind.DURATION, "时长", "duration", "--keep 盯多久（默认 1 小时，最长 12 小时）", "how long --keep watches (default 1 hour, at most 12 hours)"),
                Opt("save", Kind.VALUE, "名字", "name", "把这段步骤存成这个应用的段，以后 use 名字", "save these steps as a segment of this app, later: use <name>"),
                Opt("full", Kind.SWITCH, null, null, "这次一定出完整回复；不带步骤时按上次 act 的时间窗重算【这段时间】并返回全屏图和全部元素", "always return a full reply this time; with no steps, replays the last act window with a full picture and all elements"),
                Opt("paused", Kind.SWITCH, null, null, "= --bare", "= --bare", deprecated = true),
                Opt("confirmed", Kind.SWITCH, null, null, "已不起作用：付费/不可撤销由用户在手机上确认", "no longer has any effect: payment / irreversible steps are confirmed by the user on the phone", deprecated = true)),
            long = true, sumZh = "在副屏上做一段步骤（点、滑、输入、等待），返回现在的画面；不带步骤 = 看一眼",
            sumEn = "run a stretch of steps on the screen (tap, swipe, type, wait) and return the screen now; no steps = look",
            scopeOf = { a -> if (looksOnly(a)) Scope.LOOK else Scope.OPERATE },
            scopeNoteZh = "不带步骤、或只有 wait 的 act 只需要 look", scopeNoteEn = "an act with no steps, or only wait steps, needs just look"),
        Cmd("note", "[文字] | before \"<步骤>\" | after \"<步骤>\" | save <段名> \"<步骤>\" | show <段名> | human on|off | reply quiet|full | hold on|off | delete <编号|before|after|段名>", "[text] | before \"<steps>\" | after \"<steps>\" | save <segment> \"<steps>\" | show <segment> | human on|off | reply quiet|full | hold on|off | delete <number|before|after|segment>",
            listOf("给当前应用记笔记（不带参数 = 列出笔记、段和前置/收尾步骤）。下次 launch 会列出来，并标明是谁、在应用哪个版本时记的。",
                "before / after：每段 act 开始前先做 before 的步骤，做完、观察完再做 after 的步骤（--bare 跳过）。",
                "例：会自己走的画面（游戏、播放器）记 before = 继续的步骤、after = 暂停的步骤，你思考时画面就停着；每次进页面都弹的窗记 before = 关掉它的步骤。",
                "save：不执行，直接存下或改写一个段（示教存的段也一样），步骤写法和 act 相同，可以用 try / if / repeat、* 和 @N；note 列出的就是段的完整步骤。",
                "human on：这个应用以后每段 act 都用仿人节奏（同 act --human）；off 关掉。",
                "reply quiet|full：按当前 agent 保存默认回复模式（默认 quiet：符合预期时给短回复，full：每次都给完整回复），不需要先打开应用。",
                "hold on|off：按当前 agent 保存默认是否开启 act --hold（默认 off，仅 Android 14+ 生效；Android 15 起，暂停正在频繁刷新的应用时，系统可能直接结束它），不需要先打开应用。"),
            listOf("keep notes for the current app (no arguments = list notes, segments and before/after steps). The next launch lists them, with who wrote each and for which app version.",
                "before / after: every act first does the before steps, and after acting and looking does the after steps (--bare skips them).",
                "e.g. for a picture that moves by itself (a game, a player) keep before = the steps that resume and after = the steps that pause, so it stands still while you think; for a popup shown on every visit keep before = the steps that close it.",
                "save: store or rewrite a segment without running it (taught lessons too); the steps are written as for act, with try / if / repeat, * and @N; note lists each segment's full steps.",
                "human on: every act on this app uses human pacing from now on (as act --human); off turns it off.",
                "reply quiet|full: save this agent's default reply mode (default quiet: short reply when expected; full: always return full four blocks); works without an open app.",
                "hold on|off: save whether this agent enables act --hold by default (default off, Android 14+ only; starting on Android 15, pausing an actively refreshing app may cause the system to kill it); works without an open app."),
            sumZh = "给当前应用记笔记、前置/收尾步骤，或设回复/暂停默认模式", sumEn = "keep notes and before/after steps for the current app, or set reply/hold defaults"),
        Cmd("events", "", "",
            listOf("取盯守（act --keep wake）看到条件时发来的事件。事件有序号，取走后在你下一次 events 时才删除（中途断了不会丢）。",
                "这个会话没有正在运行的 --keep wake 盯守时不等，直接说明。"),
            listOf("fetch events that watches (act --keep wake) sent when their condition was seen. Events are numbered and are deleted only at your next events call (nothing is lost if the connection drops in between).",
                "Without a running --keep wake watch in this session it does not wait and says so."),
            listOf(Opt("wait", Kind.DURATION, "时长", "duration", "没有事件时最多等这么久（默认不等，最长 120s）", "wait up to this long when there is none (default no wait, at most 120s)"),
                Opt("again", Kind.SWITCH, null, null, "把上一次取走的那批再给一遍", "return the previously fetched batch again")),
            scope = Scope.LOOK, screen = false),
        Cmd("task", "[任务号] | cancel <任务号> | recap", "[task id] | cancel <task id> | recap",
            listOf("查看用 --async 开始的任务：不带参数列出本会话的任务；带任务号返回它的状态，做完了就是它的完整结果；cancel 让它在下一步之前停下；recap 返回本会话走过的页面和步骤摘要（不重新读屏，最多 600 字）。"),
            listOf("look at tasks started with --async: no argument lists this session's tasks; with a task id it returns the state, and the full result once finished; cancel stops it before its next step; recap summarizes this session's pages and steps without re-reading the screen (at most 600 chars)."),
            listOf(Opt("wait", Kind.DURATION, "时长", "duration", "任务没做完时最多等这么久（最长 120s）", "wait up to this long while the task is unfinished (at most 120s)")),
            scope = Scope.LOOK, screen = false),
        Cmd("tasks", "", "",
            listOf("列出副屏上的应用任务（最前面的排第一）：包名、任务号、是不是前台。用来确认应用还在不在、有没有被系统挪走。停到停放屏（handoff park）的应用不在这里。"),
            listOf("list the app tasks on the screen now (the front one first): package, task id and whether it is in front. Use it to see whether the app is still there. Apps parked with handoff park are not on this screen and are not listed."),
            scope = Scope.LOOK),
        Cmd("title", "[文字]", "[text]",
            listOf("在悬浮窗上显示你正在做什么（最多 40 字，给用户看；不带文字 = 清除；这一轮结束时自动清除）。"),
            listOf("show what you are doing on the floating window (up to 40 characters, for the user; no text = clear; cleared when this turn ends)."),
            screen = false),
        Cmd("permissions", "", "",
            listOf("这个 agent 现在的权限：每类操作是允许、每次确认还是不允许，临时许可何时结束，哪些应用每次确认、禁止或只读，以及固定边界。任务开始前看一次，按它规划；用户问起时照此转告。手机在每条命令执行前按同一张表检查。"),
            listOf("this agent's permissions now: whether each class of action is allowed, confirmed each time or not allowed, when temporary allows end, which apps confirm every time, are blocked or read-only, and the fixed limits. Read it once before a task and plan by it; pass it on when the user asks. The phone checks every command against the same table before it runs."),
            scope = Scope.LOOK, screen = false, sumZh = "查看这个 agent 的权限设置", sumEn = "show this agent's permission settings"),
        Cmd("elevate", "<类别|档位>", "<class|level>",
            listOf("提前向用户申请更高的许可，写明原因：手机上弹出确认（通知 → 指纹），用户选择本轮任务内、今天内或永久。类别 caution/destroy/system/operate/rate，档位 careful/free/free_system。",
                "临时许可到期自动收回；支付不能申请（每次付款都单独确认）。命令遇到“不允许”的类别时也会弹出同样的确认。"),
            listOf("ask the user ahead for more permission, with your reason: the phone shows a confirmation (notification → fingerprint) and the user picks this turn, today or permanently. Classes caution/destroy/system/operate/rate, levels careful/free/free_system.",
                "A temporary permission ends by itself; payments cannot be asked for (each payment is confirmed on its own). A command that meets a Not allowed class brings up the same confirmation."),
            listOf(Opt("why", Kind.VALUE, "原因", "reason", "为什么需要（用户在确认卡上看到，最多 120 字）", "why you need it (shown on the card, up to 120 characters)"),
                Opt("for", Kind.VALUE, "round|today|always", "round|today|always", "希望的范围（默认 round；用户可以改）", "the range you hope for (default round; the user may pick another)")),
            scope = Scope.LOOK, screen = false, sumZh = "向用户申请临时或永久的更高许可（手机上指纹确认）", sumEn = "ask the user for temporary or permanent extra permission (fingerprint on the phone)", sideEffects = true),
        Cmd("done", "", "",
            listOf("这一轮做完了：副屏马上省电暂停、页面保留 10 分钟，别的会话可以接手（不 done 的话安静满 N 分钟自动结束，N 在 App 里设）。"),
            listOf("this turn is finished: the screen pauses to save power at once, the page is kept 10 minutes, and other sessions can take over (without done, it ends after N quiet minutes, set in the app).")),
        Cmd("keep", "| off <名字|all>", "| off <name|all>",
            listOf("列出 / 停止本轮之后的盯守（用 act --stop … --keep 开始）。",
                "另见 act --hold（两轮之间暂停应用）：Android 15 起，暂停正在频繁刷新的应用时，系统可能直接结束它。"),
            listOf("list / stop watches that run after this turn (started with act --stop … --keep).",
                "See also act --hold (pause between turns): starting on Android 15, pausing an actively refreshing app may cause the system to kill it."), screen = false),
        Cmd("stop", "[包名]", "[package]",
            listOf("关掉副屏上的应用。"),
            listOf("close the app on the screen."),
            listOf(Opt("force", Kind.SWITCH, null, null, "结束整个应用（主屏上的也会被结束）：手机上会请用户确认", "stop the whole app (also on the main screen): the phone asks the user to confirm")),
            scopeOf = { a -> if (a.hasFlag("force")) Scope.SYSTEM else Scope.OPERATE },
            scopeNoteZh = "--force 需要 system", scopeNoteEn = "--force needs system"),
        Cmd("release", "", "", listOf("立即释放副屏。"), listOf("release the screen now.")),
        Cmd("handoff", "in [包名] | out [包名] | park <应用> | unpark <应用>", "in [package] | out [package] | park <app> | unpark <app>",
            listOf("in：把用户主屏上的应用移到副屏接着做（返回四段；手机上会请用户确认）；out：交还到主屏，需要用户本人操作时用。",
                "handoff park <应用>：把副屏任务移到隐藏的停放屏；handoff unpark <应用>：移回副屏前台。当前目标仍在停放时，look/act 返回 APP_PARKED。轮次结束、Stop 或用户接管时清理停放任务。"),
            listOf("in: move the app from the user's main screen to the background screen and carry on (returns four blocks; the phone asks the user to confirm); out: hand it back to the main screen when the user must act.",
                "handoff park <app>: move the app task to a hidden parking display; handoff unpark <app>: return it to the foreground. Look and act return APP_PARKED if the current target is still parked. Turn end, Stop or user takeover clears parked tasks."),
            listOf(Opt("note", Kind.VALUE, "说明", "text", "out 时给用户的通知里写什么", "what the notification to the user says on out")),
            scopeOf = { a -> if (a.positional.firstOrNull()?.lowercase() == "in") Scope.SYSTEM else Scope.OPERATE },
            scopeNoteZh = "in 需要 system", scopeNoteEn = "in needs system"),
        // 1.4.0-beta13 (rainbow: agents can mute and unmute apps too, the same as the app's 应用静音 page).
        Cmd("sound", "[list] | mute <应用> | unmute <应用> | plays <应用> on|off | screen on|off", "[list] | mute <app> | unmute <app> | plays <app> on|off | screen on|off",
            listOf("应用静音：list 列出现在没声音的应用（谁静的）、手动静音的、在副屏里也出声的、副屏静音开没开；mute 让应用在所有屏幕上都不出声（直到 unmute）；unmute 恢复声音；plays 设这个应用在副屏里也出声；screen 开关副屏静音。已经在播放的声音要等应用重新播放才变。"),
            listOf("app sound: list shows the apps silent now (and by whom), the ones muted by hand, the ones that keep sound on the screen, and whether screen muting is on; mute silences an app on every screen (until unmute); unmute gives its sound back; plays lets an app keep its sound on the screen; screen turns screen muting on or off. Sound already playing changes only when the app plays again."),
            scope = Scope.SYSTEM, screen = false,
            scopeOf = { a -> if ((a.positional.firstOrNull()?.lowercase() ?: "list") == "list") Scope.LOOK else Scope.SYSTEM },
            scopeNoteZh = "list 只需 look", scopeNoteEn = "list needs look only"),
        Cmd("ask", "\"<问题>\"", "\"<question>\"",
            listOf("在手机上问用户（通知 + App 首页），等用户回答。默认 --point：用户在副屏的画面上点一下“你说的是哪个”，返回副屏坐标和那里的元素（#N）。说不清位置时用它，比文字描述准。",
                "回答的是事实，不是许可：付款等仍按手机确认走。"),
            listOf("ask the user on the phone (notification + the app's home page) and wait for the answer. Default --point: the user taps \"the one I mean\" on the screen's picture; you get the screen point and the element there (#N). Use it when a place is hard to put into words.",
                "An answer is information, not permission: payments still need the phone's confirmation."),
            listOf(Opt("point", Kind.SWITCH, null, null, "在画面上点一下（默认）", "tap once on the picture (default)"),
                Opt("area", Kind.SWITCH, null, null, "在画面上框一块，返回框和框里的元素", "drag a box on the picture; returns the box and the elements in it"),
                Opt("choose", Kind.VALUE, "甲|乙|丙", "A|B|C", "从 2–8 个选项里选一个", "choose one of 2–8 options"),
                Opt("text", Kind.SWITCH, null, null, "写一句话回答", "answer with a line of text"),
                Opt("wait", Kind.DURATION, "时长", "duration", "最多等多久（默认 50s，最长 5m；没答复是 ASK_PENDING，之后重发同一条 ask 取答案）", "how long to wait (default 50s, at most 5m; no answer is ASK_PENDING, send the same ask again later for the answer)")),
            screen = false, long = true, sumZh = "在手机上问用户，让用户在副屏画面上点出你要找的东西", sumEn = "ask the user on the phone; the user can tap what you mean on the screen's picture"),
        Cmd("teach", "\"<要演示什么>\"", "\"<what to show>\"",
            listOf("请用户在副屏上亲手做一遍（示教）：手机上弹出请求，用户接管副屏操作、做完审阅，存成这个应用的一段。返回段名和步骤；之后 act \"use 段名 参数…\" 照做。用户输入的字默认变成参数 {1} {2}…，用的时候按顺序填。",
                "适合说不清、反复失败、或用户自己更清楚怎么做的流程。先 launch 到要演示的应用，再把页面整理回起始的样子（清掉自己输入的字、计算结果、弹窗），看一眼确认再发。"),
            listOf("ask the user to do it once on the background screen (a lesson): a request appears on the phone, the user takes over the screen, does it, reviews it, and it is saved as a segment of the app. Returns the segment name and steps; then act \"use <name> <values>…\" runs it. Words the user typed become blanks {1} {2}… by default, filled in order when used.",
                "For flows that are hard to describe, keep failing, or the user knows better. launch the app to show first, put the page back where it starts (clear words you typed, a result, a dialog) and look once to check before sending."),
            listOf(Opt("name", Kind.VALUE, "名字", "name", "建议的段名（用户可以改）", "suggested segment name (the user may change it)"),
                Opt("wait", Kind.DURATION, "时长", "duration", "最多等多久（默认 50s，最长 5m；没做完是 ASK_PENDING，之后重发同一条 teach 取结果）", "how long to wait (default 50s, at most 5m; not done yet is ASK_PENDING, send the same teach again later for the result)"),
                Opt("again", Kind.SWITCH, null, null, "请用户重新演示一遍（不加时，同一条 teach 已有结果就原样返回，不会再问用户）", "ask the user to show it again (without it, a teach that already has a result returns that result and does not ask again)")),
            screen = false, long = true, sumZh = "请用户在副屏上亲手演示一遍，存成可重放的一段", sumEn = "ask the user to show it once on the screen; saved as a segment you can replay"),
        Cmd("schedule", "[\"时间\"] | off <名字|all>", "[\"when\"] | off <name|all>",
            listOf("预约你会来用手机的时间（定时任务用）：每个窗口开始前 3 分钟拉起服务、检查所选的权限来源（Shizuku 或 Root）和连接，没准备好就通知用户，结果作为事件发给本会话。不带参数列出预约和现在是否就绪。",
                "时间写法：\"07:00\"、\"07:00,19:30\"、\"weekdays 08:30\"、\"mon,wed,fri 21:00\"、\"every 2h from 08:00 to 22:00\"（间隔至少 30 分钟，最多 8 条）。"),
            listOf("book the times you will use the phone (for timed tasks): 3 minutes before each window the service is started, and the selected permission source (Shizuku or root) and the connection are checked; the user is notified if it is not ready, and the result is sent to this session as an event. With no argument: the schedules and whether it is ready now.",
                "Times: \"07:00\", \"07:00,19:30\", \"weekdays 08:30\", \"mon,wed,fri 21:00\", \"every 2h from 08:00 to 22:00\" (at least 30 minutes apart, at most 8 entries)."),
            listOf(Opt("name", Kind.VALUE, "名字", "name", "预约的名字（同名会替换；默认 w1、w2…）", "the schedule's name (the same name replaces it; default w1, w2…)"),
                Opt("for", Kind.DURATION, "时长", "duration", "每个窗口保持多久（默认 10m，最长 30m）", "how long each window stays up (default 10m, at most 30m)")),
            screen = false, sumZh = "预约定时任务要用手机的时间，到点前拉起并检查", sumEn = "book when a timed task will use the phone; it is started and checked before each time"),
        Cmd("status", "", "",
            listOf("后台操作是否可用、副屏、权限、应用限制、正在盯守的条件、排队中的命令。"),
            listOf("whether background operation works, the screen, permissions, app rules, watches running, commands in the queue."),
            scope = Scope.LOOK, screen = false),
        Cmd("apps", "[关键词]", "[keyword]", listOf("已安装应用和包名。"), listOf("installed apps and packages."), scope = Scope.LOOK, screen = false),
        Cmd("spots", "[x1,y1,x2,y2]", "[x1,y1,x2,y2]",
            listOf("本地按颜色找色块（不调模型），列出中心坐标和标注图。"), listOf("find color spots locally (no model), listing centers and a marked picture."),
            listOf(Opt("color", Kind.VALUE, "#RRGGBB", "#RRGGBB", "只找这种颜色", "only this color")), scope = Scope.LOOK),
        Cmd("look", "[x1,y1,x2,y2]", "[x1,y1,x2,y2]",
            listOf("主动要图：不带参数是现在的画面；带区域是放大那一块；带 --last/--from/--count 是回看录像（每个动作前一张、圈出点的位置）。可以要更清楚、换排法、关掉标注、翻页。每次最多 8 张图、合计约 3 MB，多的用 --page 翻。"),
            listOf("ask for pictures: no argument is the screen now; a region zooms into it; --last/--from/--count looks back at the recording (one frame before each action, touches circled). Ask for more detail, another layout, no marks, or the next page. At most 8 pictures, about 3 MB a reply; --page for more."),
            lookOpts(), scope = Scope.LOOK),
        Cmd("frames", "", "",
            listOf("look --last … 的旧写法：回看录像，默认是上一次返回的【这段时间】。"),
            listOf("old form of look --last …: look back at the recording, by default the [Meanwhile] of the last reply."),
            lookOpts(), scope = Scope.LOOK),
        Cmd("zoom", "x1,y1,x2,y2", "x1,y1,x2,y2",
            listOf("look x1,y1,x2,y2 的旧写法：放大看一块区域（图边上标着副屏坐标）。"), listOf("old form of look x1,y1,x2,y2: zoom into a region (screen coordinates on the picture's edges)."),
            listOf(Opt("scale", Kind.VALUE, "倍数", "factor", "放大倍数（默认按区域大小定）", "zoom factor (default follows the region size)"),
                detailOpt, marksOpt), scope = Scope.LOOK),
        Cmd("collect", "", "",
            listOf("从当前位置往下一页页滚，收集每一条（一张卡片或一行列表算一条，去重）。上面还有内容时会说；--from-top 先回到顶部。"), listOf("scroll down page by page from here and collect every item (a card or list row is one item, deduplicated). Says when there is more above; --from-top goes to the top first."),
            listOf(Opt("pages", Kind.VALUE, "页数", "count", "最多翻几页（默认 8，最多 40）", "pages to go through at most (default 8, at most 40)"),
                Opt("from-top", Kind.SWITCH, null, null, "先滚回页面顶部再开始收集", "scroll back to the top of the page before collecting"),
                Opt("until", Kind.VALUE, "文字", "text", "看到这段文字就停", "stop when this text is seen")), long = true),
        Cmd("fix", "[code]", "[code]",
            listOf("修复 status 里列出的问题，并把被留下静音的应用恢复声音；每项修完再检查一遍，fixed 是检查的结果。会改系统设置或在主屏打开设置页的步骤：手机上会请用户确认。"),
            listOf("fix the problems listed in status and give sound back to apps left silent; each is checked again afterwards and fixed is that check. Steps that change system settings or open a settings page on the main screen: the phone asks the user to confirm."),
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
            "agent 不需要连接 adb 或配置无线调试：Agent-VScreen 使用用户选定的 Shizuku 或 Root。不要去连 adb、配无线调试或改 adb 密钥。没有副屏时直接 launch，会自动建屏。\n\n" +
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
            "Agents do not need adb or wireless debugging: Agent-VScreen uses Shizuku or root, whichever the user chose. Don't connect adb, set up wireless debugging or change adb keys. With no screen, just launch: one is created automatically.\n\n" +
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
            "- 用户点“接管”时动作会等待（最多 15 秒，之后 USER_TAKEOVER）。接管只由用户结束，你这一轮结束了副屏也留在用户手上、不会被释放：用 vscreen events --wait 等 handback 事件再继续，不要 release 或重新 launch。交还后第一个改动画面的命令会被拒一次（REREAD_REQUIRED），先 act（不带步骤）看一眼再重发。\n" +
            "- 用户在悬浮窗点“停止 AI”后，所有操作返回 USER_STOPPED：停下来，把返回里“怎么解除”那句转告用户，不要重试。\n" +
            "- 用户可以把应用设成“禁止操作”（APP_BLOCKED）、“只读”（APP_READ_ONLY）或“每次确认”（手机上确认）。这是用户的决定，不要绕过，也不要换个入口进同一个应用。\n" +
            "- 支付，以及这个 agent 的权限表里设为“问我”的类别（不可撤销操作、敏感操作如 stop --force、handoff in 等）：由用户在手机上确认（或用户事先给这个令牌开了临时免确认）。设为“不允许”的类别不会执行。你自己加任何参数都不能代替用户确认。\n" +
            "- 涉及隐私（私信、通讯录、相册、余额）的步骤，先问用户，答复后再做。\n" +
            "- 一个副屏同一时间只属于一个会话（请求头 X-VScreen-Session，默认 default）。别的会话在用时返回 BUSY，写明归谁、多久后自动让出。\n" +
            "- --keep 在你不在时替你盯：告诉用户你在盯什么、盯多久、看到后会怎样、怎么停（keep off）。",
        "After a user takeover or a restart: the [Now] of the next launch or act counts as a fresh look; no other command is needed (there is no resume).\n" +
            "The full command-line output is also saved as last.txt under VSCREEN_SHOT_DIR (default TMPDIR/vscreen-shots/last.txt); read it when output is too long or redirection goes wrong.\n\n" +
            "Rules:\n" +
            "- When the background screen is unavailable, commands fail; they never run on the main screen instead.\n" +
            "- Commands run one at a time, in a queue: two commands never move the same screen at once. After 60 s in the queue a command returns QUEUE_TIMEOUT and has not run.\n" +
            "- While the user has taken over, actions wait (up to 15 s, then USER_TAKEOVER). Only the user ends a takeover: when your turn ends the screen stays with the user and is not released; wait for the handback event with vscreen events --wait, don't release or launch again. After the hand-back the first command that changes the screen is refused once (REREAD_REQUIRED): look with act (no steps), then send it again.\n" +
            "- After the user taps \"Stop AI\" on the floating window, every action returns USER_STOPPED: stop, pass on the \"how to undo\" line to the user, don't retry.\n" +
            "- The user can set an app to \"blocked\" (APP_BLOCKED), \"read-only\" (APP_READ_ONLY) or \"confirm every time\" (confirmed on the phone). That is the user's decision: don't get around it, and don't enter the same app another way.\n" +
            "- Payments, and the classes this agent's permission table sets to \"Ask me\" (irreversible or sensitive actions such as stop --force and handoff in), are confirmed by the user on the phone (or the user gave this token a temporary no-confirmation period). Classes set to \"Not allowed\" are never done. No option you add can stand in for the user's confirmation.\n" +
            "- Steps touching privacy (messages, contacts, photos, balances): ask the user first and act after the answer.\n" +
            "- A screen belongs to one session at a time (header X-VScreen-Session, default \"default\"). When another session is using it you get BUSY, with whose it is and when it frees up.\n" +
            "- --keep watches for you while you are away: tell the user what you watch, for how long, what happens when seen, and how to stop it (keep off).")

    /** `help all`. */
    fun fullHelp(): String = buildString {
        append(head).append('\n')
        commands.forEach { c -> c.help().forEach { append(it).append('\n') } }
        append("  guide\n        ").append(tr("用法速查（一页）。", "the one-page quick usage.")).append('\n')
        append("  help [all|codes|<").append(tr("主题", "topic")).append(">|<").append(tr("命令", "command")).append(">]\n        ").append(tr("完整说明 / 代码表 / 主题 / 单条命令。", "the full reference / the code table / a topic / one command.")).append('\n')
        append('\n').append(rules)
    }

    /** `help` (short). */
    /** Commands kept only as old forms of another (listed once, at the end of the quick help). */
    internal val OLD_FORMS = mapOf("zoom" to "look x1,y1,x2,y2", "frames" to "look --last …")

    /**
     * `help` (short). 1.4.0-beta2: generated from the command table and the step / condition kinds, so a new command,
     * option, step or condition is in it without anyone remembering to add a line.
     */
    fun quickHelp(): String = buildString {
        append(tr("vscreen — Agent-VScreen：在看不见的副屏里操作应用，不占用户主屏（完整说明：help all；单条：help act；代码表：help codes）",
            "vscreen — Agent-VScreen: operate apps on an invisible background screen without taking the user's screen (full guide: help all; one command: help act; codes: help codes)")).append('\n')
        for (c in commands) {
            if (c.name in OLD_FORMS) continue
            val use = (c.name + " " + c.positional).trim()
            append("  ").append(use.padEnd(24)).append("  ").append(c.summary.substringBefore(tr("。", ". ")).trimEnd('.', '。')).append('\n')
            if (c.name == "act") {
                append(tr("        步骤：", "        steps: ")).append(Steps.STEPS_HELP).append('\n')
                append(tr("        目标：x y | #N（【现在】列表的编号）| 画面上的字；动作后可加 until 条件：", "        target: x y | #N (number in the [Now] list) | text on the screen; after an action add until <condition>: ")).append(Steps.CONDS_HELP).append('\n')
                append("        ").append(c.allOpts.filter { !it.deprecated }.joinToString("  ") { it.usage() }).append('\n')
            }
        }
        append(tr("  旧写法：", "  old forms: ")).append(OLD_FORMS.entries.joinToString(tr("；", "; ")) { "${it.key} = ${it.value}" }).append('\n')
        append(tr("成功与否看退出码 / ok：0 整段按预期做完；1 停在某一步或被拒绝（【注意】里的代码说明原因，help codes 查怎么办）；2 命令写错。\n" +
            "一步没达到预期（until 没等到、字没输进去、找不到或有歧义）这段就停在那一步，后面的不做，【注意】里写明现在的画面。\n" +
            "输出很长时完整结果也存在截图目录的 last.txt（路径见 help all），可以用 head / sed 分段看。",
            "Success is in the exit code / ok: 0 the whole stretch went as expected; 1 it stopped at a step or was refused (the code in [Notes] says why, help codes says what to do); 2 the command was written wrong.\n" +
            "If a step does not reach what it expects (until never came, text was not typed in, not found or ambiguous), the stretch stops at that step, skips the rest, and [Notes] describes the screen now.\n" +
            "When output is long, the full result is also saved as last.txt in the screenshot folder (path in help all); read it in parts with head / sed."))
    }

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
            if (c.scopeNote.isNotEmpty()) append(tr("（", " (")).append(c.scopeNote).append(tr("）", ")"))
            if (c.screen) append(tr(" · 在副屏队列里执行", " · runs in the screen queue"))
            append("\n\n")
        }
    }

    /** JSON schema of one command's arguments as an MCP tool input: `args` (the positional part) plus one property per option. */
    fun inputSchema(c: Cmd): JSONObject {
        val props = JSONObject()
        if (c.positional.isNotEmpty()) props.put("args", JSONObject().put("type", "string").put("description",
            tr("一个字符串（不是数组），写法：", "ONE string (not an array), written as: ") + c.positional.trim('[', ']').replace("\"", "") +
                (if (c.positional.startsWith("[")) tr("。可以不给", ". Optional") else "")))
        for (o in c.allOpts.filter { !it.deprecated }) props.put(o.name.replace('-', '_'), JSONObject()
            .put("type", if (o.kind == Kind.SWITCH) "boolean" else "string").put("description", o.text))
        props.put("session", JSONObject().put("type", "string").put("description", tr("可选：会话名", "optional: session name")))
        return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray())
    }

    /** The argv of a structured call (MCP typed tool / HTTP `{"command":"act","args":…,"options":{…}}`). */
    private fun singleTextArg(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"') && '"' !in t.substring(1, t.length - 1)) ||
                (t.startsWith('\'') && t.endsWith('\'') && '\'' !in t.substring(1, t.length - 1)))) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    fun argvOf(c: Cmd, args: String?, options: JSONObject?): List<String> {
        val out = arrayListOf(c.name)
        args?.takeIf { it.isNotBlank() }?.let { a ->
            when (c.name) {
                "act", "title", "launch" -> out += a
                "ask", "teach" -> out += singleTextArg(a)
                else -> out += com.kilon.agentvscreen.api.ApiServer.splitCommand(a)
            }
        }
        if (options != null) for (o in c.allOpts) {
            val k = listOf(o.name, o.name.replace('-', '_')).firstOrNull { options.has(it) } ?: continue
            if (o.kind == Kind.SWITCH) {
                if (options.optBoolean(k, false)) out += "--${o.name}"
            } else {
                val v = options.opt(k)
                if (v is JSONArray) {
                    for (idx in 0 until v.length()) {
                        v.optString(idx).takeIf { it.isNotEmpty() && it != "null" }?.let { out += "--${o.name}"; out += it }
                    }
                } else {
                    v?.toString()?.takeIf { it.isNotEmpty() && it != "null" }?.let { out += "--${o.name}"; out += it }
                }
            }
        }
        return out
    }
}


private val detailOpt = CommandSpec.Opt("detail", CommandSpec.Kind.VALUE, "清晰度", "detail", "low、normal（默认）或 high", "low, normal (default) or high")
private val marksOpt = CommandSpec.Opt("marks", CommandSpec.Kind.VALUE, "on|off", "on|off", "图上的标尺和点击圈（默认 on）", "ruler and touch circles on the picture (default on)")
private fun lookOpts() = listOf(
    CommandSpec.Opt("last", CommandSpec.Kind.DURATION, "时长", "duration", "最近这么久（如 30s、2m）", "this long up to now (e.g. 30s, 2m)"),
    CommandSpec.Opt("from", CommandSpec.Kind.VALUE, "时间", "time", "从多久以前（20s）或钟点（14:03:10）开始", "start this long ago (20s) or at a clock time (14:03:10)"),
    CommandSpec.Opt("to", CommandSpec.Kind.VALUE, "时间", "time", "到多久以前或钟点为止（默认现在）", "end this long ago or at a clock time (default now)"),
    CommandSpec.Opt("count", CommandSpec.Kind.VALUE, "张数", "count", "最多几帧（默认 12，最多 96）", "at most this many frames (default 12, at most 96)"),
    CommandSpec.Opt("every", CommandSpec.Kind.DURATION, "间隔", "interval", "按固定间隔取（如 0.5s），和上一张一样的不重复", "take one every interval (e.g. 0.5s), repeats left out"),
    CommandSpec.Opt("page", CommandSpec.Kind.VALUE, "页", "page", "超过 8 张图时看第几页", "which page when there are more than 8 pictures"),
    detailOpt,
    CommandSpec.Opt("layout", CommandSpec.Kind.VALUE, "排法", "layout", "auto（默认）、single（一帧一张）、strip（一行 6 帧）或 3x4", "auto (default), single (one frame each), strip (6 in a row) or 3x4"),
    marksOpt,
    CommandSpec.Opt("big", CommandSpec.Kind.SWITCH, null, null, "同 --detail high", "same as --detail high", deprecated = true))
