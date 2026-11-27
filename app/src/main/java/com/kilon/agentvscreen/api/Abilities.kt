package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.CommandSpec
import com.kilon.agentvscreen.tr

/**
 * 1.4: the one list of what an agent can do with Agent-VScreen, and when to reach for each.
 *
 * Why it exists: before 1.4 the same abilities were written out by hand in five places (the memory note, the guide's
 * recipes, the update notice, the Operit advice, the README). Each new feature was added to some of them and not the
 * others. The note an agent keeps in its memory, the only text it sees at the start of a new conversation, still said
 * "tap, swipe, type, screenshots" in 1.3, so an agent asked to "check in every morning at 7" had no idea the phone
 * could book that time (`schedule`), and said it couldn't.
 *
 * Now every text that tells an agent what it can do is generated from this table:
 *  - [AgentGuide.memory] (and the MCP / HTTP variants): one line per ability, written as "when the user … → I …";
 *  - [AgentGuide.prompt] (`vscreen guide`, `/skill`, the MCP server instructions): the "what you can do" section;
 *  - [AgentNews.notice]: abilities added or changed after the version the agent's note is for, plus the new note to
 *    save in place of the old one, so an agent never has to rewrite it from memory;
 *  - the Operit advice and the README's ability list (written out by AbilitiesTest, which fails when they are stale).
 *
 * AbilitiesTest also checks that every command in [CommandSpec] belongs to an ability. A new command that nobody tells
 * agents about therefore fails the build instead of shipping silently.
 *
 * Wording rule: [whenZh]/[whenEn] are in the user's terms (what the user asks for), never in ours; [canZh]/[canEn]
 * name the command so the agent can act on the line alone; nothing here is about a particular app.
 */
object Abilities {
    class Ability(
        val id: String,
        /** Version code it first shipped in, and the last one whose change matters to an agent (news, note refresh). */
        val since: Int, val changed: Int,
        /** The situation, as the user would put it. */
        val whenZh: String, val whenEn: String,
        /** What I do about it, one short line naming the command. For the memory note. */
        val canZh: String, val canEn: String,
        /** How, for the guide: the recipe with examples. */
        val howZh: String, val howEn: String,
        /** The commands this ability is made of (each command belongs to exactly one ability). */
        val commands: List<String>,
        /** What changed for agents in [changed], one line (the update notice); empty = nothing to say beyond the new note. */
        val newsZh: String = "", val newsEn: String = "",
    ) {
        val news: String get() = tr(newsZh, newsEn)
        val whenText: String get() = tr(whenZh, whenEn)
        val can: String get() = tr(canZh, canEn)
        val how: String get() = tr(howZh, howEn)
    }

    // 1.4.0-beta1 (34): every line was rewritten as "when the user … → I …", so every ability counts as changed in 34.
    val all: List<Ability> = listOf(
        Ability("operate", 1, 46,
            "用户要我在手机上做任何事：打开某个应用、查信息、比价、看某页内容、填表、发帖、测试应用……",
            "the user wants anything done on the phone: open an app, look something up, read a page, fill a form, test an app…",
            "`vscreen launch <应用>` 打开并看，`vscreen act \"<步骤>\"` 点、滑、输入、按键，每次都返回截图和元素列表",
            "`vscreen launch <app>` opens it and looks, `vscreen act \"<steps>\"` taps, swipes, types and presses keys; every reply has a screenshot and the elements",
            "按第一到第四节的循环做。长操作加 `--async`，之后 `vscreen task <号> --wait 60s` 取结果。让用户知道你在做什么：`vscreen title \"在搜耳机\"`。做完：`vscreen done`（副屏让出，页面保留）；`vscreen stop` 关掉副屏上的应用，`vscreen release` 立即释放副屏。不知道包名：`vscreen apps 关键词`；Android 16+ 查应用结构化接口：`vscreen fn [应用]`。",
            "Follow the loop in sections 1–4. Add `--async` for long work and fetch it with `vscreen task <id> --wait 60s`. Let the user see what you are doing: `vscreen title \"searching headphones\"`. When finished: `vscreen done` (screen freed, page kept); `vscreen stop` closes the app on the screen, `vscreen release` frees the screen at once. Unknown package: `vscreen apps <keyword>`; Android 16+ app functions: `vscreen fn [app]`.",
            listOf("launch", "act", "title", "done", "stop", "release", "apps", "fn", "task"),
            // 1.4.0-beta13 (46): shortcuts and typing one character at a time.
            "act 新增：组合键 `key ctrl+a`、`key ctrl+c`、`key shift+tab`（ctrl/shift/alt + 键）；`type \"文字\" slow`（可加 `200ms`）逐字输入，给边打边搜、逐字校验的输入框，不加 slow 仍是一次输入。",
            "act now has shortcuts `key ctrl+a`, `key ctrl+c`, `key shift+tab` (ctrl/shift/alt + a key), and `type \"text\" slow` (optionally `200ms`) to type one character at a time for search-as-you-type or per-character boxes; without slow it is typed at once as before."),
        // 1.4.0-beta10 (rainbow: agents with their own scheduled tasks never thought of booking the phone; it is not
        // woken by their timers). The trigger is now ANY timer the agent sets up that will use the phone, whoever asked.
        Ability("schedule", 14, 43,
            "我要为一件会用到手机的事设定时：用户说了定时、每天、每周、到点（签到、打卡、抢购、定时查看），或我自己建了定时任务、周期任务、cron、自动化、提醒",
            "anything timed that will use the phone: the user asks for daily or at 8:00 (check-ins, periodic checks), or I set up my own timer, cron or reminder",
            "两边都要设：我的定时只叫醒我，叫不醒手机。同一时间用 `vscreen schedule \"<时间>\" --name <名字>` 给手机预约，它会在每次之前拉起服务、检查权限，没准备好就提醒用户；改时间、取消时两边一起改",
            "set both: my timer wakes me, not the phone. Book the same time with `vscreen schedule \"<when>\" --name <name>`; change or cancel both together",
            "手机这边负责“到点时一定能用”：`vscreen schedule \"07:00\"`（也可以 `\"weekdays 08:30\"`、`\"mon,wed,fri 21:00\"`、`\"every 2h from 08:00 to 22:00\"`、只一次 `\"today 10:30\"`），`--name 签到` 起名，`--for 20m` 保持时长；`vscreen schedule` 列出预约，`vscreen schedule off 名字` 取消。到点叫醒你的是你自己的定时功能（定时任务、cron、自动化）：两边都设好再告诉用户。你没有定时功能时，照实告诉用户，可以改成 `act --stop 条件 --keep notify` 盯条件。每次被叫醒先看 `vscreen status` 的 ready，false 就把 readiness.blocking 告诉用户。用户也能在 App 里像闹钟一样改时间或关掉某条预约。",
            "The phone's part is \"it will be usable then\": `vscreen schedule \"07:00\"` (also `\"weekdays 08:30\"`, `\"mon,wed,fri 21:00\"`, `\"every 2h from 08:00 to 22:00\"`, once only `\"today 10:30\"`), `--name checkin` to name it, `--for 20m` for the window; `vscreen schedule` lists them, `vscreen schedule off <name>` cancels. What wakes you is your own timer (scheduled task, cron, automation): set up both, then tell the user. If you have no timer, say so plainly; a condition can still be watched with `act --stop <condition> --keep notify`. Each time you wake, read `ready` in `vscreen status`; if false, pass readiness.blocking on to the user. The user can change a booking's time or switch it off in the app, like an alarm.",
            listOf("schedule"),
            "定时规则：只要你为会用到手机的事建了定时（不管是用户要求还是你自己的定时任务），同一时间都要 `vscreen schedule` 预约；你的定时叫不醒手机，不预约，到点时服务可能已经睡了。",
            "Timer rule: whenever you set up anything timed that will use the phone (asked by the user or your own scheduled task), book the same time with `vscreen schedule`; your timer does not wake the phone, and without a booking its service may be asleep by then."),
        Ability("watch", 1, 43,
            "要等一件以后才发生的事：到货、降价、有回复、直播开始、排队轮到、下载完成；或我想隔一会儿再看一次画面",
            "something will happen later: back in stock, a price drop, a reply, a download finishing; or I want to look again in a while",
            "`vscreen act --stop \"text:到货\" --keep notify` 让手机一直盯着，看到就通知用户（`--keep wake` 改成叫醒我，用 `vscreen events` 取）",
            "`vscreen act --stop \"text:In stock\" --keep notify` makes the phone keep watching and notify the user when seen (`--keep wake` wakes me; fetch with `vscreen events`)",
            "`vscreen act --stop \"text:到货\" --keep notify --for 2h`：本轮结束后继续盯，看到就通知用户，重启后还在；`--keep wake` 发事件给你，`vscreen events --wait 60s` 取。`vscreen keep` 列出盯守，`vscreen keep off 名字` 停止。告诉用户你在盯什么、盯多久、看到后会怎样。",
            "`vscreen act --stop \"text:In stock\" --keep notify --for 2h`: keeps watching after this turn and notifies the user when seen, surviving a restart; `--keep wake` sends you an event, fetched with `vscreen events --wait 60s`. `vscreen keep` lists watches, `vscreen keep off <name>` stops one. Tell the user what you watch, for how long and what happens when seen.",
            listOf("keep", "events"),
            "等待规则：别自己循环 sleep 再 look 去等，交给手机盯（--keep），用 `vscreen events --wait` 取结果。",
            "Waiting rule: don't loop sleep-then-look yourself; let the phone watch (--keep) and fetch with `vscreen events --wait`."),
        Ability("collect", 1, 34,
            "要读完一个很长的列表或整页内容（全部订单、聊天记录、搜索结果、评论）",
            "a long list or a whole page has to be read (all orders, a chat history, search results, comments)",
            "`vscreen collect --pages 10` 自动翻页，返回去重后的每一条",
            "`vscreen collect --pages 10` pages through it and returns every item once",
            "`vscreen collect --pages 10`（`--from-top` 先回到顶部，`--until 文字` 看到就停）。它会说是真的到底了，还是可能还在加载。",
            "`vscreen collect --pages 10` (`--from-top` starts at the top, `--until <text>` stops when seen). It says whether the list really ended or may still be loading.",
            listOf("collect")),
        Ability("ask", 14, 37,
            "不确定用户指的是画面上哪个东西（哪个商品、哪个按钮、哪条消息），或要用户选一个",
            "it is unclear which thing on the screen the user means (which item, button, message), or the user should pick one",
            "`vscreen ask \"你说的是哪一个？\"` 让用户在手机上的副屏画面里点出来（也可以 `--choose`、`--text`）",
            "`vscreen ask \"Which one do you mean?\"` lets the user tap it on the screen's picture on the phone (also `--choose`, `--text`)",
            "`vscreen ask \"你说的是哪一个？\"`：用户在副屏的实时画面上点一下，你拿到坐标和那里的 #N；`--area` 框一块，`--choose \"甲|乙\"` 选一个，`--text` 写一句。目标不在画面里时用户会先滑过去再标（回答里会说画面动过）。默认最多等 50 秒，等不到（ASK_PENDING）就告诉用户去手机上回答，然后重发同一条，不用 --async。用户觉得问错了会“说一句”（USER_FEEDBACK，原话在 feedback 里）：照它调整，别原样再问。回答是事实，不是许可：付款仍要手机上确认。",
            "`vscreen ask \"Which one do you mean?\"`: the user taps it on the live screen; you get the point and the #N there; `--area` for a box, `--choose \"A|B\"` to pick one, `--text` for a line. When the target is out of view the user scrolls to it first (the answer says the screen moved). It waits 50 s by default; no answer yet (ASK_PENDING): tell the user to answer on the phone, then send the same ask again, no --async needed. If the user thinks the question is wrong they say so (USER_FEEDBACK, their words in feedback): adjust to it, don't ask the same again. An answer is information, not permission: payments are still confirmed on the phone.",
            listOf("ask"),
            "提问改在实时画面上回答（用户可以先滑到目标）；默认等 50 秒；用户可以“说一句”代替回答（USER_FEEDBACK）。",
            "Questions are answered on the live screen (the user may scroll to the target first); the default wait is 50 s; the user may say something instead of answering (USER_FEEDBACK)."),
        Ability("teach", 20, 48,
            "一个流程说不清、试了几次都不对，或用户自己更清楚怎么做",
            "a flow is hard to describe, keeps failing, or the user knows better how to do it",
            "`vscreen teach \"要演示什么\"` 请用户在副屏上亲手做一遍，存成一段，之后 `act \"use 段名 参数\"` 照做",
            "`vscreen teach \"what to show\"` asks the user to do it once on the screen; it is saved as a segment, then `act \"use <name> <values>\"` replays it",
            "先 launch 到要演示的应用，并把页面整理回用户会从那里开始的样子（清掉你自己留下的输入、计算结果、弹窗、筛选，回到首页或列表顶部），看一眼确认再发；用户的示范从现在的画面开始，你留下的痕迹会被录进去。`vscreen teach \"要演示什么\" --name 建议的名字`。用户先接管走到起点（这时不记录），再开始示范、审阅、存成这个应用的一段；你拿到段名、步骤和一张关键步骤缩略图。之后 `act \"use 段名 参数…\"`：先核对起点，不在起点就一步不做（NOT_AT_START，回执写了起点的样子，自己走过去再 use）。滑到某项、拨轮盘这类操作按结果重放，不按距离；用户输入的字和拨到的值是参数 {1} {2}…，按顺序填。点在没字的地方记成“旁边的字 +dx,+dy”，其余坐标按屏幕比例（50% 80%）记，换尺寸或横竖屏也点得准。用户觉得要学的不对会“说一句”（USER_FEEDBACK）。旧格式的段还能用，但回执会提醒请用户重新示教。用户在演示和审阅保存时请求不会过期：ASK_PENDING 会说用户做到哪一步了，过一会儿把同一条 teach 原样再发就能拿到，别换个说法另发一条。拿到段后先回到起点试跑一遍，再把只对这一次成立的值改成参数或通配、补上 until / try / if，用 note save 存回（起点核对保留）。",
            "launch the app first and put the page back the way the user would start from (clear what you left: typed words, a calculation, a dialog, a filter; go to the home page or the top of the list), look once to check, then send it; the user starts from the screen as it is, and what you left gets recorded. Then `vscreen teach \"what to show\" --name <suggested name>`. The user takes the screen to the starting page (not recorded), then shows it, reviews it and saves it as a segment of the app; you get its name, steps and a contact sheet of key steps. Then `act \"use <name> <values>…\"`: it checks the starting page first and does nothing elsewhere (NOT_AT_START, the reply describes the start: get there, then use it). Scrolling to an item or turning a wheel replays toward the result, not by distance; words the user typed and values the user set are blanks {1} {2}…, filled in order. A tap where nothing is written is kept as \"nearby words +dx,+dy\", other points as shares of the screen (50% 80%), so they land right on another size or orientation. If what you asked to learn is wrong the user says so (USER_FEEDBACK). Old-format segments still work, but the reply suggests having the user teach them again. While the user is showing it or saving it the request does not expire: ASK_PENDING says how far they are; send the very same teach again a little later, not a reworded one. Once you have the segment, go to the start and try it once, then turn values that only held this time into blanks or wildcards, add until / try / if, and save it back with note save (the start check is kept).",
            listOf("teach"),
            "发 teach 之前先把页面整理回起始的样子（例如计算器先清零、清掉自己输入的字、关掉弹窗），看一眼确认再发：用户从现在的画面开始示范。",
            "Before sending teach, put the page back to where it starts (clear the calculator, the words you typed, a dialog) and look once to check: the user starts showing from the screen as it is."),
        Ability("notes", 1, 44,
            "同一个应用会反复用：有固定入口、每次都弹的窗、要先暂停的画面，或对机器节奏敏感",
            "the same app comes up again and again: a fixed way in, a popup every visit, a picture to pause first, or machine-like timing upsets it",
            "`vscreen note` 给这个应用记经验、存步骤（`note save`）、设前置/收尾步骤和仿人节奏，下次 launch 会列出来",
            "`vscreen note` keeps what I learned about the app, saved steps (`note save`), before/after steps and human pacing; the next launch lists them",
            "`vscreen note <一句话>` 记经验；`vscreen note save 名字 \"步骤\"` 存一段（或 act 加 `--save 名字`），之后 `use 名字`；`vscreen note before \"<步骤>\"` / `note after \"<步骤>\"`：每段 act 前后自动做（游戏的继续 / 暂停、反复弹出的窗口）；`vscreen note human on`：这个应用一直用仿人节奏（同 `act --human`）；`vscreen note show 段名` 看一段和它的缩略图。段里写 `check \"要看什么\" [unless 条件]`：跑到这里停下（CHECKPOINT）等你看画面，没问题按回执 `use 名字 … from N` 接着跑。你存的段和用户示教的段是同一种：第一次跑通会记下起点，之后不在起点就不做。",
            "`vscreen note <one sentence>` keeps a lesson; `vscreen note save <name> \"steps\"` stores a segment (or add `--save <name>` to act), then `use <name>`; `vscreen note before \"<steps>\"` / `note after \"<steps>\"` run around every act (resume / pause a game, a popup that keeps coming back); `vscreen note human on` keeps human pacing for the app (as `act --human`); `vscreen note show <segment>` shows a segment and its contact sheet. Put `check \"what to look at\" [unless <cond>]` in a segment: the run stops there (CHECKPOINT) for you to look, then goes on with `use <name> … from N` from the reply. Segments you save and lessons the user teaches are one kind: the first run that goes through records its start, and it does nothing elsewhere after that.",
            listOf("note"),
            "段里可以写 check 检查点：跑到那里停下等你看一眼，再用 use 名字 … from N 接着跑。你自己存的段第一次跑通后也会核对起点。",
            "Segments can hold a check step: the run stops there for you to look, then use <name> … from N goes on. Segments you save check their start too once they have run through."),
        Ability("handoff", 1, 35,
            "有一步只能用户本人做（密码、验证码、人脸、扫码），或要用的应用用户正在用",
            "a step only the user can do (password, code, face, scanning), or the app I need is the one the user is using",
            "`vscreen handoff out` 把应用交到用户主屏并告诉他做什么；`vscreen handoff in` 把主屏上的应用接到副屏",
            "`vscreen handoff out` puts the app on the user's screen with what to do; `vscreen handoff in` takes the app from the user's screen",
            "`vscreen handoff out --note \"请输入验证码\"` 把应用交到主屏，用户做完你再 `handoff in` 或重新 launch。`handoff in` 要用户在手机上确认。用户正在主屏用的应用，launch 会返回 APP_IN_USE：先 `vscreen ask` 问用户，同意了再 `launch 应用 --asked`（另开一份）或 `handoff in`（接着他的页面）。用户在主屏打开了你正在用的应用，它就交给用户：动作返回 APP_WITH_USER，`vscreen events --wait` 等 handback 再继续。",
            "`vscreen handoff out --note \"enter the code\"` puts the app on the main screen; when the user is done, `handoff in` or launch again. `handoff in` is confirmed by the user on the phone. For an app the user is using on the main screen, launch returns APP_IN_USE: ask with `vscreen ask` first, then `launch <app> --asked` (a separate copy) or `handoff in` (from their page) once they agree. When the user opens the app you are using on the main screen, it is theirs: actions return APP_WITH_USER; wait for handback with `vscreen events --wait` before going on.",
            listOf("handoff"),
            "用户正在主屏用的应用，launch 返回 APP_IN_USE：先问用户，同意后加 --asked 或用 handoff in。用户在主屏打开你正在用的应用时它交给用户：动作返回 APP_WITH_USER、事件 handover；交回时有 handback 事件，先看画面再继续。",
            "For an app the user is using on the main screen, launch returns APP_IN_USE: ask the user, then add --asked or use handoff in. When the user opens the app you are using on the main screen it is theirs: actions return APP_WITH_USER and a handover event comes; a handback event comes when they hand it back: look first, then go on."),
        // 1.4.0-beta13 (rainbow): app sound by hand, the same as the app's 应用静音 page.
        Ability("sound", 46, 46,
            "应用没声音，或用户要让某个应用静音、恢复声音",
            "an app has no sound, or the user wants an app muted or its sound back",
            "`vscreen sound` 查看，`sound mute|unmute <应用>` 静音或恢复",
            "`vscreen sound` shows it, `sound mute|unmute <app>` mutes or unmutes",
            "`vscreen sound` 列出现在没声音的应用（why：on_screen 在副屏上、muted_by_hand 手动静音、left_silent 被留下的静音、clone 分身）、手动静音的、在副屏里也出声的、副屏静音开没开。`sound unmute <应用>` 恢复（包和 UID 两级都设成允许并核实），`sound mute <应用>` 在所有屏幕上静音直到恢复，`sound plays <应用> on|off` 让它在副屏里也出声，`sound screen on|off` 开关副屏静音。已经在播放的声音要等应用重新播放才变，告诉用户回到应用重新点播放。改动在 App 的 设置 → 应用静音 里也能看到和改。",
            "`vscreen sound` lists the apps silent now (why: on_screen, muted_by_hand, left_silent, clone), the ones muted by hand, the ones that keep sound on the screen, and whether screen muting is on. `sound unmute <app>` gives the sound back (allow on both the package and UID records, checked), `sound mute <app>` silences it on every screen until unmuted, `sound plays <app> on|off` lets it keep its sound on the screen, `sound screen on|off` turns screen muting on or off. Sound already playing changes only when the app plays again: tell the user to press play again. The user sees and changes the same in the app under Settings → App sound.",
            listOf("sound"),
            "新命令 sound：查看和管理应用静音（mute / unmute / plays / screen）。",
            "New command sound: see and manage app muting (mute / unmute / plays / screen)."),
        Ability("park", 56, 56,
            "切换或暂存应用", "switch or park apps",
            "`vscreen tasks` 查任务；`launch --keep` 切回原页；`handoff park/unpark` 暂存或取回",
            "`vscreen tasks` lists tasks; `launch --keep` switches without reopening; `handoff park/unpark` sets aside or restores apps",
            "`vscreen tasks` 列出副屏上应用的包名、任务号和前台状态。`vscreen launch <应用> --keep` 切回原页或启动新应用，不重建副屏。`vscreen handoff park <应用>` 把任务移到隐藏停放屏；`vscreen handoff unpark <应用>` 原样移回前台。停放后若当前目标仍是该应用，look/act 返回 APP_PARKED。轮次结束、停止或接管时，停放任务会被关闭。",
            "`vscreen tasks` lists the package, task ID and foreground status of apps on the screen. `vscreen launch <app> --keep` switches to the existing page or starts the app without rebuilding the screen. `vscreen handoff park <app>` moves its task to a hidden display; `vscreen handoff unpark <app>` brings it back to the foreground. Look and act return APP_PARKED if the current target is still parked. Parked tasks are closed at turn end, on Stop, or when the user takes over.",
            listOf("tasks"),
            "新增 tasks、launch --keep 和 handoff park/unpark：查看、切换或暂存副屏任务。",
            "New: tasks, launch --keep and handoff park/unpark to list, switch or park screen tasks."),
        // 2.0.0-beta11 (68): rainbow asked for agents to request temporary or permanent elevation, confirmed on the phone.
        Ability("elevate", 68, 68,
            "要做的事超出我的许可（如卸载、系统操作）",
            "the task needs more than my permission (uninstalling, system actions…)",
            "`vscreen permissions` 查权限，`vscreen elevate <类别> --why 原因` 申请",
            "`vscreen permissions` shows mine, `vscreen elevate <class> --why <reason>` asks",
            "任务开始前先 `vscreen permissions` 看自己的权限（status 的 permissions 字段也有），按它规划，并在用户问起时照实转告；权限变化时下一条回复开头会提示。手机在每条命令执行前按同一张表检查，不是靠你自觉。超出时开始前就申请，不要做到一半才撞上限制：`vscreen elevate destroy --why \"按你的要求卸载 3 个应用\"`。类别 operate/caution/destroy/system/rate，档位 careful/free/free_system。`--for round|today|always` 是你希望的范围（默认 round），用户在确认卡上可以改。批准后返回实际范围，之后的命令照常执行；临时许可到期自动收回，再需要就再申请。拒绝是 USER_DENIED：不要换别的办法绕过，告诉用户。60 秒没答复是 NEEDS_CONFIRM：请用户去手机上确认，然后重发。支付不能申请。",
            "Before a task, read your permissions with `vscreen permissions` (also `permissions` in status), plan by them and tell the user plainly when asked; when they change, the next reply starts with a notice. The phone checks every command against the same table; it does not rely on you. When you need more, ask before you start, not halfway through: `vscreen elevate destroy --why \"uninstall the 3 apps you named\"`. Classes operate/caution/destroy/system/rate, levels careful/free/free_system. `--for round|today|always` is the range you hope for (default round); the user may pick another on the card. Approval returns the actual range and later commands run as usual; a temporary permission ends by itself, ask again when needed. A denial is USER_DENIED: don't work around it, tell the user. No answer in 60 s is NEEDS_CONFIRM: ask the user to confirm on the phone, then send again. Payments cannot be asked for.",
            listOf("permissions", "elevate"),
            "新增 permissions 查看自己的权限；elevate 向用户申请临时或永久的更高许可，手机上指纹确认；权限变化时回复开头会提示。",
            "New: permissions shows your permission settings; elevate asks the user for temporary or permanent extra permission (fingerprint on the phone); replies start with a notice when permissions change."),
        Ability("look", 1, 36,
            "画面上的字太小、是图标，或者根本没有字（游戏、画布、图表），或想回看刚才发生了什么",
            "the text is too small or an icon, or there is no text at all (games, canvas, charts), or I need to see what just happened",
            "`vscreen look` 要图：现在的画面、放大一块、回看录像都用它；`vscreen spots` 找可点的色块",
            "`vscreen look` asks for pictures: the screen now, a region up close, or the recording; `vscreen spots` finds tappable color patches",
            "回执只在画面变了或出错时附一张压缩图；要看就 `vscreen look`（`--detail high` 更清楚）；`vscreen look x1,y1,x2,y2` 放大一块；`vscreen look --last 30s` 回看（`--every 0.3s` 更密，`--from 14:03:10` 指定时间，`--layout single|strip|3x4` 排法，`--marks off` 去标注，超过 8 张用 `--page 2`）；`vscreen spots` 找色块（`--color` 只找一种颜色）（本地，不调模型）。zoom、frames 是 look 的旧写法。",
            "Replies attach one compact picture only when the screen changed or something failed; to see it, `vscreen look` (`--detail high` for clearer); `vscreen look x1,y1,x2,y2` zooms into a region; `vscreen look --last 30s` replays (`--every 0.3s` denser, `--from 14:03:10` a given time, `--layout single|strip|3x4`, `--marks off`, `--page 2` past 8 pictures); `vscreen spots` finds color patches (`--color` for one color) (local, no model). zoom and frames are old forms of look.",
            listOf("look", "zoom", "spots", "frames"),
            "排法写成一个词 `--layout 3x4`（之前文档里的 `grid 3x4` 会被拆成两段）；只有画面变了或出错时回执才附图。",
            "A layout is one word, `--layout 3x4` (the `grid 3x4` the docs showed is split in two by the shell); replies attach a picture only when the screen changed or something failed."),
        Ability("trouble", 1, 47,
            "连不上手机、命令一直失败或出了怪问题",
            "the phone cannot be reached, commands keep failing, or something odd happens",
            "先分清是没开还是坏了：没在运行、服务关着或收起是用户的选择，转告用户打开即可，不用修；`vscreen status` 里 ready 为假时才是故障，`vscreen fix` 修它列出的项，也恢复被留下静音的应用",
            "First tell off from broken: not running, service off or hidden is the user's choice; tell the user to open it, nothing to repair. Only `vscreen status` with ready false is a fault, and `vscreen fix` repairs what it lists and gives sound back to apps left silent",
            "命令说 Agent-VScreen 没在运行、服务被关掉（DISABLED）或禁止了访问时，这不是故障：转告用户打开 App 或打开服务，不要让用户“修复”。`vscreen status`（ready、readiness.blocking、remote.user_action 说明要用户做什么）为假时才是故障：`vscreen fix` 修它列出的项（改系统设置的步骤手机上会问用户），`vscreen diag` 导出诊断交给开发者。",
            "When a command says Agent-VScreen is not running, the service was turned off (DISABLED) or access is blocked, that is not a fault: tell the user to open the app or turn the service on; don't ask them to repair anything. Only `vscreen status` with ready false is a fault (readiness.blocking and remote.user_action say what the user must do): `vscreen fix` repairs what it lists (steps that change system settings ask the user), `vscreen diag` exports diagnostics for the developer.",
            listOf("status", "fix", "diag"),
            "vscreen fix 不带参数时也会恢复被留下静音的应用，能自己重启前台服务和内置 Tailscale；结果里的 fixed 是修完再检查的结果。",
            "vscreen fix without arguments also gives sound back to apps left silent, and can restart the foreground service and the built-in Tailscale itself; fixed in its result is a check made afterwards."),
    )

    /** Which ability a command belongs to (null = not disclosed: AbilitiesTest fails on that). */
    fun of(command: String): Ability? = all.firstOrNull { command in it.commands }

    /** Abilities whose disclosure changed after [code]: added or reworded. */
    fun changedSince(code: Int): List<Ability> = all.filter { it.changed > code || it.since > code }

    /** The commands nobody tells agents about (must stay empty). */
    fun undisclosed(): List<String> = CommandSpec.commands.map { it.name }.filter { of(it) == null }

    /** For an agent that uses MCP or raw HTTP, the CLI words become the tool / command names. */
    fun forVia(text: String, via: Via): String = when (via) {
        Via.CLI -> text
        Via.MCP -> text.replace("`vscreen ", "`").replace("vscreen ", "")
        Via.HTTP -> text.replace("`vscreen ", "`").replace("vscreen ", "")
    }

    enum class Via { CLI, MCP, HTTP }
}
