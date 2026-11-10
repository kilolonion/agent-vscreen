package com.kilon.agentvscreen.cmd

import android.content.Context
import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenStore
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.Audit
import com.kilon.agentvscreen.tr
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * `vscreen` — the agent's background-screen tool.
 *
 * One verb does the work: `act` runs a stretch of steps on the caller's background screen and returns four fixed
 * blocks (what was done / what happened meanwhile / the screen now / notes). The phone reports facts only: no
 * verdicts on progress, success or meaning. Everything runs on the virtual display owned by the calling session;
 * there is no main-screen fallback.
 *
 * 1.2: every command goes through one gate ([handle]): the command table ([CommandSpec]) decides its options, the
 * permission it needs and whether it waits in the screen queue; the result is typed ([NativeOffloadResult.code],
 * [NativeOffloadResult.data]); every call is written to the audit log.
 *
 * Files: VScreenAct (act, launch), VScreenSteps (the step language), VScreenReport (the four blocks),
 * VScreenOpenCommands (launch internals, handoff, stop, release), VScreenNotes (note, keep),
 * VScreenHelpers (spots, zoom, collect), VScreenScreenCommands (status, apps, fix), Tasks (--async, task), VScreenDiag.
 */
class VScreenOffloadHandler(internal val context: Context) : NativeOffloadHandler {

    companion object {
        internal const val TAG = "VScreenOffload"
        const val DEFAULT_SESSION = "default"
        internal const val SHOT_DIR = "vscreen"

        val QUICK_HELP: String get() = CommandSpec.quickHelp()
        val HELP: String get() = CommandSpec.fullHelp()

        /** Last activity observed per display (status shows it). */
        internal val lastActivity = HashMap<Int, String>()
        /** Per session: when its last four-block return was made (epoch ms) — the start of the next "meanwhile". */
        internal val lastReturn = HashMap<String, Long>()
        /** Per session: the element list of its last "now" block (what #N means). */
        internal val lastElements = HashMap<String, ElSet>()

        /** Commands that do not read the app on the screen themselves (act and launch check the app rules per step). */
        internal val RULE_FREE = setOf("status", "apps", "release", "stop", "fix", "help", "handoff", "note", "keep", "launch", "act", "events", "done", "title", "task", "diag")

        /**
         * 1.2: the screen queue. There is one background screen; commands that work on it run one at a time, in the
         * order they arrived (fair lock). Before 1.2 a second caller got BUSY at once and had to guess when to retry.
         */
        private val screenQueue = ReentrantLock(true)
        internal const val QUEUE_WAIT_MS = 60_000L
        @Volatile internal var queueHolder: String? = null

        /** Drop everything remembered about [displayId] (called when BackgroundOperation releases it). */
        internal fun forgetDisplay(displayId: Int) {
            synchronized(lastActivity) { lastActivity.remove(displayId) }
            com.kilon.agentvscreen.core.VScreenPerception.stop(displayId)
            com.kilon.agentvscreen.core.VScreenRecorder.stop(displayId)
            BackgroundOperation.appContextOrNull()?.let { ShotStore.clearLater(it) }
        }

        init { BackgroundOperation.onScreenReleased { forgetDisplay(it) } }
    }

    /** `help [command|all|codes]`. */
    internal fun helpFor(cmd: String?): String = when (cmd) {
        null -> CommandSpec.quickHelp()
        "all" -> CommandSpec.fullHelp()
        "codes" -> Codes.help()
        else -> CommandSpec.helpFor(cmd) ?: (tr("没有叫 $cmd 的命令。", "There is no command called $cmd.") + "\n\n" + CommandSpec.quickHelp())
    }

    /** Nothing a command does may take the app down: any throwable becomes an error result. Every call is audited. */
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val call = Call.current()
        val r = try {
            handleCommand(request)
        } catch (t: Throwable) {
            AppLogger.error(TAG, "vscreen command crashed", t)
            err("INTERNAL_ERROR", run { val why = "${t.javaClass.simpleName}: ${(t.cause?.message ?: t.message ?: "").take(300)}"; tr("工具内部错误（$why），命令没有执行完。vscreen diag 能导出诊断，交给开发者", "Internal tool error ($why); the command did not finish. vscreen diag exports a diagnostic bundle for the developer") })
        }
        val argv = request.argv.drop(1)
        if (argv.firstOrNull() !in setOf(null, "help", "--help", "-h"))
            com.kilon.agentvscreen.host.quiet("audit") { Audit.command(call?.tokenName ?: "main", call?.peer, request.sessionId ?: DEFAULT_SESSION, argv, r.codeOrDefault, r.exitCode, android.os.SystemClock.elapsedRealtime() - t0, call?.requestId) }
        return r
    }

    private fun handleCommand(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val sub = argv.firstOrNull()
        if (sub == null || sub == "--help" || sub == "-h") return NativeOffloadResult(0, helpFor(null) + "\n")
        if (sub == "help") return NativeOffloadResult(0, helpFor(argv.getOrNull(1)) + "\n")
        if (argv.drop(1).contains("--help")) return NativeOffloadResult(0, helpFor(sub) + "\n")
        // 1.2.0-beta2: a look-only token cannot open a screen of its own, so its commands look at the screen that exists
        // (whoever owns it) instead of meeting NO_SCREEN / BUSY for ever.
        val sid = (request.sessionId?.takeIf { it.isNotBlank() } ?: DEFAULT_SESSION).let { own ->
            if (Call.current()?.scopes == setOf("look")) BackgroundOperation.state.value.ownerSessionId ?: own else own
        }
        takeAdjusted()
        return try {
            val spec = CommandSpec.of(sub) ?: return unknown(sub)
            val rest = VScreenOptions.normalize(sub, argv.drop(1))
            checkOptions(sub, rest)
            val args = VScreenOptions.args(sub, rest)
            allowed(spec, spec.scopeFor(args))
            if (spec.long && args.hasFlag("async")) return Tasks.start(this, sid, sub, argv.filter { it != "--async" })
            queued(spec, sid) {
                BackgroundOperation.refreshStatus()
                enforceRules(sub)
                dispatch(sub, args, sid)
            }
        } catch (e: ActionError) {
            err(e.code, e.message ?: e.code, data = e.data)
        } catch (e: OffloadArgs.BadOption) {
            err("INVALID_ARGS", e.message ?: tr("参数不对", "bad arguments"), 2)
        } catch (t: Exception) {
            AppLogger.error(TAG, "$sub failed", t)
            err("OPERATION_FAILED", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** The caller's token must cover what the command does (look ⊂ operate ⊂ system). */
    private fun allowed(spec: CommandSpec.Cmd, need: CommandSpec.Scope) {
        val call = Call.current() ?: return
        if (need.key in call.scopes) return
        throw ActionError("SCOPE_DENIED", tr("这个令牌（${call.tokenName}）的权限不含“${need.key}”，这条 ${spec.name} 没有执行。权限由用户在 Agent-VScreen › 权限 › 令牌 里定，不要换别的办法绕过；需要的话请用户调整",
            "This token (${call.tokenName}) does not include \"${need.key}\", so this ${spec.name} did not run. The user sets token permissions in Agent-VScreen › Permissions › Tokens; don't work around it, ask the user to change it if needed"))
    }

    /** Run [block] in the screen queue when the command works on the screen. */
    private fun queued(spec: CommandSpec.Cmd, sid: String, block: () -> NativeOffloadResult): NativeOffloadResult {
        if (!spec.screen || screenQueue.isHeldByCurrentThread) return block()
        val call = Call.current()
        val deadline = System.currentTimeMillis() + QUEUE_WAIT_MS
        while (!screenQueue.tryLock(200, TimeUnit.MILLISECONDS)) {
            if (call?.cancelled == true) throw ActionError("CANCELLED", tr("排队时调用方断开了，命令没有执行", "The caller went away while queued; the command did not run"))
            if (System.currentTimeMillis() > deadline) throw ActionError("QUEUE_TIMEOUT", run { val h = queueHolder ?: "?"; tr("副屏正被另一个调用使用（$h），排了 ${QUEUE_WAIT_MS / 1000} 秒还没轮到，命令没有执行。稍后重发", "The screen is in use by another call ($h); after ${QUEUE_WAIT_MS / 1000} s in the queue it was still not this command's turn, so it did not run. Send it again later") })
        }
        queueHolder = "${call?.tokenName ?: "main"}/$sid ${spec.name}"
        try { return block() } finally { queueHolder = null; screenQueue.unlock() }
    }

    /**
     * An option this command does not have is refused (with the nearest right one), a switch given a value and an
     * option missing its value are refused: nothing runs, so a typo is never mistaken for "it worked".
     */
    internal fun checkOptions(sub: String, rest: List<String>) {
        val allowed = optionsOf(sub) ?: emptySet()
        val given = rest.filter { it.startsWith("--") && it.length > 2 }.map { it.substring(2).substringBefore('=') }
        val foreign = given.filter { it !in allowed && it !in CommandSpec.GLOBAL_OPTIONS }.distinct()
        if (foreign.isNotEmpty()) throw ActionError("UNKNOWN_OPTION", run {
            val bad = foreign.joinToString(" ") { "--$it" }; val hint = suggestFor(sub, foreign.first())
            val ok = CommandSpec.of(sub)?.allOpts?.filter { !it.deprecated }?.joinToString(" ") { "--${it.name}" }.orEmpty().ifEmpty { tr("无", "none") }
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

    /** Options of [sub], from the command table (null: no such command or no options). */
    internal fun optionsOf(sub: String): Set<String>? = CommandSpec.optionNames(sub)?.takeIf { it.isNotEmpty() }

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
        "task" -> Tasks.command(sid ?: DEFAULT_SESSION, args)
        "done" -> doneCmd(sid)
        "title" -> titleCmd(sid, args)
        "spots" -> spotsCmd(sid, args)
        "zoom" -> zoomCmd(sid, args)
        "collect" -> collectCmd(sid, args)
        "fix" -> fix(sid, args)
        "diag" -> diagCmd(sid)
        else -> unknown(sub)
    }

    private fun unknown(sub: String): NativeOffloadResult = if (sub in setOf("resume", "continue", "look", "screenshot", "tap", "type", "swipe", "key", "wait", "scroll"))
        NativeOffloadResult(2, tr("vscreen: 没有 $sub 命令：看画面就 act（不带步骤），点击/输入/滑动/等待都写成 act 的步骤（act \"tap 登录; type 你好\"）。用户在悬浮窗上点了“停止”或接管时，只有用户能交还\n",
            "vscreen: there is no $sub command: to see the screen, act (no steps); tapping / typing / swiping / waiting are act steps (act \"tap Login; type hello\"). When the user tapped \"Stop\" or took over on the floating window, only the user can hand back\n"), "UNKNOWN_COMMAND")
    else NativeOffloadResult(2, tr("vscreen: 没有命令 '$sub'", "vscreen: no command '$sub'") + "\n\n" + CommandSpec.quickHelp() + "\n", "UNKNOWN_COMMAND")

    /** Facts about the display, for status. */
    internal fun base(d: VScreenDisplay) = JSONObject()
        .put("display_id", d.displayId).put("generation", d.generation)
        .put("width", d.width).put("height", d.height).put("display_rotation", d.rotation * 90)

    /** A JSON reply (status, apps, fix): the object is the text and the data. */
    internal fun ok(args: OffloadArgs, data: Any): NativeOffloadResult {
        val body = JSONObject().put("ok", true).put("data", data).toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n", "OK", (data as? JSONObject)?.let { JSONObject().put("result", it) })
    }

    /** An error reply: the code, what happened, and what to do next (from the code table). */
    internal fun err(code: String, message: String, exit: Int = if (code in setOf("INVALID_ARGS", "UNKNOWN_OPTION", "INVALID_STEP", "UNKNOWN_COMMAND")) 2 else 1, data: JSONObject? = null): NativeOffloadResult =
        NativeOffloadResult(exit, "${Blocks.error}$code${tr("：", ": ")}$message\n", code,
            JSONObject().put("message", message).put("next", Codes.next(code)).also { o -> data?.keys()?.forEach { k -> o.put(k, data.get(k)) } })
}
