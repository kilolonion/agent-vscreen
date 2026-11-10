# Agent-VScreen 命令与代码参考 / Command and code reference

> 由代码里的命令表和代码表生成，不要手改。Generated from the command and code tables in the source; do not edit by hand.

# 中文

## 命令

### `launch <应用名|包名> [--landscape] [--portrait] [--restart] [--async]`

打开应用（找不到就 apps 关键词）。副屏方向按应用声明的方向建；被系统缩放会自动处理。
返回四段；【做了什么】里还会列出你以前给这个应用记的 note、存的段、前置/收尾步骤。

- `--landscape` 副屏按横屏建
- `--portrait` 副屏按竖屏建
- `--restart` 冷启动（先结束应用）
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate` · 在副屏队列里执行

### `act ["步骤; 步骤; …"] [--stop <条件>] [--watch <时长>] [--fast] [--bare] [--keep <notify|wake>] [--for <时长>] [--save <名字>] [--async]`

做一段操作（最多 40 步，用 ; 或换行分开），返回四段。不带步骤 = 看一眼（等画面稳定，最多 3 秒）。
步骤：
      tap 目标 | press 目标 [时长，如 1.2s；纯数字是毫秒] | swipe x1 y1 x2 y2 [时长] | drag x1 y1 x2 y2 [x3 y3 …] [时长]
      scroll up|down|left|right [#N | x y] | type 文字 | type+ 文字（追加） | key 键名 | back | enter
      wait <条件> [or <条件>] [最长时长] | wait 2s | use 段名
      目标 = x y | #N（上次【现在】列表里的编号）| 画面上的字（先找完全一样的，再找包含的；只写一个数＝点画面上的这个数字）
      键名：back enter tab del space esc search menu up down left right paste copy cut page_up page_down，或数字 keycode（没有 home：副屏没有桌面）
每个动作后可加 until <条件> [or <条件>] [最长时长，默认 5s]；没写就等画面稳定（最多 3 秒；画面只剩循环小动画也算稳定）。
条件：text:字 | gone:字 | change[:x1,y1,x2,y2] | still[:区域]（完全不动）| stable（稳定，容许循环小动画）| color:x,y=#RRGGBB[~容差] | app:包名或界面名
一步没达到预期，这段就停在这一步，后面的不做，结果是失败（退出码 1，outcome=missed/refused）：
      until 到时间没等到；字画面上找不到；同样的字有好几处（AMBIGUOUS，会列出每一处的坐标，改用坐标）；#N 的元素已经不在原位（STALE_ELEMENT）；
      type 没有获得焦点的输入框、或输入后框里没有你的字。type 前先点输入框，【现在】里它标“可输入 有焦点”。
真正付钱的按钮（支付、提交订单、转账）、用户设为“每次询问”的应用（用户选“谨慎”档时还有删除等不可撤销的按钮）：手机上会弹出确认，用户点了允许才会做（最多等 60 秒；没答复是 CONFIRM_PENDING，告诉用户去手机上允许，再重发）。
例：act "tap 搜索; type 耳机; key enter until text:综合"
        act "drag 300 900 800 900" --watch 2s
        act --stop "text:到货" --keep notify --for 2h

- `--stop <条件>` 整段一直盯着，满足就停下，后面的步骤不做
- `--watch <时长>` 步骤做完再看这么久（会动的画面）
- `--fast` 动作之间不等画面稳定（游戏、连续点击）；最后一步仍会等
- `--bare` 这一段不做这个应用记下的前置/收尾步骤
- `--keep <notify|wake>` 本轮结束后继续盯 --stop 的条件，看到就通知用户（notify）或发一条事件给调用方（wake，用 events 取），然后结束；重启后还在
- `--for <时长>` --keep 盯多久（默认 1 小时，最长 12 小时）
- `--save <名字>` 把这段步骤存成这个应用的段，以后 use 名字
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate`（不带步骤、或只有 wait 的 act 只需要 look） · 在副屏队列里执行

### `note [文字] | before "<步骤>" | after "<步骤>" | delete <编号|before|after|段名>`

给当前应用记笔记（不带参数 = 列出笔记、段和前置/收尾步骤）。下次 launch 会列出来，并标明是谁、在应用哪个版本时记的。
before / after：每段 act 开始前先做 before 的步骤，做完、观察完再做 after 的步骤（--bare 跳过）。
例：会自己走的画面（游戏、播放器）记 before = 继续的步骤、after = 暂停的步骤，你思考时画面就停着；每次进页面都弹的窗记 before = 关掉它的步骤。

权限范围: `operate` · 在副屏队列里执行

### `events [--wait <时长>] [--again]`

取盯守（act --keep wake）看到条件时发来的事件。事件有序号，取走后在你下一次 events 时才删除（中途断了不会丢）。
这个会话没有正在运行的 --keep wake 盯守时不等，直接说明。

- `--wait <时长>` 没有事件时最多等这么久（默认不等，最长 120s）
- `--again` 把上一次取走的那批再给一遍

权限范围: `look`

### `task [任务号] | cancel <任务号> [--wait <时长>]`

查看用 --async 开始的任务：不带参数列出本会话的任务；带任务号返回它的状态，做完了就是它的完整结果；cancel 让它在下一步之前停下。

- `--wait <时长>` 任务没做完时最多等这么久（最长 120s）

权限范围: `look`

### `title [文字]`

在悬浮窗上显示你正在做什么（最多 40 字，给用户看；不带文字 = 清除；这一轮结束时自动清除）。

权限范围: `operate`

### `done`

这一轮做完了：副屏马上省电暂停、页面保留 10 分钟，别的会话可以接手（不 done 的话安静满 N 分钟自动结束，N 在 App 里设）。

权限范围: `operate` · 在副屏队列里执行

### `keep | off <名字|all>`

列出 / 停止本轮之后的盯守（用 act --stop … --keep 开始）。

权限范围: `operate`

### `stop [包名] [--force]`

关掉副屏上的应用。

- `--force` 结束整个应用（主屏上的也会被结束）：手机上会请用户确认

权限范围: `operate`（--force 需要 system） · 在副屏队列里执行

### `release`

立即释放副屏。

权限范围: `operate` · 在副屏队列里执行

### `handoff in [包名] | out [包名] [--note <说明>]`

in：把用户主屏上的应用移到副屏接着做（返回四段；手机上会请用户确认）；out：交还到主屏，需要用户本人操作时用。

- `--note <说明>` out 时给用户的通知里写什么

权限范围: `operate`（in 需要 system） · 在副屏队列里执行

### `status`

后台操作是否可用、副屏、权限、应用限制、正在盯守的条件、排队中的命令。

权限范围: `look`

### `apps [关键词]`

已安装应用和包名。

权限范围: `look`

### `spots [x1,y1,x2,y2] [--color <#RRGGBB>]`

本地按颜色找色块（不调模型），列出中心坐标和标注图。

- `--color <#RRGGBB>` 只找这种颜色

权限范围: `look` · 在副屏队列里执行

### `zoom x1,y1,x2,y2 [--scale <倍数>]`

放大看一块区域（图边上标着副屏坐标）。

- `--scale <倍数>` 放大倍数（默认按区域大小定）

权限范围: `look` · 在副屏队列里执行

### `collect [--pages <页数>] [--until <文字>] [--async]`

从当前位置往下一页页滚，收集每一行文字（去重）。

- `--pages <页数>` 最多翻几页（默认 8，最多 40）
- `--until <文字>` 看到这段文字就停
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate` · 在副屏队列里执行

### `fix [code] [--open]`

修复 status 里列出的问题。会改系统设置或在主屏打开设置页的步骤：手机上会请用户确认。

- `--open` 需要用户手动处理时，在主屏打开对应页面

权限范围: `system`

### `diag`

导出一份诊断（版本、状态、最近的日志和被吞掉的异常；不含你输入的文字和截图），返回文本，出问题时交给开发者。

权限范围: `look`

## 结果代码

| code | next | 含义 |
|---|---|---|
| `OK` | `none` | 按预期完成。 |
| `STEP_MISSED` | `look` | 一步做了，但没等到它期待的结果（until 超时、wait 超时、输入没进去）。这段停在那一步，后面的没做。看截图再决定。 |
| `NOT_FOUND` | `look` | 画面上没有你写的字、编号或段名。返回里列了相近的字和输入框的坐标。 |
| `AMBIGUOUS` | `fix_command` | 你写的字在画面上有好几处，没点。返回里列了每一处的坐标：改用坐标或 #N。 |
| `STALE_ELEMENT` | `look` | #N 指的元素已经不在上次列表里的位置（页面变了）。没点。重新看一眼再用新的编号。 |
| `OUT_OF_BOUNDS` | `fix_command` | 坐标在副屏外面。 |
| `INVALID_STEP` | `fix_command` | 步骤写法不对，整段没有执行。 |
| `INVALID_ARGS` | `fix_command` | 参数不对，命令没有执行。 |
| `UNKNOWN_OPTION` | `fix_command` | 这个命令没有这个参数，命令没有执行。 |
| `UNKNOWN_COMMAND` | `fix_command` | 没有这个命令。点击、输入、滑动、等待都写成 act 的步骤。 |
| `FOCUS_ELSEWHERE` | `look` | 有焦点的输入框不是你刚点的地方，没有输入。 |
| `TYPE_FAILED` | `look` | 文字没能送进去。 |
| `NO_CHANGE` | `look` | 这一步做了，但之后画面没有可见的变化（可能没点中，或这里本来就不会变）。 |
| `CONFIRM_PENDING` | `ask_user` | 这一步要用户在手机上确认（付费/不可撤销/用户设为每次询问的应用/会影响主屏的操作），用户还没答复，没做。请用户在手机通知里点“允许”，然后把这一段重发一遍。 |
| `USER_DENIED` | `ask_user` | 用户在手机上拒绝了这一步。不要重试，问用户想怎么办。 |
| `CONFIRM_REQUIRED` | `ask_user` | 同 CONFIRM_PENDING（旧名字）。 |
| `APP_BLOCKED` | `ask_user` | 用户禁止后台操作这个应用。不要绕过，也不要从别的入口进同一个应用。 |
| `APP_READ_ONLY` | `ask_user` | 用户把这个应用设成只读：能看、滚动、返回，不能点击/输入/滑动。 |
| `USER_TAKEOVER` | `ask_user` | 用户正在自己操作副屏。停下告诉用户，等用户交还。 |
| `USER_STOPPED` | `ask_user` | 用户按了“停止 AI”。所有操作都被拒绝，只有用户能解除；把返回里怎么解除的那句转告用户。 |
| `REREAD_REQUIRED` | `look` | 用户刚接管过副屏，页面可能变了。这次的返回就是现在的画面，看清再继续。 |
| `HOST_RESTARTED` | `look` | Agent-VScreen 进程重启过，上一轮的任务被打断。这次的返回就是现在的画面，不要假设之前的步骤都做完了。 |
| `MAIN_SCREEN_CHANGED` | `ask_user` | 这段前后主屏的前台应用或任务变了：可能是用户自己切的，也可能被打扰了。先告诉用户。 |
| `SCOPE_DENIED` | `ask_user` | 这个令牌没有做这件事的权限（用户在 App 里给令牌定的范围）。 |
| `PROTECTED_APP` | `none` | 这是 Agent-VScreen 自己、桌面或系统界面，不能结束。 |
| `NO_SCREEN` | `fix_command` | 现在没有副屏：先 launch 一个应用。 |
| `NO_APP` | `fix_command` | 没有这个应用，或副屏上没有应用。apps <关键词> 查包名。 |
| `NO_FRAME` | `retry_later` | 副屏还没有画面，稍后再试。 |
| `NO_SESSION` | `fix_command` | 请求里没有会话名。 |
| `BUSY` | `retry_later` | 副屏现在归另一个会话。返回里写了归谁、多久后让出。 |
| `QUEUE_TIMEOUT` | `retry_later` | 前面的命令还没做完，这条排队超过 60 秒，没有执行。 |
| `CANCELLED` | `none` | 调用方断开了或取消了任务，命令在下一步之前停下。 |
| `CONNECTION_LOST` | `ask_user` | 后台特权连接断了（Shizuku 停了），副屏和上面的应用已经没了，不是你的操作造成的。告诉用户；恢复后重新 launch。 |
| `NOT_CONNECTED` | `ask_user` | 后台连接没有就绪。vscreen fix not_connected，或请用户打开 Agent-VScreen。 |
| `DISABLED` | `ask_user` | 用户关掉了 Agent-VScreen 的服务。 |
| `CREATE_FAILED` | `ask_user` | 副屏没建出来。status 里有原因。 |
| `STALE_SCREEN` | `look` | 这个操作是针对已经重建过的旧副屏的。 |
| `NOT_SUPPORTED` | `ask_user` | 这台手机做不了这个操作（副屏服务不可用）。status 的 degraded 里有原因。 |
| `INPUT_FAILED` | `look` | 系统没有接受这次输入注入。 |
| `APP_NOT_FOUND` | `fix_command` | 找不到这个包的启动入口。 |
| `LAUNCH_FAILED` | `look` | 应用没启动起来。 |
| `LAUNCH_RESOLVER` | `fix_command` | 启动后停在系统的“打开方式”选择器：这个包没有桌面入口。 |
| `LAUNCHED_ON_MAIN_SCREEN` | `ask_user` | 系统把应用开到了主屏而不是副屏。不要在主屏上继续，告诉用户。 |
| `LAUNCH_NOT_ON_TOP` | `look` | 启动后副屏最前面是别的应用。 |
| `HANDOFF_NO_TASK` | `ask_user` | 主屏（或副屏）上没有可以移动的这个应用。 |
| `HANDOFF_DENIED` | `ask_user` | 系统没有让应用在两块屏之间移动。 |
| `CLOSE_FAILED` | `look` | 没能只关掉副屏上的任务。 |
| `MAIN_SCREEN_TASK` | `none` | 这个任务在主屏上，没有动它。 |
| `KEEP_LIMIT` | `fix_command` | 同时盯守的条件到上限了：先 keep off 一个。 |
| `TASK_NOT_FOUND` | `fix_command` | 没有这个任务号（任务结果保留 30 分钟）。task 列出本会话的任务。 |
| `TASK_RUNNING` | `retry_later` | 任务还在执行。 |
| `BAD_TOKEN` | `ask_user` | 令牌缺失、不对或已被用户撤销。请用户在 Agent-VScreen 的“接入 agent”里重新复制接入的话。 |
| `BAD_SIGNATURE` | `ask_user` | 请求签名不对或时间差太大（命令行会自动校时重试一次）。 |
| `RATE_LIMITED` | `retry_later` | 这个来源连续用错令牌，被暂时拒绝。 |
| `SERVER_BUSY` | `retry_later` | 同时进来的连接太多。 |
| `BAD_HOST` | `fix_command` | 请求的 Host 不是这台手机的地址或名字。 |
| `BAD_JSON` | `fix_command` | 请求体不是合法的 JSON。 |
| `BAD_REQUEST` | `fix_command` | 请求格式不对。 |
| `BROWSER_REFUSED` | `none` | 不接受网页发起的请求。 |
| `METHOD` | `fix_command` | 这个地址要用另一种 HTTP 方法。 |
| `NOT_ALLOWED` | `ask_user` | 其它设备访问没有打开或没有登录。 |
| `NO_SHOT` | `look` | 这张截图已经不在了（只留最近 300 张，副屏释放时清空）。重新看一眼。 |
| `PEER_DENIED` | `ask_user` | 这台设备不在用户的允许名单里。 |
| `REPLAYED` | `fix_command` | 这个签名请求已经收到过一次。每个请求用新的 Nonce。 |
| `REQUEST_TIMEOUT` | `retry_later` | 请求没有在时限内发完。 |
| `SIGNATURE_REQUIRED` | `ask_user` | 从其它设备来的请求必须签名（vscreen 命令行会签名）。MCP 等不能签名的客户端：请用户在 其它设备 里关掉“只接受签名请求”。 |
| `TASK_STARTED` | `none` | 任务已开始，用 task <号> 取结果。 |
| `TIME_SKEW` | `ask_user` | 调用方的时钟和手机差得太多，签名因此不被接受。校准调用方的时钟。 |
| `OPERATION_FAILED` | `look` | 执行出错，返回里有原因。 |
| `INTERNAL_ERROR` | `none` | 工具内部错误。把这段报错发给开发者（vscreen diag 能导出诊断）。 |


# English

## Commands

### `launch <app name|package> [--landscape] [--portrait] [--restart] [--async]`

open an app (if not found, apps <keyword>). The screen orientation follows what the app declares; system scaling is handled automatically.
Returns four blocks; [Done] also lists the notes, saved segments and before/after steps you kept for this app.

- `--landscape` build the screen in landscape
- `--portrait` build the screen in portrait
- `--restart` cold start (stops the app first)
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate` · runs in the screen queue

### `act ["step; step; …"] [--stop <condition>] [--watch <duration>] [--fast] [--bare] [--keep <notify|wake>] [--for <duration>] [--save <name>] [--async]`

do a stretch of steps (up to 40, separated by ; or newlines), returns four blocks. No steps = take a look (waits for the picture to settle, up to 3 s).
steps:
      tap <target> | press <target> [duration, e.g. 1.2s; a bare number is ms] | swipe x1 y1 x2 y2 [duration] | drag x1 y1 x2 y2 [x3 y3 …] [duration]
      scroll up|down|left|right [#N | x y] | type <text> | type+ <text> (append) | key <name> | back | enter
      wait <condition> [or <condition>] [max duration] | wait 2s | use <segment>
      target = x y | #N (number in the last [Now] list) | text on the screen (exact match first, then contains; a single number = tap that number on the screen)
      keys: back enter tab del space esc search menu up down left right paste copy cut page_up page_down, or a numeric keycode (no home: the screen has no launcher)
After each action you may add until <condition> [or <condition>] [max duration, default 5s]; without it the picture is awaited to settle (up to 3 s; a picture left with only a small looping animation counts as settled).
conditions: text:<word> | gone:<word> | change[:x1,y1,x2,y2] | still[:region] (no motion at all) | stable (settled, small looping animation allowed) | color:x,y=#RRGGBB[~tolerance] | app:<package or screen name>
A step that does not reach what it expects stops the stretch there, skips the rest, and the result is a failure (exit code 1, outcome=missed/refused):
      until timed out; text not found on the screen; the same text in several places (AMBIGUOUS, every place is listed with its point: use coordinates); the element of #N is no longer where it was (STALE_ELEMENT);
      type found no focused input box, or your text is not in the box afterwards. Tap the input box before type; in [Now] it is marked "input focused".
Buttons that actually spend money (pay, place order, transfer) and apps the user set to "ask every time" (in the user's "careful" mode also irreversible buttons such as delete): the phone asks the user, and the step is done only after the user allows it (waits up to 60 s; no answer is CONFIRM_PENDING: tell the user to allow it on the phone, then send again).
e.g. act "tap Search; type headphones; key enter until text:All"
         act "drag 300 900 800 900" --watch 2s
         act --stop "text:In stock" --keep notify --for 2h

- `--stop <condition>` watched for the whole stretch; when met it stops and skips the rest
- `--watch <duration>` keep looking this long after the steps (moving pictures)
- `--fast` do not wait for the picture to settle between actions (games, rapid taps); the last step still waits
- `--bare` this stretch skips the before/after steps kept for this app
- `--keep <notify|wake>` after this turn keep watching the --stop condition; when seen, notify the user (notify) or send the caller an event (wake, fetched with events), then end; survives a restart
- `--for <duration>` how long --keep watches (default 1 hour, at most 12 hours)
- `--save <name>` save these steps as a segment of this app, later: use <name>
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate` (an act with no steps, or only wait steps, needs just look) · runs in the screen queue

### `note [text] | before "<steps>" | after "<steps>" | delete <number|before|after|segment>`

keep notes for the current app (no arguments = list notes, segments and before/after steps). The next launch lists them, with who wrote each and for which app version.
before / after: every act first does the before steps, and after acting and looking does the after steps (--bare skips them).
e.g. for a picture that moves by itself (a game, a player) keep before = the steps that resume and after = the steps that pause, so it stands still while you think; for a popup shown on every visit keep before = the steps that close it.

scope: `operate` · runs in the screen queue

### `events [--wait <duration>] [--again]`

fetch events that watches (act --keep wake) sent when their condition was seen. Events are numbered and are deleted only at your next events call (nothing is lost if the connection drops in between).
Without a running --keep wake watch in this session it does not wait and says so.

- `--wait <duration>` wait up to this long when there is none (default no wait, at most 120s)
- `--again` return the previously fetched batch again

scope: `look`

### `task [task id] | cancel <task id> [--wait <duration>]`

look at tasks started with --async: no argument lists this session's tasks; with a task id it returns the state, and the full result once finished; cancel stops it before its next step.

- `--wait <duration>` wait up to this long while the task is unfinished (at most 120s)

scope: `look`

### `title [text]`

show what you are doing on the floating window (up to 40 characters, for the user; no text = clear; cleared when this turn ends).

scope: `operate`

### `done`

this turn is finished: the screen pauses to save power at once, the page is kept 10 minutes, and other sessions can take over (without done, it ends after N quiet minutes, set in the app).

scope: `operate` · runs in the screen queue

### `keep | off <name|all>`

list / stop watches that run after this turn (started with act --stop … --keep).

scope: `operate`

### `stop [package] [--force]`

close the app on the screen.

- `--force` stop the whole app (also on the main screen): the phone asks the user to confirm

scope: `operate` (--force needs system) · runs in the screen queue

### `release`

release the screen now.

scope: `operate` · runs in the screen queue

### `handoff in [package] | out [package] [--note <text>]`

in: move the app from the user's main screen to the background screen and carry on (returns four blocks; the phone asks the user to confirm); out: hand it back to the main screen when the user must act.

- `--note <text>` what the notification to the user says on out

scope: `operate` (in needs system) · runs in the screen queue

### `status`

whether background operation works, the screen, permissions, app rules, watches running, commands in the queue.

scope: `look`

### `apps [keyword]`

installed apps and packages.

scope: `look`

### `spots [x1,y1,x2,y2] [--color <#RRGGBB>]`

find color spots locally (no model), listing centers and a marked picture.

- `--color <#RRGGBB>` only this color

scope: `look` · runs in the screen queue

### `zoom x1,y1,x2,y2 [--scale <factor>]`

zoom into a region (screen coordinates on the picture's edges).

- `--scale <factor>` zoom factor (default follows the region size)

scope: `look` · runs in the screen queue

### `collect [--pages <count>] [--until <text>] [--async]`

scroll down page by page from here and collect every line of text (deduplicated).

- `--pages <count>` pages to go through at most (default 8, at most 40)
- `--until <text>` stop when this text is seen
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate` · runs in the screen queue

### `fix [code] [--open]`

fix the problems listed in status. Steps that change system settings or open a settings page on the main screen: the phone asks the user to confirm.

- `--open` when the user must act by hand, open the page on the main screen

scope: `system`

### `diag`

export a diagnostic bundle (versions, status, recent logs and swallowed exceptions; none of your typed text or screenshots) as text, to hand to the developer when something is wrong.

scope: `look`

## Result codes

| code | next | meaning |
|---|---|---|
| `OK` | `none` | Done as expected. |
| `STEP_MISSED` | `look` | A step was done but what it expected never came (until / wait timed out, typed text did not land). The stretch stopped there and skipped the rest. Look at the screenshot before deciding. |
| `NOT_FOUND` | `look` | The text, number or segment you named is not there. The reply lists similar text and input boxes with their points. |
| `AMBIGUOUS` | `fix_command` | The text you named is on the screen several times; nothing was tapped. The reply lists every place with its point: use coordinates or #N. |
| `STALE_ELEMENT` | `look` | The element #N pointed to is no longer where the last list had it (the page changed). Nothing was tapped. Look again and use the new numbers. |
| `OUT_OF_BOUNDS` | `fix_command` | The point is outside the screen. |
| `INVALID_STEP` | `fix_command` | A step is written wrong; nothing in the stretch ran. |
| `INVALID_ARGS` | `fix_command` | Bad arguments; the command did not run. |
| `UNKNOWN_OPTION` | `fix_command` | The command has no such option; it did not run. |
| `UNKNOWN_COMMAND` | `fix_command` | No such command. Tapping, typing, swiping and waiting are all act steps. |
| `FOCUS_ELSEWHERE` | `look` | The focused input box is not where you just tapped; nothing was typed. |
| `TYPE_FAILED` | `look` | The text could not be sent. |
| `NO_CHANGE` | `look` | The step was done but the picture did not visibly change afterwards (the tap may have missed, or nothing changes here). |
| `CONFIRM_PENDING` | `ask_user` | This step needs the user's confirmation on the phone (payment / irreversible / an app set to ask every time / something that affects the main screen); the user has not answered and it was not done. Ask the user to tap Allow in the phone's notification, then send this stretch again. |
| `USER_DENIED` | `ask_user` | The user denied this step on the phone. Don't retry; ask the user what they want. |
| `CONFIRM_REQUIRED` | `ask_user` | Same as CONFIRM_PENDING (old name). |
| `APP_BLOCKED` | `ask_user` | The user blocked background operation of this app. Don't get around it or enter the same app another way. |
| `APP_READ_ONLY` | `ask_user` | The user set this app to read-only: you may look, scroll and go back, not tap / type / swipe. |
| `USER_TAKEOVER` | `ask_user` | The user is operating the screen. Stop, tell the user, and wait for the hand-back. |
| `USER_STOPPED` | `ask_user` | The user pressed "Stop AI". Everything is refused and only the user can lift it; pass on the line that says how. |
| `REREAD_REQUIRED` | `look` | The user just had the screen and the page may have changed. This reply is the screen now: look, then carry on. |
| `HOST_RESTARTED` | `look` | The Agent-VScreen process restarted and the last task was cut off. This reply is the screen now; don't assume earlier steps completed. |
| `MAIN_SCREEN_CHANGED` | `ask_user` | The main screen's front app or tasks changed during this stretch: the user may have switched, or something disturbed it. Tell the user first. |
| `SCOPE_DENIED` | `ask_user` | This token is not allowed to do this (the scope the user gave the token in the app). |
| `PROTECTED_APP` | `none` | This is Agent-VScreen itself, the launcher or system UI; it cannot be stopped. |
| `NO_SCREEN` | `fix_command` | There is no screen now: launch an app first. |
| `NO_APP` | `fix_command` | No such app, or no app on the screen. apps <keyword> finds packages. |
| `NO_FRAME` | `retry_later` | The screen has no picture yet; try again shortly. |
| `NO_SESSION` | `fix_command` | The request names no session. |
| `BUSY` | `retry_later` | The screen belongs to another session now. The reply says whose and when it frees up. |
| `QUEUE_TIMEOUT` | `retry_later` | Earlier commands are still running; this one waited in the queue for over 60 s and did not run. |
| `CANCELLED` | `none` | The caller disconnected or cancelled the task; the command stopped before its next step. |
| `CONNECTION_LOST` | `ask_user` | The privileged background connection broke (Shizuku stopped); the screen and its app are gone, not because of your actions. Tell the user; launch again once it is back. |
| `NOT_CONNECTED` | `ask_user` | The background connection is not ready. Run vscreen fix not_connected, or ask the user to open Agent-VScreen. |
| `DISABLED` | `ask_user` | The user turned the Agent-VScreen service off. |
| `CREATE_FAILED` | `ask_user` | The screen could not be created. status says why. |
| `STALE_SCREEN` | `look` | This action was meant for an older screen that has been rebuilt. |
| `NOT_SUPPORTED` | `ask_user` | This phone cannot do this (the screen service is unavailable). See degraded in status. |
| `INPUT_FAILED` | `look` | The system did not accept the injected input. |
| `APP_NOT_FOUND` | `fix_command` | No launch entry for this package. |
| `LAUNCH_FAILED` | `look` | The app did not start. |
| `LAUNCH_RESOLVER` | `fix_command` | It stopped on the system's "Open with" chooser: the package has no launcher entry. |
| `LAUNCHED_ON_MAIN_SCREEN` | `ask_user` | The system opened the app on the main screen instead. Don't go on there; tell the user. |
| `LAUNCH_NOT_ON_TOP` | `look` | After starting, another app is in front on the screen. |
| `HANDOFF_NO_TASK` | `ask_user` | There is no such app task to move on the main screen (or the background screen). |
| `HANDOFF_DENIED` | `ask_user` | The system did not let the app move between the screens. |
| `CLOSE_FAILED` | `look` | Could not close just the task on the screen. |
| `MAIN_SCREEN_TASK` | `none` | This task is on the main screen; it was left alone. |
| `KEEP_LIMIT` | `fix_command` | Too many watches at once: turn one off with keep off. |
| `TASK_NOT_FOUND` | `fix_command` | No such task id (results are kept 30 minutes). task lists this session's tasks. |
| `TASK_RUNNING` | `retry_later` | The task is still running. |
| `BAD_TOKEN` | `ask_user` | The token is missing, wrong or was revoked by the user. Ask the user to copy the connect message again under Connect an agent in Agent-VScreen. |
| `BAD_SIGNATURE` | `ask_user` | The request signature is wrong or the clocks differ too much (the CLI corrects its clock and retries once). |
| `RATE_LIMITED` | `retry_later` | This source kept using wrong tokens and is refused for a while. |
| `SERVER_BUSY` | `retry_later` | Too many connections at once. |
| `BAD_HOST` | `fix_command` | The request's Host is not this phone's address or name. |
| `BAD_JSON` | `fix_command` | The request body is not valid JSON. |
| `BAD_REQUEST` | `fix_command` | The request is malformed. |
| `BROWSER_REFUSED` | `none` | Requests from web pages are not accepted. |
| `METHOD` | `fix_command` | This address needs another HTTP method. |
| `NOT_ALLOWED` | `ask_user` | Access from other devices is off or not signed in. |
| `NO_SHOT` | `look` | That screenshot is gone (only the last 300 are kept; they are cleared when the screen is released). Look again. |
| `PEER_DENIED` | `ask_user` | This device is not on the user's allow list. |
| `REPLAYED` | `fix_command` | This signed request was already received once. Use a fresh nonce for every request. |
| `REQUEST_TIMEOUT` | `retry_later` | The request did not arrive in time. |
| `SIGNATURE_REQUIRED` | `ask_user` | Requests from other devices must be signed (the vscreen CLI signs). For clients that cannot sign, such as MCP: ask the user to turn off "signed requests only" under Other devices. |
| `TASK_STARTED` | `none` | The task started; fetch its result with task <id>. |
| `TIME_SKEW` | `ask_user` | The caller's clock is too far from the phone's, so the signature is not accepted. Fix the caller's clock. |
| `OPERATION_FAILED` | `look` | It failed; the reply says why. |
| `INTERNAL_ERROR` | `none` | Internal tool error. Send it to the developer (vscreen diag exports diagnostics). |

