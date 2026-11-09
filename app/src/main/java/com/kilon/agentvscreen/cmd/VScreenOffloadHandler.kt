package com.kilon.agentvscreen.cmd
import com.kilon.agentvscreen.I18n

import com.kilon.agentvscreen.tr

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenStore
import org.json.JSONObject

/**
 * [vscreen] `vscreen` — the agent's background-screen tool (v49).
 *
 * One verb does the work: `act` runs a segment of steps on the chat's background screen and returns four fixed
 * blocks (what was done / what happened meanwhile / the screen now / attention). Realtime apps whose pause and
 * resume steps were written down (`note pause|resume`) are resumed at the start of a segment and paused at its end,
 * so the agent thinks while the app stands still. The phone reports facts only: no verdicts on progress, success
 * or meaning. Everything runs on the virtual display owned by the calling chat; there is no main-screen fallback.
 *
 * Files: VScreenAct (act, launch), VScreenSteps (the step language), VScreenReport (the four blocks),
 * VScreenOpenCommands (launch internals, handoff, stop, release), VScreenNotes (note, keep),
 * VScreenHelpers (spots, zoom, collect), VScreenScreenCommands (status, apps, fix).
 */
class VScreenOffloadHandler(internal val context: Context) : NativeOffloadHandler {

    companion object {
        internal const val TAG = "VScreenOffload"
        const val DEFAULT_SESSION = "default"
        internal const val SHOT_DIR = "vscreen"

        const val QUICK_HELP_ZH = """vscreen — Agent-VScreen：在看不见的副屏里操作应用，不占用户主屏（完整说明：help all；单条：help act）
  launch 应用名            打开应用，返回四段（做了什么 / 这段时间 / 现在 / 注意）
  act "步骤; 步骤; …"      做一段操作，返回四段；act 不带步骤 = 看一眼
        步骤：tap 目标 | press 目标 | swipe x1 y1 x2 y2 | drag x1 y1 x2 y2 … | scroll down | type 文字 | key back | wait 条件 | use 段名
        目标：x y | #N（【现在】列表的编号）| 画面上的字；动作后可加 until 条件（text:字 gone:字 change still color:x,y=#RRGGBB app:名）
        --stop 条件  --watch 2s  --keep notify|wake  --save 段名  --confirmed
  note [文字] | note pause|resume "步骤" | note realtime on|off | note delete 编号
  keep | keep off 名字      查看 / 停止本轮之后的盯守
  events [--wait 30s]       取盯守（--keep wake）发来的事件
  title 文字 | done         告诉用户你在做什么（悬浮窗上显示）| 这一轮做完了（副屏让出，页面保留）
  stop | release | handoff in|out | status | apps 关键词 | spots | zoom 区域 | collect | fix
一步没达到预期（until 没等到、字没输进去）这段就停在那一步，后面的不做，【注意】里写明现在的画面。
输出很长时完整结果也存在截图目录的 last.txt（路径见 help all），可以用 head / sed 分段看。"""

        const val HELP_ZH = """vscreen — Agent-VScreen 后台操作：在看不见的副屏里操作其他应用，不占用户主屏。截图由你（调用方的模型）看，手机只报事实。
不需要 adb 或无线调试：特权走 Agent-VScreen 自己的 Shizuku 通道。不要去连 adb、配无线调试或改 adb 密钥。没有副屏时直接 launch，会自动建屏。

返回的四段（launch / act / handoff in）：
  【做了什么】 每一步做了什么、等到了什么或等到时间没等到、哪个停止条件触发了（只有事实，没有“成功/失败”的判断）
  【这段时间】 从你上次拿到结果到现在（包括你思考的时间）：出现/消失/出现又消失的字，画面哪里变了多久；有变化时附拼图
  【现在】     前台界面、副屏尺寸、截图路径（边上标着副屏坐标）和编号元素：#N “字” (x,y) 可点/可输入/…；同一页上只列新出现或变了的元素（编号不变），不带步骤的 act 列出全部
  【注意】     主屏被动过、付费按钮没点、用户接管、应用限制、失败原因；没有就是“无”

命令：
  launch <应用名|包名> [--landscape|--portrait] [--restart]
        打开应用（找不到就 apps 关键词）。副屏方向按应用声明的方向建；被系统缩放会自动处理。
        返回四段；【做了什么】里还会列出你以前给这个应用记的 note、存的段、暂停/继续步骤。
        --restart 冷启动；--landscape / --portrait 指定副屏方向
  act ["步骤; 步骤; …"] [--stop "<条件>"] [--watch 时长] [--paused] [--keep notify|wake] [--for 时长] [--save 名字] [--confirmed]
        做一段操作（最多 40 步，用 ; 或换行分开），返回四段。不带步骤 = 看一眼（等画面稳定，最多 3 秒）。
        步骤：
          tap 目标 | press 目标 [时长，如 1.2s；纯数字是毫秒] | swipe x1 y1 x2 y2 [时长] | drag x1 y1 x2 y2 [x3 y3 …] [时长]
          scroll up|down|left|right [#N | x y] | type 文字 | type+ 文字（追加） | key back|enter|home|tab|del|search|… | back | enter
          wait <条件> [or <条件>] [最长时长] | wait 2s | use 段名
          目标 = x y | #N（上次【现在】列表里的编号）| 画面上的字（先找完全一样的，再找包含的；只写一个数＝点画面上的这个数字）
        一步没达到预期就停在这一步，后面的步骤不做（【注意】里写现在的画面）：until 到时间没等到；type 没有获得焦点的输入框、
        或输入后框里没有你的字、或无障碍没开确认不了。type 前先点输入框，【现在】里它标“可输入 有焦点”
        每个动作后可加 until <条件> [or <条件>] [最长时长，默认 5s]；没写就等画面稳定（最多 3 秒；实时应用不等）。
        条件：text:字 | gone:字 | change[:x1,y1,x2,y2] | still[:区域] | stable | color:x,y=#RRGGBB[~容差] | app:包名或界面名
        --stop 条件：整段一直盯着，满足就停下，后面的步骤不做
        --watch 时长：步骤做完再看这么久（实时画面）；实时应用默认看 1s
        --paused：记了暂停/继续步骤的应用，这一段不继续也不暂停（画面保持暂停时看/点菜单）
        --keep notify|wake [--for 时长]：本轮结束后继续盯 --stop 的条件（默认 1 小时，最长 12 小时），
        看到就通知用户（notify）或发一条事件给调用方（wake，用 events 取），然后结束
        --save 名字：把这段步骤存成这个应用的段，以后 use 名字
        --confirmed：付费/不可撤销的按钮，用户在本轮明确同意后才加（否则不点，【注意】里是 CONFIRM_REQUIRED）
        例：act "tap 搜索; type 耳机; key enter until text:综合"
            act "drag 300 900 800 900" --watch 2s
            act --stop "text:到货" --keep notify --for 2h
  note [文字] | note pause "<步骤>" | note resume "<步骤>" | note realtime on|off | note delete <编号|pause|resume|段名>
        给当前应用记笔记（不带参数 = 列出笔记、段和暂停/继续步骤）。下次 launch 会列出来。
        暂停和继续都记了：之后每段 act 先做继续步骤，做完、观察完再做暂停步骤（实时游戏变成回合制）
  events [--wait 时长]
        取盯守（act --keep wake）看到条件时发来的事件；--wait 最多等这么久（默认不等，最长 120s）。
        这个会话没有正在运行的 --keep wake 盯守时不等，直接说明
  title [文字]
        在悬浮窗上显示你正在做什么（最多 40 字，给用户看；不带文字 = 清除；这一轮结束时自动清除）
  done
        这一轮做完了：副屏马上省电暂停、页面保留 10 分钟，别的会话可以接手（不 done 的话安静满 N 分钟自动结束，N 在 App 里设）
  keep | keep off <名字|all>
        列出 / 停止本轮之后的盯守（用 act --stop … --keep 开始）
  stop [包名] [--force]
        关掉副屏上的应用（--force 结束整个应用，主屏上的也会被结束：先征得用户同意）
  release
        立即释放副屏
  handoff in [包名] | handoff out [包名] [--note 说明]
        in：把用户主屏上的应用移到副屏接着做（返回四段）；out：交还到主屏，需要用户本人操作时用
  status
        后台操作是否可用、副屏、权限、应用限制、正在盯守的条件
  apps [关键词]
        已安装应用和包名
  spots [x1,y1,x2,y2] [--color #RRGGBB]
        本地按颜色找色块（不调模型），列出中心坐标和标注图
  zoom x1,y1,x2,y2 [--scale 倍数]
        放大看一块区域（图边上标着副屏坐标）
  collect [--pages 页数] [--until 文字]
        从当前位置往下一页页滚，收集每一行文字（去重）
  fix [code] [--open]
        修复 status 里列出的问题（会改系统设置或在主屏打开设置页：先征得用户同意）

用户接管或重启之后：下一次 launch 或 act 返回的【现在】就算重新看过画面，不需要别的命令（没有 resume）。
用户在悬浮窗上点“停止”时，只有用户能交还；关掉悬浮窗只是释放副屏，之后直接 launch。
命令行的完整输出也存在 VSCREEN_SHOT_DIR 下的 last.txt（默认 TMPDIR/vscreen-shots/last.txt），输出太长或重定向出问题时读这个文件。

规则：
- 副屏不可用时命令直接失败，不会改到主屏执行。
- 用户点“接管”时动作会等待（最多 15 秒，之后 USER_TAKEOVER）；交还后第一个 act 会先返回现在的画面（REREAD_REQUIRED），看清再继续。
- 用户在悬浮窗点“停止 AI”后，所有操作返回 USER_STOPPED（写明几点停的）：停下来，把返回里“怎么解除”那句转告用户（通知栏“允许继续”/悬浮窗“交还 AI”/App 首页），不要重试。用户只是关掉或收起悬浮窗不会停你。
- 用户可以把应用设成“禁止操作”（APP_BLOCKED）或“只读”（APP_READ_ONLY：能看、滚动、返回）。这是用户的决定，不要绕过，也不要换个入口进同一个应用。
- 涉及隐私（私信、通讯录、相册、余额）或高风险（支付、转账、发送、删除、授权、登录）的步骤，先问用户，答复后再做。
- fix 会改系统设置，先征得用户同意。
- 一个副屏同一时间只属于一个会话（请求头 X-VScreen-Session，默认 default）。别的会话在用时返回 BUSY，写明归谁、多久后自动让出；
  那个会话 done 或 release 后，或它安静满 N 分钟后，你就能接手（页面保留）。
- --keep 在你不在时替你盯：告诉用户你在盯什么、盯多久、看到后会怎样、怎么停（keep off）。"""

        const val QUICK_HELP_EN = """vscreen — Agent-VScreen: operate apps on an invisible background screen without taking the user's screen (full guide: help all; one command: help act)
  launch <app>              open an app, returns four blocks (Done / Meanwhile / Now / Notes)
  act "step; step; …"       do a stretch of steps, returns four blocks; act with no steps = take a look
        steps: tap <target> | press <target> | swipe x1 y1 x2 y2 | drag x1 y1 x2 y2 … | scroll down | type <text> | key back | wait <condition> | use <segment>
        target: x y | #N (number in the [Now] list) | text on the screen; after an action add until <condition> (text:word gone:word change still color:x,y=#RRGGBB app:name)
        --stop <condition>  --watch 2s  --keep notify|wake  --save <segment>  --confirmed
  note [text] | note pause|resume "steps" | note realtime on|off | note delete <number>
  keep | keep off <name>    list / stop watches that run after this turn
  events [--wait 30s]       fetch events sent by watches (--keep wake)
  title <text> | done       tell the user what you are doing (shown on the floating window) | this turn is finished (screen freed, page kept)
  stop | release | handoff in|out | status | apps <keyword> | spots | zoom <region> | collect | fix
If a step does not reach what it expects (until never came, text was not typed in), the stretch stops at that step, skips the rest, and [Notes] describes the screen now.
When output is long, the full result is also saved as last.txt in the screenshot folder (path in help all); read it in parts with head / sed."""

        const val HELP_EN = """vscreen — Agent-VScreen background operation: operate other apps on an invisible background screen without taking the user's screen. You (the caller's model) look at the screenshots; the phone only reports facts.
No adb or wireless debugging needed: privileges go through Agent-VScreen's own Shizuku channel. Don't connect adb, set up wireless debugging or change adb keys. With no screen, just launch: one is created automatically.

The four blocks returned (launch / act / handoff in):
  [Done]       what each step did, what was waited for and whether it came or time ran out, which stop condition fired (facts only, no "success/failure" verdicts)
  [Meanwhile]  from your last result until now (including your thinking time): text that appeared / disappeared / flashed, where the picture changed and for how long; a contact sheet when it changed
  [Now]        front screen, screen size, screenshot path (screen coordinates on the edges) and numbered elements: #N "text" (x,y) tappable/input/…; on the same page only new or changed elements are listed (numbers stay), act with no steps lists all
  [Notes]      main screen touched, payment button not tapped, user takeover, app rules, causes of failure; "none" when there are none

Commands:
  launch <app name|package> [--landscape|--portrait] [--restart]
        open an app (if not found, apps <keyword>). The screen orientation follows what the app declares; system scaling is handled automatically.
        Returns four blocks; [Done] also lists the notes, saved segments and pause/resume steps you kept for this app.
        --restart cold start; --landscape / --portrait choose the screen orientation
  act ["step; step; …"] [--stop "<condition>"] [--watch <duration>] [--paused] [--keep notify|wake] [--for <duration>] [--save <name>] [--confirmed]
        do a stretch of steps (up to 40, separated by ; or newlines), returns four blocks. No steps = take a look (waits for the picture to settle, up to 3 s).
        steps:
          tap <target> | press <target> [duration, e.g. 1.2s; a bare number is ms] | swipe x1 y1 x2 y2 [duration] | drag x1 y1 x2 y2 [x3 y3 …] [duration]
          scroll up|down|left|right [#N | x y] | type <text> | type+ <text> (append) | key back|enter|home|tab|del|search|… | back | enter
          wait <condition> [or <condition>] [max duration] | wait 2s | use <segment>
          target = x y | #N (number in the last [Now] list) | text on the screen (exact match first, then contains; a single number = tap that number on the screen)
        A step that does not reach what it expects stops the stretch there and skips the rest ([Notes] describes the screen now): until timed out; type found no focused input box,
        or your text is not in the box after typing, or accessibility is off so it can't be confirmed. Tap the input box before type; in [Now] it is marked "input focused"
        After each action you may add until <condition> [or <condition>] [max duration, default 5s]; without it the picture is awaited to settle (up to 3 s; not for realtime apps).
        conditions: text:<word> | gone:<word> | change[:x1,y1,x2,y2] | still[:region] | stable | color:x,y=#RRGGBB[~tolerance] | app:<package or screen name>
        --stop <condition>: watched for the whole stretch; when met it stops and skips the rest
        --watch <duration>: keep looking this long after the steps (realtime picture); realtime apps look 1s by default
        --paused: for apps with saved pause/resume steps, this stretch neither resumes nor pauses (look at / tap menus while paused)
        --keep notify|wake [--for <duration>]: after this turn keep watching the --stop condition (default 1 hour, at most 12 hours);
        when seen, notify the user (notify) or send the caller an event (wake, fetched with events), then end
        --save <name>: save these steps as a segment of this app, later: use <name>
        --confirmed: for payment / irreversible buttons, add only after the user clearly agreed in this turn (otherwise not tapped; [Notes] says CONFIRM_REQUIRED)
        e.g. act "tap Search; type headphones; key enter until text:All"
             act "drag 300 900 800 900" --watch 2s
             act --stop "text:In stock" --keep notify --for 2h
  note [text] | note pause "<steps>" | note resume "<steps>" | note realtime on|off | note delete <number|pause|resume|segment>
        keep notes for the current app (no arguments = list notes, segments and pause/resume steps). The next launch lists them.
        With both pause and resume saved: every act first does the resume steps, then after acting and looking does the pause steps (a realtime game becomes turn-based)
  events [--wait <duration>]
        fetch events that watches (act --keep wake) sent when their condition was seen; --wait waits up to this long (default no wait, at most 120s).
        Without a running --keep wake watch in this session it does not wait and says so
  title [text]
        show what you are doing on the floating window (up to 40 characters, for the user; no text = clear; cleared when this turn ends)
  done
        this turn is finished: the screen pauses to save power at once, the page is kept 10 minutes, and other sessions can take over (without done, it ends after N quiet minutes, set in the app)
  keep | keep off <name|all>
        list / stop watches that run after this turn (started with act --stop … --keep)
  stop [package] [--force]
        close the app on the screen (--force stops the whole app, also on the main screen: ask the user first)
  release
        release the screen now
  handoff in [package] | handoff out [package] [--note <text>]
        in: move the app from the user's main screen to the background screen and carry on (returns four blocks); out: hand it back to the main screen when the user must act
  status
        whether background operation works, the screen, permissions, app rules, watches running
  apps [keyword]
        installed apps and packages
  spots [x1,y1,x2,y2] [--color #RRGGBB]
        find color spots locally (no model), listing centers and a marked picture
  zoom x1,y1,x2,y2 [--scale <factor>]
        zoom into a region (screen coordinates on the picture's edges)
  collect [--pages <count>] [--until <text>]
        scroll down page by page from here and collect every line of text (deduplicated)
  fix [code] [--open]
        fix the problems listed in status (changes system settings or opens a settings page on the main screen: ask the user first)

After a user takeover or a restart: the [Now] of the next launch or act counts as a fresh look; no other command is needed (there is no resume).
When the user taps "Stop" on the floating window, only the user can hand control back; closing the floating window only releases the screen, then just launch.
The full command-line output is also saved as last.txt under VSCREEN_SHOT_DIR (default TMPDIR/vscreen-shots/last.txt); read it when output is too long or redirection goes wrong.

Rules:
- When the background screen is unavailable, commands fail; they never run on the main screen instead.
- While the user has taken over, actions wait (up to 15 s, then USER_TAKEOVER); the first act after the hand-back returns the screen now (REREAD_REQUIRED): look, then carry on.
- After the user taps "Stop AI" on the floating window, every action returns USER_STOPPED (with the time): stop, and pass on the "how to undo" line to the user (notification "Allow again" / floating window "Hand back to AI" / app home page); don't retry. The user merely closing or folding the floating window does not stop you.
- The user can set an app to "blocked" (APP_BLOCKED) or "read-only" (APP_READ_ONLY: look, scroll, back). That is the user's decision: don't get around it, and don't enter the same app another way.
- Steps touching privacy (messages, contacts, photos, balances) or high risk (paying, transferring, sending, deleting, authorizing, logging in): ask the user first and act after the answer.
- fix changes system settings: ask the user first.
- A screen belongs to one session at a time (header X-VScreen-Session, default "default"). When another session is using it you get BUSY, with whose it is and when it frees up;
  after that session's done or release, or N quiet minutes, you can take over (page kept).
- --keep watches for you while you are away: tell the user what you watch, for how long, what happens when seen, and how to stop it (keep off)."""

        val QUICK_HELP: String get() = if (I18n.en) QUICK_HELP_EN else QUICK_HELP_ZH
        val HELP: String get() = if (I18n.en) HELP_EN else HELP_ZH

        /** Last activity observed per display (status shows it). */
        internal val lastActivity = HashMap<Int, String>()
        /** Per chat: when its last four-block return was made (epoch ms) — the start of the next 【这段时间】. */
        internal val lastReturn = HashMap<String, Long>()
        /** Per chat: the element list of its last 【现在】 (what #N means). */
        internal val lastElements = HashMap<String, ElSet>()

        /** Options every command takes (help, output shaping). */
        internal val GLOBAL_OPTIONS: Set<String> = setOf("help") + OffloadOutput.OUTPUT_FLAGS
        private val optionCache = HashMap<String, Set<String>>()

        /** Commands that do not look at or touch the app on the screen (no app-rule check). */
        internal val RULE_FREE = setOf("status", "apps", "release", "stop", "fix", "help", "handoff", "note", "keep", "launch", "act", "events", "done", "title")

        /** Drop everything remembered about [displayId] (called when BackgroundOperation releases it). */
        internal fun forgetDisplay(displayId: Int) {
            synchronized(lastActivity) { lastActivity.remove(displayId) }
            com.kilon.agentvscreen.core.VScreenPerception.stop(displayId)
            com.kilon.agentvscreen.core.VScreenRecorder.stop(displayId)
        }

        init { BackgroundOperation.onScreenReleased { forgetDisplay(it) } }
    }

    /** `help <命令>`: that command's lines from [HELP] (all of them for `help all` or an unknown name). */
    internal fun helpFor(cmd: String?): String {
        if (cmd == null || cmd == "all") return HELP
        return helpLines(cmd, HELP)?.joinToString("\n") ?: (tr("没有叫 $cmd 的命令。", "There is no command called $cmd.") + "\n\n$QUICK_HELP")
    }

    /** [cmd]'s lines in [help]; null when it has none. */
    private fun helpLines(cmd: String, help: String): List<String>? {
        val out = ArrayList<String>()
        var take = false
        for (l in help.lines()) {
            val starts = l.startsWith("  $cmd ") || l == "  $cmd"
            if (starts) { take = true; out += l; continue }
            if (take) { if (l.startsWith("        ")) out += l else take = false }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Nothing a command does may take the app down: any throwable becomes an error result. */
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult = try {
        handleCommand(request)
    } catch (t: Throwable) {
        AppLogger.warning(TAG, "vscreen command crashed: ${t.javaClass.simpleName}: ${t.message} / ${t.cause?.message}")
        errText("INTERNAL_ERROR", run { val why = "${t.javaClass.simpleName}: ${(t.cause?.message ?: t.message ?: "").take(300)}"; tr("工具内部错误（$why），命令没有执行。请把这段报错发给开发者", "Internal tool error ($why); the command did not run. Please send this error to the developer") })
    }

    private fun handleCommand(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val sub = argv.firstOrNull()
        if (sub == null || sub == "--help" || sub == "-h") return NativeOffloadResult(0, QUICK_HELP + "\n")
        if (sub == "help") return NativeOffloadResult(0, helpFor(argv.getOrNull(1)) + "\n")
        if (argv.drop(1).contains("--help")) return NativeOffloadResult(0, helpFor(sub) + "\n")
        val sid = request.sessionId?.takeIf { it.isNotBlank() } ?: DEFAULT_SESSION
        takeAdjusted()
        return try {
            val rest = VScreenOptions.normalize(sub, argv.drop(1))
            checkOptions(sub, rest)
            val args = VScreenOptions.args(sub, rest)
            BackgroundOperation.refreshStatus()
            enforceRules(sub)
            dispatch(sub, args, sid)
        } catch (e: ActionError) {
            errText(e.code, e.message ?: e.code)
        } catch (e: OffloadArgs.BadOption) {
            errText("INVALID_ARGS", e.message ?: tr("参数不对", "bad arguments"))
        } catch (t: Exception) {
            AppLogger.warning(TAG, "$sub failed: ${t.message}")
            errText("OPERATION_FAILED", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * An option this command does not have is refused (with the nearest right one), a switch given a value and an
     * option missing its value are refused: nothing runs, so a typo is never mistaken for "it worked".
     */
    internal fun checkOptions(sub: String, rest: List<String>) {
        val allowed = optionsOf(sub) ?: emptySet()
        val given = rest.filter { it.startsWith("--") && it.length > 2 }.map { it.substring(2).substringBefore('=') }
        val foreign = given.filter { it !in allowed && it !in GLOBAL_OPTIONS }.distinct()
        if (foreign.isNotEmpty()) throw ActionError("UNKNOWN_OPTION", run {
            val bad = foreign.joinToString(" ") { "--$it" }; val hint = suggestFor(sub, foreign.first())
            val ok = allowed.sorted().joinToString(" ") { "--$it" }.ifEmpty { tr("无", "none") }
            tr("$sub 没有参数 $bad$hint。命令没有执行。$sub 的参数：$ok", "$sub has no option $bad$hint. The command did not run. Options of $sub: $ok")
        })
        for ((i, t) in rest.withIndex()) {
            if (!t.startsWith("--") || t.length <= 2) continue
            val key = t.substring(2).substringBefore('=')
            val kind = VScreenOptions.kindOf(sub, key)
            if ('=' in t && kind == VScreenOptions.Kind.SWITCH) throw ActionError("INVALID_ARGS", tr("--$key 是开关，不带值（收到 $t）。命令没有执行", "--$key is a switch and takes no value (got $t). The command did not run"))
            val next = rest.getOrNull(i + 1)
            if ('=' !in t && kind == VScreenOptions.Kind.VALUE && (next == null || next.startsWith("--")))
                throw ActionError("INVALID_ARGS", run { val got = next ?: tr("结尾", "the end"); tr("--$key 后面要跟一个值（收到的是 $got）。命令没有执行", "--$key needs a value after it (got $got). The command did not run") })
        }
    }

    /** Options of [sub], from its help lines (null: no such command). */
    internal fun optionsOf(sub: String): Set<String>? = synchronized(optionCache) {
        optionCache.getOrPut(sub) {
            // Options come from the Chinese help in every language: one source, the English help lists the same ones.
            val h = helpLines(sub, HELP_ZH)?.joinToString("\n")
            if (h == null) emptySet() else Regex("--([a-z][a-z0-9-]*)").findAll(h).map { it.groupValues[1] }.toSet()
        }.takeIf { it.isNotEmpty() }
    }

    private fun suggestFor(sub: String, bad: String): String {
        val pool = (optionsOf(sub) ?: emptySet())
        val best = pool.minByOrNull { editDistance(it, bad) } ?: return ""
        return if (editDistance(best, bad) <= maxOf(2, bad.length / 3)) tr("；是不是 --$best？", "; did you mean --$best?") else ""
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * The user's per-app rules for the helpers that read the screen (act and launch check them per step / per app).
     * block: nothing is read. readonly: reading works; collect (it scrolls) works too.
     */
    internal fun enforceRules(sub: String) {
        if (VScreenStore.rules.value.isEmpty() || sub in RULE_FREE) return
        val id = BackgroundOperation.state.value.displayId ?: return
        val pkg = com.kilon.agentvscreen.core.VScreenShell.topPackage(id) ?: return
        if (VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK)
            throw ActionError("APP_BLOCKED", tr("副屏上现在是 $pkg，用户禁止后台操作它（不能读屏也不能操作）：act \"key back\" 离开或 stop。这是用户在 Agent-VScreen › 应用限制 里定的，不要绕过",
                "The screen now shows $pkg, which the user blocked from background operation (no reading, no actions): leave with act \"key back\" or stop. The user set this in Agent-VScreen › App rules; don't get around it"))
    }

    internal fun dispatch(sub: String, args: OffloadArgs, sid: String?): NativeOffloadResult = when (sub) {
        "launch" -> launchCmd(sid, args)
        "act" -> act(sid, args)
        "note" -> noteCmd(sid, args)
        "keep" -> keepCmd(sid, args)
        "stop" -> stopCmd(sid, args)
        "release" -> releaseCmd(sid)
        "handoff" -> handoffCmd(sid, args)
        "status" -> status(sid, args)
        "apps" -> apps(args)
        "events" -> eventsCmd(sid, args)
        "done" -> doneCmd(sid)
        "title" -> titleCmd(sid, args)
        "spots" -> spotsCmd(sid, args)
        "zoom" -> zoomCmd(sid, args)
        "collect" -> collectCmd(sid, args)
        "fix" -> fix(sid, args)
        "resume", "continue", "look" -> NativeOffloadResult(2, tr("vscreen: 没有 $sub 命令，也不需要：要看现在的画面就 act（不带步骤）；launch 和 act 返回的【现在】就算重新看过画面，之后的动作照常执行。用户在悬浮窗上点了“停止”或接管时，只有用户能交还，这时告诉用户、等用户交还\n",
            "vscreen: there is no $sub command, and none is needed: to see the screen now, act (no steps); the [Now] returned by launch and act counts as a fresh look, and later actions run as usual. When the user tapped \"Stop\" or took over on the floating window, only the user can hand back: tell the user and wait\n"))
        else -> NativeOffloadResult(2, tr("vscreen: 没有命令 '$sub'（只有一个操作动词 act，点击/输入/滑动/等待都写成 act 的步骤）", "vscreen: no command '$sub' (there is one action verb, act: tapping / typing / swiping / waiting are all act steps)") + "\n\n$QUICK_HELP\n")
    }

    /** Facts about the display, for status. */
    internal fun base(d: VScreenDisplay) = JSONObject()
        .put("display_id", d.displayId).put("generation", d.generation)
        .put("width", d.width).put("height", d.height).put("display_rotation", d.rotation * 90)

    internal fun ok(args: OffloadArgs, data: Any): NativeOffloadResult {
        val body = JSONObject().put("ok", true).put("data", data).toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun errText(code: String, message: String): NativeOffloadResult =
        NativeOffloadResult(1, "${Blocks.error}$code${tr("：", ": ")}$message\n")
}
