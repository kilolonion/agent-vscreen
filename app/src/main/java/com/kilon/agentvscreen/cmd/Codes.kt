package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr

/**
 * 1.2: every code a result or a note can carry, with what it means and what the caller should do next. One table for
 * `help codes`, the `advice` field of structured notes, and API.md. A code not listed here is a bug (CodesTest).
 *
 * [next] tells an agent loop what kind of move is right, without reading prose:
 *  - LOOK   look at the screen (act with no steps) and decide again;
 *  - FIX    the command was written wrong: correct it and send again;
 *  - USER   only the user can unblock this: tell the user and stop, do not retry;
 *  - WAIT   try again a little later;
 *  - NONE   nothing to do (information).
 */
internal object Codes {
    enum class Next(val key: String) { LOOK("look"), FIX("fix_command"), USER("ask_user"), WAIT("retry_later"), NONE("none") }

    class Code(val name: String, val next: Next, private val zh: String, private val en: String) {
        val text: String get() = tr(zh, en)
    }

    val all: List<Code> = listOf(
        Code("OK", Next.NONE, "按预期完成。", "Done as expected."),
        // the step language
        Code("STEP_MISSED", Next.LOOK, "一步做了，但没等到它期待的结果（until 超时、wait 超时、输入没进去）。这段停在那一步，后面的没做。看截图再决定。", "A step was done but what it expected never came (until / wait timed out, typed text did not land). The stretch stopped there and skipped the rest. Look at the screenshot before deciding."),
        Code("NOT_FOUND", Next.LOOK, "画面上没有你写的字、编号或段名。返回里列了相近的字和输入框的坐标。", "The text, number or segment you named is not there. The reply lists similar text and input boxes with their points."),
        Code("AMBIGUOUS", Next.FIX, "你写的字在画面上有好几处，没点。返回里列了每一处的坐标：改用坐标或 #N。", "The text you named is on the screen several times; nothing was tapped. The reply lists every place with its point: use coordinates or #N."),
        Code("STALE_ELEMENT", Next.LOOK, "#N 指的元素已经不在上次列表里的位置（页面变了）。没点。重新看一眼再用新的编号。", "The element #N pointed to is no longer where the last list had it (the page changed). Nothing was tapped. Look again and use the new numbers."),
        Code("OUT_OF_BOUNDS", Next.FIX, "坐标在副屏外面。", "The point is outside the screen."),
        Code("INVALID_STEP", Next.FIX, "步骤写法不对，整段没有执行。", "A step is written wrong; nothing in the stretch ran."),
        Code("INVALID_ARGS", Next.FIX, "参数不对，命令没有执行。", "Bad arguments; the command did not run."),
        Code("UNKNOWN_OPTION", Next.FIX, "这个命令没有这个参数，命令没有执行。", "The command has no such option; it did not run."),
        Code("UNKNOWN_COMMAND", Next.FIX, "没有这个命令。点击、输入、滑动、等待都写成 act 的步骤。", "No such command. Tapping, typing, swiping and waiting are all act steps."),
        Code("FOCUS_ELSEWHERE", Next.LOOK, "有焦点的输入框不是你刚点的地方，没有输入。", "The focused input box is not where you just tapped; nothing was typed."),
        Code("TYPE_FAILED", Next.LOOK, "文字没能送进去。", "The text could not be sent."),
        Code("NO_CHANGE", Next.LOOK, "这一步做了，但之后画面没有可见的变化（可能没点中，或这里本来就不会变）。", "The step was done but the picture did not visibly change afterwards (the tap may have missed, or nothing changes here)."),
        // user safety
        Code("CONFIRM_PENDING", Next.USER, "这一步要用户在手机上确认（付费/不可撤销/用户设为每次询问的应用/会影响主屏的操作），用户还没答复，没做。请用户在手机通知里点“允许”，然后把这一段重发一遍。", "This step needs the user's confirmation on the phone (payment / irreversible / an app set to ask every time / something that affects the main screen); the user has not answered and it was not done. Ask the user to tap Allow in the phone's notification, then send this stretch again."),
        Code("USER_DENIED", Next.USER, "用户在手机上拒绝了这一步。不要重试，问用户想怎么办。", "The user denied this step on the phone. Don't retry; ask the user what they want."),
        Code("CONFIRM_REQUIRED", Next.USER, "同 CONFIRM_PENDING（旧名字）。", "Same as CONFIRM_PENDING (old name)."),
        Code("APP_BLOCKED", Next.USER, "用户禁止后台操作这个应用。不要绕过，也不要从别的入口进同一个应用。", "The user blocked background operation of this app. Don't get around it or enter the same app another way."),
        Code("APP_READ_ONLY", Next.USER, "用户把这个应用设成只读：能看、滚动、返回，不能点击/输入/滑动。", "The user set this app to read-only: you may look, scroll and go back, not tap / type / swipe."),
        Code("USER_TAKEOVER", Next.USER, "用户正在自己操作副屏。停下告诉用户，等用户交还。", "The user is operating the screen. Stop, tell the user, and wait for the hand-back."),
        Code("USER_STOPPED", Next.USER, "用户按了“停止 AI”。所有操作都被拒绝，只有用户能解除；把返回里怎么解除的那句转告用户。", "The user pressed \"Stop AI\". Everything is refused and only the user can lift it; pass on the line that says how."),
        Code("REREAD_REQUIRED", Next.LOOK, "用户刚接管过副屏，页面可能变了。这次的返回就是现在的画面，看清再继续。", "The user just had the screen and the page may have changed. This reply is the screen now: look, then carry on."),
        Code("HOST_RESTARTED", Next.LOOK, "Agent-VScreen 进程重启过，上一轮的任务被打断。这次的返回就是现在的画面，不要假设之前的步骤都做完了。", "The Agent-VScreen process restarted and the last task was cut off. This reply is the screen now; don't assume earlier steps completed."),
        Code("MAIN_SCREEN_CHANGED", Next.USER, "这段前后主屏的前台应用或任务变了：可能是用户自己切的，也可能被打扰了。先告诉用户。", "The main screen's front app or tasks changed during this stretch: the user may have switched, or something disturbed it. Tell the user first."),
        Code("SCOPE_DENIED", Next.USER, "这个令牌没有做这件事的权限（用户在 App 里给令牌定的范围）。", "This token is not allowed to do this (the scope the user gave the token in the app)."),
        Code("PROTECTED_APP", Next.NONE, "这是 Agent-VScreen 自己、桌面或系统界面，不能结束。", "This is Agent-VScreen itself, the launcher or system UI; it cannot be stopped."),
        // screen and connection
        Code("NO_SCREEN", Next.FIX, "现在没有副屏：先 launch 一个应用。", "There is no screen now: launch an app first."),
        Code("NO_APP", Next.FIX, "没有这个应用，或副屏上没有应用。apps <关键词> 查包名。", "No such app, or no app on the screen. apps <keyword> finds packages."),
        Code("NO_FRAME", Next.WAIT, "副屏还没有画面，稍后再试。", "The screen has no picture yet; try again shortly."),
        Code("NO_SESSION", Next.FIX, "请求里没有会话名。", "The request names no session."),
        Code("BUSY", Next.WAIT, "副屏现在归另一个会话。返回里写了归谁（owner）、多久后自动让出（frees_in_s 秒；null 表示它正在执行命令）。", "The screen belongs to another session now. The reply says whose (owner) and when it frees up by itself (frees_in_s seconds; null while it is running a command)."),
        Code("QUEUE_TIMEOUT", Next.WAIT, "前面的命令还没做完，这条排队超过 60 秒，没有执行。", "Earlier commands are still running; this one waited in the queue for over 60 s and did not run."),
        Code("CANCELLED", Next.NONE, "调用方断开了或取消了任务，命令在下一步之前停下。", "The caller disconnected or cancelled the task; the command stopped before its next step."),
        Code("CONNECTION_LOST", Next.USER, "后台特权连接断了（Shizuku 停了），副屏和上面的应用已经没了，不是你的操作造成的。告诉用户；恢复后重新 launch。", "The privileged background connection broke (Shizuku stopped); the screen and its app are gone, not because of your actions. Tell the user; launch again once it is back."),
        Code("NOT_CONNECTED", Next.USER, "后台连接没有就绪。vscreen fix not_connected，或请用户打开 Agent-VScreen。", "The background connection is not ready. Run vscreen fix not_connected, or ask the user to open Agent-VScreen."),
        Code("DISABLED", Next.USER, "用户关掉了 Agent-VScreen 的服务。", "The user turned the Agent-VScreen service off."),
        Code("CREATE_FAILED", Next.USER, "副屏没建出来。status 里有原因。", "The screen could not be created. status says why."),
        Code("STALE_SCREEN", Next.LOOK, "这个操作是针对已经重建过的旧副屏的。", "This action was meant for an older screen that has been rebuilt."),
        Code("NOT_SUPPORTED", Next.USER, "这台手机做不了这个操作（副屏服务不可用）。status 的 degraded 里有原因。", "This phone cannot do this (the screen service is unavailable). See degraded in status."),
        Code("INPUT_FAILED", Next.LOOK, "系统没有接受这次输入注入。", "The system did not accept the injected input."),
        // launch / handoff / stop
        Code("APP_NOT_FOUND", Next.FIX, "找不到这个包的启动入口。", "No launch entry for this package."),
        Code("LAUNCH_FAILED", Next.LOOK, "应用没启动起来。", "The app did not start."),
        Code("LAUNCH_RESOLVER", Next.FIX, "启动后停在系统的“打开方式”选择器：这个包没有桌面入口。", "It stopped on the system's \"Open with\" chooser: the package has no launcher entry."),
        Code("LAUNCHED_ON_MAIN_SCREEN", Next.USER, "系统把应用开到了主屏而不是副屏。不要在主屏上继续，告诉用户。", "The system opened the app on the main screen instead. Don't go on there; tell the user."),
        Code("LAUNCH_NOT_ON_TOP", Next.LOOK, "启动后副屏最前面是别的应用。", "After starting, another app is in front on the screen."),
        Code("HANDOFF_NO_TASK", Next.USER, "主屏（或副屏）上没有可以移动的这个应用。", "There is no such app task to move on the main screen (or the background screen)."),
        Code("HANDOFF_DENIED", Next.USER, "系统没有让应用在两块屏之间移动。", "The system did not let the app move between the screens."),
        Code("CLOSE_FAILED", Next.LOOK, "没能只关掉副屏上的任务。", "Could not close just the task on the screen."),
        Code("MAIN_SCREEN_TASK", Next.NONE, "这个任务在主屏上，没有动它。", "This task is on the main screen; it was left alone."),
        Code("KEEP_LIMIT", Next.FIX, "同时盯守的条件到上限了：先 keep off 一个。", "Too many watches at once: turn one off with keep off."),
        Code("TASK_NOT_FOUND", Next.FIX, "没有这个任务号（任务结果保留 30 分钟）。task 列出本会话的任务。", "No such task id (results are kept 30 minutes). task lists this session's tasks."),
        Code("TASK_RUNNING", Next.WAIT, "任务还在执行。", "The task is still running."),
        // transport
        Code("BAD_TOKEN", Next.USER, "令牌缺失、不对或已被用户撤销。请用户在 Agent-VScreen 的“接入 agent”里重新复制接入的话。", "The token is missing, wrong or was revoked by the user. Ask the user to copy the connect message again under Connect an agent in Agent-VScreen."),
        Code("BAD_SIGNATURE", Next.USER, "请求签名不对或时间差太大（命令行会自动校时重试一次）。", "The request signature is wrong or the clocks differ too much (the CLI corrects its clock and retries once)."),
        Code("RATE_LIMITED", Next.WAIT, "这个来源连续用错令牌，被暂时拒绝。", "This source kept using wrong tokens and is refused for a while."),
        Code("SERVER_BUSY", Next.WAIT, "同时进来的连接太多。", "Too many connections at once."),
        Code("BAD_HOST", Next.FIX, "请求的 Host 不是这台手机的地址或名字。", "The request's Host is not this phone's address or name."),
        Code("BAD_JSON", Next.FIX, "请求体不是合法的 JSON。", "The request body is not valid JSON."),
        Code("BAD_REQUEST", Next.FIX, "请求格式不对。", "The request is malformed."),
        Code("BROWSER_REFUSED", Next.NONE, "不接受网页发起的请求。", "Requests from web pages are not accepted."),
        Code("METHOD", Next.FIX, "这个地址要用另一种 HTTP 方法。", "This address needs another HTTP method."),
        Code("NOT_ALLOWED", Next.USER, "其它设备访问没有打开或没有登录。", "Access from other devices is off or not signed in."),
        Code("NO_SHOT", Next.LOOK, "这张截图已经不在了（只留最近 300 张，副屏释放时清空）。重新看一眼。", "That screenshot is gone (only the last 300 are kept; they are cleared when the screen is released). Look again."),
        Code("PEER_DENIED", Next.USER, "这台设备不在用户的允许名单里。", "This device is not on the user's allow list."),
        Code("REPLAYED", Next.FIX, "这个签名请求已经收到过一次。每个请求用新的 Nonce。", "This signed request was already received once. Use a fresh nonce for every request."),
        Code("BODY_TOO_LARGE", Next.FIX, "请求体太大（上限 1 MB）。", "The request body is too large (at most 1 MB)."),
        Code("HEADERS_TOO_LARGE", Next.FIX, "请求头太大。", "The request headers are too large."),
        Code("REQUEST_TIMEOUT", Next.WAIT, "请求没有在时限内发完。", "The request did not arrive in time."),
        Code("SIGNATURE_REQUIRED", Next.USER, "从其它设备来的请求必须签名（vscreen 命令行会签名）。MCP 等不能签名的客户端：请用户在 其它设备 里关掉“只接受签名请求”。", "Requests from other devices must be signed (the vscreen CLI signs). For clients that cannot sign, such as MCP: ask the user to turn off \"signed requests only\" under Other devices."),
        Code("TASK_STARTED", Next.NONE, "任务已开始，用 task <号> 取结果。", "The task started; fetch its result with task <id>."),
        Code("TIME_SKEW", Next.USER, "调用方的时钟和手机差得太多，签名因此不被接受。校准调用方的时钟。", "The caller's clock is too far from the phone's, so the signature is not accepted. Fix the caller's clock."),
        Code("OPERATION_FAILED", Next.LOOK, "执行出错，返回里有原因。", "It failed; the reply says why."),
        Code("INTERNAL_ERROR", Next.NONE, "工具内部错误。把这段报错发给开发者（vscreen diag 能导出诊断）。", "Internal tool error. Send it to the developer (vscreen diag exports diagnostics)."),
    )

    private val byName = all.associateBy { it.name }
    fun of(code: String?): Code? = code?.let { byName[it] }
    fun next(code: String?): String = (of(code)?.next ?: Next.LOOK).key

    /** `help codes`. */
    fun help(): String = buildString {
        append(tr("代码表：结果的 code 和【注意】里每条开头的代码。括号里是下一步该怎么走：look 先看画面再决定；fix_command 改命令重发；ask_user 只有用户能解决，告诉用户并停下，不要重试；retry_later 稍后再试。\n\n",
            "Codes: the code of a result and the code each [Notes] line starts with. In brackets, the right next move: look = look at the screen and decide again; fix_command = correct the command and resend; ask_user = only the user can unblock it, tell the user and stop, don't retry; retry_later = try again shortly.\n\n"))
        all.forEach { append("  ").append(it.name).append(" (").append(it.next.key).append(")  ").append(it.text).append('\n') }
    }

    fun markdown(): String = buildString {
        append("| code | next | ").append(tr("含义", "meaning")).append(" |\n|---|---|---|\n")
        all.forEach { append("| `").append(it.name).append("` | `").append(it.next.key).append("` | ").append(it.text.replace("|", "\\|")).append(" |\n") }
    }
}
