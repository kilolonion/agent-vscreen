# Agent-VScreen 命令与代码参考 / Command and code reference

> 由代码里的命令表和代码表生成，不要手改。Generated from the command and code tables in the source; do not edit by hand.

# 中文

## 命令

### `launch <应用名|包名> [--landscape] [--portrait] [--restart] [--asked] [--keep] [--async]`

打开应用（找不到就 apps 关键词）。默认按应用调整副屏；--keep 切回原页且不重建。
返回四段；【做了什么】里还会列出你以前给这个应用记的 note、存的段、前置/收尾步骤。

- `--landscape` 副屏按横屏建
- `--portrait` 副屏按竖屏建
- `--restart` 冷启动（先结束应用）
- `--asked` 用户正在主屏用这个应用时，已经问过用户并得到同意
- `--keep` 副屏保持现在的方向和尺寸：不重建、不按应用调整（应用没在上面时也一样）；已经有它的页面就切回原页面。不能和 --landscape/--portrait/--restart 一起用
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate` · 在副屏队列里执行

### `act ["步骤; 步骤; …"] [--stop <条件>] [--on <条件 => 一步>] [--hold] [--watch <时长>] [--fast] [--bare] [--human] [--keep <notify|wake>] [--for <时长>] [--save <名字>] [--full] [--async]`

做一段操作（最多 40 步，用 ; 或换行分开），返回四段。不带步骤 = 看一眼（等画面稳定，最多 3 秒）。
步骤：
      tap 目标 | press 目标 [时长，如 1.2s；纯数字是毫秒] | swipe x1 y1 x2 y2 [时长] | drag x1 y1 x2 y2 [x3 y3 …] [时长]
      闭环手势（按着边看边松手）：press 目标 hold until <条件> [最长]（默认 5s，上限 10s）| drag x1 y1 x2 y2 […] [时长] hold until <条件> [最长] | drag x1 y1 x2 y2 seek until <条件> [最长]（两点直线小步移动，默认 15s，上限 30s）；手势里的 text:/gone: 必须带 in x1,y1,x2,y2；不做 swipe … until，翻找用 repeat { swipe … } until text:…
      scroll up|down|left|right [#N | x y] | type 文字 | type+ 文字（追加） | type "文字" slow [200ms]（逐字输入） | key 键名 | key ctrl+a（组合键：ctrl/shift/alt + 键） | back | enter
      wait <条件> [or <条件>] [最长时长] | wait 2s | use 段名
      目标 = x y | #N（上次【现在】列表里的编号）| 画面上的字（先找完全一样的，再找包含的；只写一个数＝点画面上的这个数字）
      字里可写 *（任意字，整段字要对上：*蓝牙* 含蓝牙，共*条 以共开头以条结尾），text:/gone: 条件也一样；字@N = 从上往下第 N 处（@-1 最后一处）；字 +dx,+dy = 字旁边那一点（图标、开关）；坐标可写屏幕比例 50% 80%（换屏幕尺寸也点同一处）
      流程：try 一步 | try { 几步 }（没找到、没达到预期就跳过接着做）| if <条件> [最长] { … } [else { … } | else 一步]（看现在的画面，默认等 1.5s）| repeat 3 { … } | repeat [最多次数，最多 50] { … } until <条件>（每轮后看，默认最多 10 次，上限 50 次）。括号里可以换行、可以嵌套
      键名：back enter tab del space esc search menu up down left right paste copy cut page_up page_down，或数字 keycode（没有 home：副屏没有桌面）
每个动作后可加 until <条件> [or <条件>] [最长时长，默认 5s]（until text: 的字要是动作后才会新出现的字，动作前已在画面上的字不算）；没写就等画面稳定（最多 3 秒；画面只剩循环小动画也算稳定）；这次等待结束后（稳定了或到时间）画面和动作前一样时，再看最多 1.5 秒（type、编辑键和 --fast 的中间步骤不看），时刻从动作完成算起：变了就等它稳定，说“画面在动作 X 后才变化，Y 时稳定”（或“等到 Y 还在变”）；其间满足 --stop 条件就停在这一步，后面不做，不给 NO_CHANGE；仍没变才说“画面没有变化”（NO_CHANGE，只表示这段时间里没检测到变化）。
条件：text:字（动作后新出现的字；* 可以一个字也不配，9* 也匹配 9%）| gone:字 | change[:x1,y1,x2,y2] | still[:区域]（完全不动）| stable（稳定，容许循环小动画）| color:x,y=#RRGGBB[~容差] | app:包名或界面名
一步没达到预期，这段就停在这一步，后面的不做，结果是失败（退出码 1，outcome=missed/refused）：
      until 到时间没等到；字画面上找不到；同样的字有好几处（AMBIGUOUS，会列出每一处的坐标，改用坐标）；#N 的元素已经不在原位（STALE_ELEMENT）；
      type 没有获得焦点的输入框、或输入后框里没有你的字。type 前先点输入框，【现在】里它标“可输入 有焦点”。
支付按钮（付款、提交订单、转账）、用户设为“每次确认”的应用，以及这个 agent 的权限表里设为“问我”的类别（不可撤销操作、敏感操作等）：手机上会弹出确认，用户允许后才会执行；设为“不允许”的类别直接返回 SCOPE_DENIED（最多等 60 秒；没答复是 CONFIRM_PENDING，告诉用户去手机上允许，再重发）。
例：act "tap 搜索; type 耳机; key enter until text:综合"
        act "drag 300 900 800 900" --watch 2s
        act "drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s"
        act "drag 100 800 980 800 seek until text:70 in 400,600,700,700"
        act "press 540 1600 hold until text:9* in 300,1400,800,1500 8s"
        act --on "text:限时优惠 => tap 980,420" "tap 确定 until text:已确定"
        act --on "text:点我 => tap 点我 x3" --watch 10s
        act "tap 开始 until text:进行中" --hold
        act --stop "text:到货" --keep notify --for 2h

- `--stop <条件>` 整段一直盯着，满足就停下，后面的步骤不做
- `--on <条件 => 一步>` 短程规则（仅本次 act 生效，可写 1–3 条，不跨轮）：条件成立时执行一步（只允许 tap / key back / scroll / wait 时长，可选 x1–x3，默认 x1；条件不能是 stable / still；text: / gone: 连续 2 次独立读屏才触发，间隔 500ms；规则不能触发付款或“每次确认”应用）
- `--hold` 回复发出前暂停副屏前台应用并核实，下一条命令前自动恢复（最长 120 秒，仅 Android 14+；一直要就 note hold on）。注意：Android 15 起，暂停正在频繁刷新的应用时，系统可能直接结束它；暂停只对按帧计时的应用有效，按真实时钟计时的应用恢复后会直接跳过这段时间；暂停期间网络连接可能超时
- `--watch <时长>` 步骤做完再看这么久（会动的画面）
- `--fast` 只跳过动作之间的画面稳定等待（游戏、连续点击）；最后一步以及 repeat 内每轮的 until / 稳定等待仍会执行
- `--bare` 这一段不做这个应用记下的前置/收尾步骤
- `--human` 仿人节奏：每个动作前随机停 0.2–2.6 秒，点击在元素内略偏、按住和滑动的时长与位置有波动，打字分 1–3 个字一段（对节奏敏感的应用用；条件判断不变）。一直要就 note human on
- `--keep <notify|wake>` 本轮结束后继续盯 --stop 的条件，看到就通知用户（notify）或发一条事件给调用方（wake，用 events 取），然后结束；重启后还在
- `--for <时长>` --keep 盯多久（默认 1 小时，最长 12 小时）
- `--save <名字>` 把这段步骤存成这个应用的段，以后 use 名字
- `--full` 这次一定出完整回复；不带步骤时按上次 act 的时间窗重算【这段时间】并返回全屏图和全部元素
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate`（不带步骤、或只有 wait 的 act 只需要 look） · 在副屏队列里执行

### `note [文字] | before "<步骤>" | after "<步骤>" | save <段名> "<步骤>" | show <段名> | human on|off | reply quiet|full | hold on|off | delete <编号|before|after|段名>`

给当前应用记笔记（不带参数 = 列出笔记、段和前置/收尾步骤）。下次 launch 会列出来，并标明是谁、在应用哪个版本时记的。
before / after：每段 act 开始前先做 before 的步骤，做完、观察完再做 after 的步骤（--bare 跳过）。
例：会自己走的画面（游戏、播放器）记 before = 继续的步骤、after = 暂停的步骤，你思考时画面就停着；每次进页面都弹的窗记 before = 关掉它的步骤。
save：不执行，直接存下或改写一个段（示教存的段也一样），步骤写法和 act 相同，可以用 try / if / repeat、* 和 @N；note 列出的就是段的完整步骤。
human on：这个应用以后每段 act 都用仿人节奏（同 act --human）；off 关掉。
reply quiet|full：按当前 agent 保存默认回复模式（默认 quiet：符合预期时给短回复，full：每次都给完整回复），不需要先打开应用。
hold on|off：按当前 agent 保存默认是否开启 act --hold（默认 off，仅 Android 14+ 生效；Android 15 起，暂停正在频繁刷新的应用时，系统可能直接结束它），不需要先打开应用。

权限范围: `operate` · 在副屏队列里执行

### `events [--wait <时长>] [--again]`

取盯守（act --keep wake）看到条件时发来的事件。事件有序号，取走后在你下一次 events 时才删除（中途断了不会丢）。
这个会话没有正在运行的 --keep wake 盯守时不等，直接说明。

- `--wait <时长>` 没有事件时最多等这么久（默认不等，最长 120s）
- `--again` 把上一次取走的那批再给一遍

权限范围: `look`

### `task [任务号] | cancel <任务号> | recap [--wait <时长>]`

查看用 --async 开始的任务：不带参数列出本会话的任务；带任务号返回它的状态，做完了就是它的完整结果；cancel 让它在下一步之前停下；recap 返回本会话走过的页面和步骤摘要（不重新读屏，最多 600 字）。

- `--wait <时长>` 任务没做完时最多等这么久（最长 120s）

权限范围: `look`

### `tasks`

列出副屏上的应用任务（最前面的排第一）：包名、任务号、是不是前台。用来确认应用还在不在、有没有被系统挪走。停到停放屏（handoff park）的应用不在这里。

权限范围: `look` · 在副屏队列里执行

### `title [文字]`

在悬浮窗上显示你正在做什么（最多 40 字，给用户看；不带文字 = 清除；这一轮结束时自动清除）。

权限范围: `operate`

### `permissions`

这个 agent 现在的权限：每类操作是允许、每次确认还是不允许，临时许可何时结束，哪些应用每次确认、禁止或只读，以及固定边界。任务开始前看一次，按它规划；用户问起时照此转告。手机在每条命令执行前按同一张表检查。

权限范围: `look`

### `elevate <类别|档位> [--why <原因>] [--for <round|today|always>]`

提前向用户申请更高的许可，写明原因：手机上弹出确认（通知 → 指纹），用户选择本轮任务内、今天内或永久。类别 caution/destroy/system/operate/rate，档位 careful/free/free_system。
临时许可到期自动收回；支付不能申请（每次付款都单独确认）。命令遇到“不允许”的类别时也会弹出同样的确认。

- `--why <原因>` 为什么需要（用户在确认卡上看到，最多 120 字）
- `--for <round|today|always>` 希望的范围（默认 round；用户可以改）

权限范围: `look`

### `done`

这一轮做完了：副屏马上省电暂停、页面保留 10 分钟，别的会话可以接手（不 done 的话安静满 N 分钟自动结束，N 在 App 里设）。

权限范围: `operate` · 在副屏队列里执行

### `keep | off <名字|all>`

列出 / 停止本轮之后的盯守（用 act --stop … --keep 开始）。
另见 act --hold（两轮之间暂停应用）：Android 15 起，暂停正在频繁刷新的应用时，系统可能直接结束它。

权限范围: `operate`

### `stop [包名] [--force]`

关掉副屏上的应用。

- `--force` 结束整个应用（主屏上的也会被结束）：手机上会请用户确认

权限范围: `operate`（--force 需要 system） · 在副屏队列里执行

### `release`

立即释放副屏。

权限范围: `operate` · 在副屏队列里执行

### `handoff in [包名] | out [包名] | park <应用> | unpark <应用> [--note <说明>]`

in：把用户主屏上的应用移到副屏接着做（返回四段；手机上会请用户确认）；out：交还到主屏，需要用户本人操作时用。
handoff park <应用>：把副屏任务移到隐藏的停放屏；handoff unpark <应用>：移回副屏前台。当前目标仍在停放时，look/act 返回 APP_PARKED。轮次结束、Stop 或用户接管时清理停放任务。

- `--note <说明>` out 时给用户的通知里写什么

权限范围: `operate`（in 需要 system） · 在副屏队列里执行

### `sound [list] | mute <应用> | unmute <应用> | plays <应用> on|off | screen on|off`

应用静音：list 列出现在没声音的应用（谁静的）、手动静音的、在副屏里也出声的、副屏静音开没开；mute 让应用在所有屏幕上都不出声（直到 unmute）；unmute 恢复声音；plays 设这个应用在副屏里也出声；screen 开关副屏静音。已经在播放的声音要等应用重新播放才变。

权限范围: `system`（list 只需 look）

### `ask "<问题>" [--point] [--area] [--choose <甲|乙|丙>] [--text] [--wait <时长>] [--async]`

在手机上问用户（通知 + App 首页），等用户回答。默认 --point：用户在副屏的画面上点一下“你说的是哪个”，返回副屏坐标和那里的元素（#N）。说不清位置时用它，比文字描述准。
回答的是事实，不是许可：付款等仍按手机确认走。

- `--point` 在画面上点一下（默认）
- `--area` 在画面上框一块，返回框和框里的元素
- `--choose <甲|乙|丙>` 从 2–8 个选项里选一个
- `--text` 写一句话回答
- `--wait <时长>` 最多等多久（默认 50s，最长 5m；没答复是 ASK_PENDING，之后重发同一条 ask 取答案）
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate`

### `teach "<要演示什么>" [--name <名字>] [--wait <时长>] [--again] [--async]`

请用户在副屏上亲手做一遍（示教）：手机上弹出请求，用户接管副屏操作、做完审阅，存成这个应用的一段。返回段名和步骤；之后 act "use 段名 参数…" 照做。用户输入的字默认变成参数 {1} {2}…，用的时候按顺序填。
适合说不清、反复失败、或用户自己更清楚怎么做的流程。先 launch 到要演示的应用，再把页面整理回起始的样子（清掉自己输入的字、计算结果、弹窗），看一眼确认再发。

- `--name <名字>` 建议的段名（用户可以改）
- `--wait <时长>` 最多等多久（默认 50s，最长 5m；没做完是 ASK_PENDING，之后重发同一条 teach 取结果）
- `--again` 请用户重新演示一遍（不加时，同一条 teach 已有结果就原样返回，不会再问用户）
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate`

### `schedule ["时间"] | off <名字|all> [--name <名字>] [--for <时长>]`

预约你会来用手机的时间（定时任务用）：每个窗口开始前 3 分钟拉起服务、检查所选的权限来源（Shizuku 或 Root）和连接，没准备好就通知用户，结果作为事件发给本会话。不带参数列出预约和现在是否就绪。
时间写法："07:00"、"07:00,19:30"、"weekdays 08:30"、"mon,wed,fri 21:00"、"every 2h from 08:00 to 22:00"（间隔至少 30 分钟，最多 8 条）。

- `--name <名字>` 预约的名字（同名会替换；默认 w1、w2…）
- `--for <时长>` 每个窗口保持多久（默认 10m，最长 30m）

权限范围: `operate`

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

### `look [x1,y1,x2,y2] [--last <时长>] [--from <时间>] [--to <时间>] [--count <张数>] [--every <间隔>] [--page <页>] [--detail <清晰度>] [--layout <排法>] [--marks <on|off>]`

主动要图：不带参数是现在的画面；带区域是放大那一块；带 --last/--from/--count 是回看录像（每个动作前一张、圈出点的位置）。可以要更清楚、换排法、关掉标注、翻页。每次最多 8 张图、合计约 3 MB，多的用 --page 翻。

- `--last <时长>` 最近这么久（如 30s、2m）
- `--from <时间>` 从多久以前（20s）或钟点（14:03:10）开始
- `--to <时间>` 到多久以前或钟点为止（默认现在）
- `--count <张数>` 最多几帧（默认 12，最多 96）
- `--every <间隔>` 按固定间隔取（如 0.5s），和上一张一样的不重复
- `--page <页>` 超过 8 张图时看第几页
- `--detail <清晰度>` low、normal（默认）或 high
- `--layout <排法>` auto（默认）、single（一帧一张）、strip（一行 6 帧）或 3x4
- `--marks <on|off>` 图上的标尺和点击圈（默认 on）

权限范围: `look` · 在副屏队列里执行

### `frames [--last <时长>] [--from <时间>] [--to <时间>] [--count <张数>] [--every <间隔>] [--page <页>] [--detail <清晰度>] [--layout <排法>] [--marks <on|off>]`

look --last … 的旧写法：回看录像，默认是上一次返回的【这段时间】。

- `--last <时长>` 最近这么久（如 30s、2m）
- `--from <时间>` 从多久以前（20s）或钟点（14:03:10）开始
- `--to <时间>` 到多久以前或钟点为止（默认现在）
- `--count <张数>` 最多几帧（默认 12，最多 96）
- `--every <间隔>` 按固定间隔取（如 0.5s），和上一张一样的不重复
- `--page <页>` 超过 8 张图时看第几页
- `--detail <清晰度>` low、normal（默认）或 high
- `--layout <排法>` auto（默认）、single（一帧一张）、strip（一行 6 帧）或 3x4
- `--marks <on|off>` 图上的标尺和点击圈（默认 on）

权限范围: `look` · 在副屏队列里执行

### `zoom x1,y1,x2,y2 [--scale <倍数>] [--detail <清晰度>] [--marks <on|off>]`

look x1,y1,x2,y2 的旧写法：放大看一块区域（图边上标着副屏坐标）。

- `--scale <倍数>` 放大倍数（默认按区域大小定）
- `--detail <清晰度>` low、normal（默认）或 high
- `--marks <on|off>` 图上的标尺和点击圈（默认 on）

权限范围: `look` · 在副屏队列里执行

### `collect [--pages <页数>] [--from-top] [--until <文字>] [--async]`

从当前位置往下一页页滚，收集每一条（一张卡片或一行列表算一条，去重）。上面还有内容时会说；--from-top 先回到顶部。

- `--pages <页数>` 最多翻几页（默认 8，最多 40）
- `--from-top` 先滚回页面顶部再开始收集
- `--until <文字>` 看到这段文字就停
- `--async` 不等它做完：马上返回任务号，之后 task <号> 取结果（长操作、怕连接超时时用）

权限范围: `operate` · 在副屏队列里执行

### `fix [code] [--open]`

修复 status 里列出的问题，并把被留下静音的应用恢复声音；每项修完再检查一遍，fixed 是检查的结果。会改系统设置或在主屏打开设置页的步骤：手机上会请用户确认。

- `--open` 需要用户手动处理时，在主屏打开对应页面

权限范围: `system`

### `diag`

导出一份诊断（版本、状态、最近的日志和被吞掉的异常；不含你输入的文字和截图），返回文本，出问题时交给开发者。

权限范围: `look`

## 结果代码

| code | next | 含义 |
|---|---|---|
| `OK` | `none` | 按预期完成。 |
| `CHECKPOINT` | `look` | 段跑到 check 检查点停下了，等你看一眼画面：没问题就按回执里的命令接着跑（use 段名 … from N），不对就先改好再接着跑。 |
| `STEP_MISSED` | `look` | 一步做了，但没等到它期待的结果（until 超时、wait 超时、输入没进去）。这段停在那一步，后面的没做。看截图再决定。 |
| `NOT_READ` | `look` | 等待期间读屏没跟上（文字识别忙或失败），没有读到动作后的画面；不能据此判断画面上有没有目标字，看截图或稍后再试。 |
| `NOT_FOUND` | `look` | 画面上没有你写的字、编号或段名。返回里列了相近的字和输入框的坐标。 |
| `AMBIGUOUS` | `fix_command` | 你写的字在画面上有好几处，没点。返回里列了每一处的坐标：改用坐标或 #N。 |
| `STALE_ELEMENT` | `look` | #N 指的元素已经不在上次列表里的位置（页面变了）。没点。重新看一眼再用新的编号。 |
| `OUT_OF_BOUNDS` | `fix_command` | 坐标在副屏外面。 |
| `INVALID_STEP` | `fix_command` | 步骤写法不对，整段没有执行。 |
| `INVALID_ARGS` | `fix_command` | 参数不对，命令没有执行。 |
| `GESTURE_NEEDS_UNTIL` | `fix_command` | 闭环手势（hold / seek）必须带 until <条件>，整段没有执行。 |
| `GESTURE_NEEDS_AREA` | `fix_command` | 闭环手势里的 text: / gone: 条件必须带区域（in x1,y1,x2,y2），整段没有执行。 |
| `BAD_RULE` | `fix_command` | 短程规则（--on）写法不对，整条 act 没有执行。写法：--on "<条件> => <tap\|key back\|scroll\|wait 时长>[ x1..x3]"，最多 3 条，条件不能是 stable / still。 |
| `RULE_OFF` | `none` | 本轮的一条 --on 规则已停用（次数用完、前台切走、目标找不到/有歧义或执行后画面无变化），本轮内不再检查。 |
| `HOLD_UNSUPPORTED` | `none` | 当前系统或设备不支持 --hold（仅 Android 14 / API 34 及以上，或系统不支持 am freeze），本次未暂停应用，其它照常。 |
| `HOLD_REFUSED` | `none` | 本次拒绝或未能暂停该应用（系统/常驻/输入法/系统媒体进程、冻结未通过核实，或暂停时应用被系统结束）。其它操作照常完成。 |
| `UNKNOWN_OPTION` | `fix_command` | 这个命令没有这个参数，命令没有执行。 |
| `UNKNOWN_COMMAND` | `fix_command` | 没有这个命令。点击、输入、滑动、等待都写成 act 的步骤。 |
| `FOCUS_ELSEWHERE` | `look` | 有焦点的输入框不是你刚点的地方，没有输入。 |
| `TYPE_FAILED` | `look` | 文字没能送进去。 |
| `SNAP_BACK` | `look` | 拖动后松手弹回了起点（可能未拖过触发阈值或不支持拖放）。 |
| `NO_CHANGE` | `look` | 这一步做了，但之后画面没有可见的变化（可能没点中，或这里本来就不会变）。 |
| `UNPROVEN` | `look` | 这一步做在画面上一直自己在动的地方，之后的变化可能只是动画，不能说明它生效了。看图核对。 |
| `SCREEN_RESUMED` | `look` | 副屏刚从省电暂停中恢复；暂停期间应用不在前台，可能自己暂停或弹出菜单。以这次回复的画面为准。 |
| `NEEDS_CONFIRM` | `user_on_phone` | 需要用户在手机上的 Agent-VScreen 里强确认（风控维度，或你用 elevate 申请的权限）。这一步没有做。用户可能没看到通知：把回复里【等用户】的那句话发给用户，等用户说好了，再把这条命令原样重发。 |
| `CONFIRM_PENDING` | `user_on_phone` | 这一步要用户在手机上确认（付费/不可撤销/用户设为每次确认的应用/会影响主屏的操作），用户还没答复，没做。用户可能没看到通知：把回复里【等用户】的那句话发给用户，等用户说好了，再把这一段重发一遍。规则（--on）碰到这种按钮时不会发请求，整条 act 中止，这时没有【等用户】，要你自己来做这一步。 |
| `USER_DENIED` | `ask_user` | 用户在手机上拒绝了这一步。不要重试，问用户想怎么办。 |
| `CONFIRM_REQUIRED` | `user_on_phone` | 同 CONFIRM_PENDING（旧名字）。 |
| `APP_BLOCKED` | `ask_user` | 用户禁止后台操作这个应用。不要绕过，也不要从别的入口进同一个应用。 |
| `APP_IN_USE` | `ask_user` | 用户正在主屏上用这个应用。先 ask 用户，同意后 launch --asked（另开一份）或 handoff in（接着他的页面）。 |
| `APP_WITH_USER` | `ask_user` | 你在用的应用现在在用户手上（主屏）。等 handback 事件，或 launch 别的应用。 |
| `OCR_DOWN` | `none` | 文字识别不可用（两种引擎都失败）：元素只来自无障碍，画面上的字可能没列出来，以截图为准。 |
| `APP_PARKED` | `fix_command` | 这个应用被停到了停放屏（隐藏的停放显示，不在副屏上）：页面、登录都还在，但现在看不到也不能操作。用 vscreen handoff unpark <应用> 把它取回副屏，再继续；这期间副屏可以放别的应用。 |
| `APP_READ_ONLY` | `ask_user` | 用户把这个应用设成只读：能看、滚动、返回，不能点击/输入/滑动。 |
| `USER_TAKEOVER` | `ask_user` | 用户正在自己操作副屏。停下告诉用户，等用户交还（vscreen events --wait 收 handback）；副屏和页面会留着，不要 release 或重新 launch。 |
| `THERMAL_STOP` | `ask_user` | 过热保护已停止后台任务，不要重试。请用户在手机冷却后从首页电源菜单手动恢复。 |
| `USER_STOPPED` | `ask_user` | 用户按了“停止 AI”。所有操作都被拒绝，只有用户能解除；把返回里怎么解除的那句转告用户。 |
| `CONTROL_CHANGED` | `look` | 这条命令收到之后，用户接管、停止或交还过副屏：它计划时的画面可能已经不在了，剩下的动作都没做。act（不带步骤）看一眼现在的画面，再按看到的重发。 |
| `REREAD_REQUIRED` | `look` | 用户刚接管过副屏，页面可能变了，这次没有执行。只拒这一次：act（不带步骤）看一眼现在的画面，再重发。 |
| `HOST_RESTARTED` | `look` | Agent-VScreen 进程重启过，上一轮的任务被打断。这次的返回就是现在的画面，不要假设之前的步骤都做完了。 |
| `MAIN_SCREEN_CHANGED` | `ask_user` | 副屏这边的应用或任务跑到了主屏，打扰了用户正在用的主屏。停下告诉用户；handoff in 把它拿回副屏。用户自己在主屏切换应用不会报这条。 |
| `SCOPE_DENIED` | `ask_user` | 这个令牌没有做这件事的权限（用户在 App 里给令牌定的范围）。 |
| `PROTECTED_APP` | `none` | 这是 Agent-VScreen 自己、桌面或系统界面，不能在副屏打开、移屏、停放或结束。 |
| `NO_SCREEN` | `fix_command` | 现在没有副屏：先 launch 一个应用。 |
| `NO_APP` | `fix_command` | 没有这个应用，或副屏上没有应用。apps <关键词> 查包名。 |
| `NO_FRAME` | `retry_later` | 副屏还没有画面，稍后再试。 |
| `NO_SESSION` | `fix_command` | 请求里没有会话名。 |
| `BUSY` | `retry_later` | 副屏现在归另一个会话。返回里写了归谁（owner）、多久后自动让出（frees_in_s 秒；null 表示它正在执行命令）。 |
| `QUEUE_TIMEOUT` | `retry_later` | 前面的命令还没做完，这条排队超过 60 秒，没有执行。 |
| `CANCELLED` | `none` | 调用方断开了或取消了任务，命令在下一步之前停下。 |
| `CONNECTION_LOST` | `ask_user` | 后台特权连接断了（Shizuku 或 Root 服务停了），副屏和上面的应用已经没了，不是你的操作造成的。告诉用户；恢复后重新 launch。 |
| `NOT_CONNECTED` | `ask_user` | 副屏服务用不了：Shizuku（或 Root）停了或没授权，返回里写了是哪一种。把那句转告用户；只是服务关着或收起时不会报这条。 |
| `DISABLED` | `ask_user` | 用户关掉了 Agent-VScreen 的服务。 |
| `CREATE_FAILED` | `ask_user` | 副屏没建出来。status 里有原因。 |
| `STALE_SCREEN` | `look` | 这个操作是针对已经重建过的旧副屏的。 |
| `NOT_SUPPORTED` | `ask_user` | 这台手机做不了这个操作（副屏服务不可用）。status 的 degraded 里有原因。 |
| `INPUT_FAILED` | `look` | 系统没有接受这次输入注入。 |
| `APP_NOT_FOUND` | `fix_command` | 找不到这个包的启动入口。 |
| `APP_NOT_SUPPORTED` | `fix_command` | 这个应用不能在副屏上打开（例如 Agent-VScreen 自己）。 |
| `LAUNCH_FAILED` | `look` | 应用没启动起来。 |
| `LAUNCH_RESOLVER` | `fix_command` | 启动后停在系统的“打开方式”选择器：这个包没有桌面入口。 |
| `LAUNCHED_ON_MAIN_SCREEN` | `ask_user` | 系统把应用开到了主屏而不是副屏。不要在主屏上继续，告诉用户。 |
| `LAUNCH_NOT_ON_TOP` | `look` | 启动后副屏最前面是别的应用。 |
| `HANDOFF_NO_TASK` | `ask_user` | 主屏（或副屏）上没有可以移动的这个应用。 |
| `HANDOFF_DENIED` | `ask_user` | 系统没有让应用在两块屏之间移动。 |
| `CLOSE_FAILED` | `look` | 没能只关掉副屏上的任务。 |
| `MAIN_SCREEN_TASK` | `none` | 这个任务在主屏上，没有动它。 |
| `SCHEDULE_LIMIT` | `fix_command` | 预约到上限了（8 条）：先 schedule off 一条。 |
| `NOT_READY` | `ask_user` | Agent-VScreen 没有响应或还没准备好（被系统冻结、服务没开、Shizuku / Root 没运行）。命令行已经试过唤醒一次。把原因告诉用户；定时任务请先用 schedule 预约时间。 |
| `ASK_PENDING` | `user_on_phone` | 问题（或 teach 的演示请求）已经显示在手机上，用户还没做。用户可能没看到通知：把回复里【等用户】的那句话发给用户，等用户说好了，再把同一条 ask / teach 原样再发一次就能拿到结果（10 分钟内有效）。 |
| `ASK_DECLINED` | `ask_user` | 用户选择不回答这个问题。不要再问同一个问题，换个方式或告诉用户你停在了哪里。 |
| `USER_FEEDBACK` | `ask_user` | 用户没有照你问的方式回答，而是说了一句话（在 feedback 里，原话）。多半是你问错了、页面不对或理解偏了：按这句话调整，不要原样再问。 |
| `NOT_AT_START` | `look` | 示教段是从某个页面开始录的，副屏现在不在那里，所以一步都没做。回执写了起点的样子：先自己走到那个页面，再 use。 |
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
| `BODY_TOO_LARGE` | `fix_command` | 请求体太大（上限 1 MB）。 |
| `HEADERS_TOO_LARGE` | `fix_command` | 请求头太大。 |
| `REQUEST_TIMEOUT` | `retry_later` | 请求没有在时限内发完。 |
| `SIGNATURE_REQUIRED` | `ask_user` | 经外部 Tailscale App 从其它设备来的请求必须签名（vscreen 命令行会签名；经内置 Tailscale 来的不需要）。MCP 等不能签名的客户端：请用户在 其它设备 里关掉“只接受签名请求”，或改用内置 Tailscale。 |
| `TASK_STARTED` | `none` | 任务已开始，用 task <号> 取结果。 |
| `TIME_SKEW` | `ask_user` | 调用方的时钟和手机差得太多，签名因此不被接受。校准调用方的时钟。 |
| `UNSUPPORTED_MCP_PROTOCOL` | `fix_command` | 当前 MCP 服务端支持基于 initialize 握手的协议版本（2025-06-18 / 2025-03-26 / 2024-11-05）；请回退使用 initialize 重新连接。 |
| `OPERATION_FAILED` | `look` | 执行出错，返回里有原因。 |
| `INTERNAL_ERROR` | `none` | 工具内部错误。把这段报错发给开发者（vscreen diag 能导出诊断）。 |


# English

## Commands

### `launch <app name|package> [--landscape] [--portrait] [--restart] [--asked] [--keep] [--async]`

open an app (if not found, apps <keyword>). By default the screen fits the app; --keep switches to the existing page without rebuilding.
Returns four blocks; [Done] also lists the notes, saved segments and before/after steps you kept for this app.

- `--landscape` build the screen in landscape
- `--portrait` build the screen in portrait
- `--restart` cold start (stops the app first)
- `--asked` the user is using this app on the main screen and has agreed when asked
- `--keep` keep the screen's orientation and size as they are: no rebuild and no fitting it to the app (also when the app is not on it yet); an existing page of it is brought back. Not with --landscape/--portrait/--restart
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate` · runs in the screen queue

### `act ["step; step; …"] [--stop <condition>] [--on <cond => step>] [--hold] [--watch <duration>] [--fast] [--bare] [--human] [--keep <notify|wake>] [--for <duration>] [--save <name>] [--full] [--async]`

do a stretch of steps (up to 40, separated by ; or newlines), returns four blocks. No steps = take a look (waits for the picture to settle, up to 3 s).
steps:
      tap <target> | press <target> [duration, e.g. 1.2s; a bare number is ms] | swipe x1 y1 x2 y2 [duration] | drag x1 y1 x2 y2 [x3 y3 …] [duration]
      closed-loop gestures (judge while holding): press <target> hold until <cond> [max] (default 5s, max 10s) | drag x1 y1 x2 y2 […] [duration] hold until <cond> [max] | drag x1 y1 x2 y2 seek until <cond> [max] (2-point line in small steps, default 15s, max 30s); text:/gone: inside hold/seek must include in x1,y1,x2,y2; no swipe … until — use repeat { swipe … } until text:…
      scroll up|down|left|right [#N | x y] | type <text> | type+ <text> (append) | type "<text>" slow [200ms] (one character at a time) | key <name> | key ctrl+a (shortcut: ctrl/shift/alt + key) | back | enter
      wait <condition> [or <condition>] [max duration] | wait 2s | use <segment>
      target = x y | #N (number in the last [Now] list) | text on the screen (exact match first, then contains; a single number = tap that number on the screen)
      words may hold * (any characters; the whole text must match: *Wi-Fi* holds Wi-Fi), in text:/gone: conditions too; words@N = the N-th place from the top (@-1 the last); words +dx,+dy = the point that far beside them (an icon, a switch); coordinates may be shares of the screen, 50% 80% (same place on any screen size)
      flow: try <step> | try { steps } (not found or not as expected: skip and go on) | if <condition> [max] { … } [else { … } | else <step>] (the screen as it is, waits up to 1.5 s by default) | repeat 3 { … } | repeat [at most, max 50] { … } until <condition> (checked after each round, default 10, max 50). Braces may span lines and nest
      keys: back enter tab del space esc search menu up down left right paste copy cut page_up page_down, or a numeric keycode (no home: the screen has no launcher)
After each action you may add until <condition> [or <condition>] [max duration, default 5s] (until text: must match text that newly appears after the action; text already on screen beforehand does not count); without it the picture is awaited to settle (up to 3 s; a picture left with only a small looping animation counts as settled); if after that wait (settled or timed out) it matches the picture before the action, it looks up to 1.5 s longer (not for type, edit keys or the in-between steps of --fast), timing from when the action finished: a change then is waited out and said as "the screen changed late, X after the action, and settled at Y" (or "was still changing when time was up at Y"); a --stop condition met meanwhile stops the stretch there, skips the rest and gives no NO_CHANGE; only a picture still unchanged is "the screen did not change" (NO_CHANGE: no change was seen in that time).
conditions: text:<word> (newly appearing after action; * may match zero chars: 9* also matches 9%) | gone:<word> | change[:x1,y1,x2,y2] | still[:region] (no motion at all) | stable (settled, small looping animation allowed) | color:x,y=#RRGGBB[~tolerance] | app:<package or screen name>
A step that does not reach what it expects stops the stretch there, skips the rest, and the result is a failure (exit code 1, outcome=missed/refused):
      until timed out; text not found on the screen; the same text in several places (AMBIGUOUS, every place is listed with its point: use coordinates); the element of #N is no longer where it was (STALE_ELEMENT);
      type found no focused input box, or your text is not in the box afterwards. Tap the input box before type; in [Now] it is marked "input focused".
Buttons that actually spend money (pay, place order, transfer) and apps the user set to "confirm every time" (plus the classes this agent's permission table sets to "Ask me", such as irreversible or sensitive actions): the phone asks the user, and the step is done only after the user allows it; classes set to "Not allowed" return SCOPE_DENIED (waits up to 60 s; no answer is CONFIRM_PENDING: tell the user to allow it on the phone, then send again).
e.g. act "tap Search; type headphones; key enter until text:All"
         act "drag 300 900 800 900" --watch 2s
         act "drag 200 1500 540 600 hold until color:540,600=#4CAF50 2s"
         act "drag 100 800 980 800 seek until text:70 in 400,600,700,700"
         act "press 540 1600 hold until text:9* in 300,1400,800,1500 8s"
         act --on "text:Promo => tap 980,420" "tap OK until text:Done"
         act --on "text:Tap me => tap Tap me x3" --watch 10s
         act "tap Start until text:Running" --hold
         act --stop "text:In stock" --keep notify --for 2h

- `--stop <condition>` watched for the whole stretch; when met it stops and skips the rest
- `--on <cond => step>` short-range rule for this act only (1–3 rules, never persisted): when cond holds, run one step (tap / key back / scroll / wait duration only; optional x1–x3, default x1; cond cannot be stable / still; text: / gone: requires 2 consecutive independent readings with 500ms cooldown; rules never auto-confirm payments or ask-every-time apps)
- `--hold` pause and verify the virtual screen's foreground app before sending the reply, resuming before the next command (max 120s, Android 14+ only; set default with note hold on). Note: starting on Android 15, pausing an actively refreshing app may cause the system to kill it; pausing only stops frame-timed apps (wall-clock apps skip ahead on resume) and active network connections may time out
- `--watch <duration>` keep looking this long after the steps (moving pictures)
- `--fast` skip settle waits only between actions (games, rapid taps); the last step and each repeat iteration's until / settle wait still run
- `--bare` this stretch skips the before/after steps kept for this app
- `--human` human pacing: a random 0.2–2.6 s pause before each action, taps slightly off-centre inside the element, varied hold and swipe lengths, typing 1–3 characters at a time (for apps sensitive to machine timing; conditions are unchanged). To keep it on: note human on
- `--keep <notify|wake>` after this turn keep watching the --stop condition; when seen, notify the user (notify) or send the caller an event (wake, fetched with events), then end; survives a restart
- `--for <duration>` how long --keep watches (default 1 hour, at most 12 hours)
- `--save <name>` save these steps as a segment of this app, later: use <name>
- `--full` always return a full reply this time; with no steps, replays the last act window with a full picture and all elements
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate` (an act with no steps, or only wait steps, needs just look) · runs in the screen queue

### `note [text] | before "<steps>" | after "<steps>" | save <segment> "<steps>" | show <segment> | human on|off | reply quiet|full | hold on|off | delete <number|before|after|segment>`

keep notes for the current app (no arguments = list notes, segments and before/after steps). The next launch lists them, with who wrote each and for which app version.
before / after: every act first does the before steps, and after acting and looking does the after steps (--bare skips them).
e.g. for a picture that moves by itself (a game, a player) keep before = the steps that resume and after = the steps that pause, so it stands still while you think; for a popup shown on every visit keep before = the steps that close it.
save: store or rewrite a segment without running it (taught lessons too); the steps are written as for act, with try / if / repeat, * and @N; note lists each segment's full steps.
human on: every act on this app uses human pacing from now on (as act --human); off turns it off.
reply quiet|full: save this agent's default reply mode (default quiet: short reply when expected; full: always return full four blocks); works without an open app.
hold on|off: save whether this agent enables act --hold by default (default off, Android 14+ only; starting on Android 15, pausing an actively refreshing app may cause the system to kill it); works without an open app.

scope: `operate` · runs in the screen queue

### `events [--wait <duration>] [--again]`

fetch events that watches (act --keep wake) sent when their condition was seen. Events are numbered and are deleted only at your next events call (nothing is lost if the connection drops in between).
Without a running --keep wake watch in this session it does not wait and says so.

- `--wait <duration>` wait up to this long when there is none (default no wait, at most 120s)
- `--again` return the previously fetched batch again

scope: `look`

### `task [task id] | cancel <task id> | recap [--wait <duration>]`

look at tasks started with --async: no argument lists this session's tasks; with a task id it returns the state, and the full result once finished; cancel stops it before its next step; recap summarizes this session's pages and steps without re-reading the screen (at most 600 chars).

- `--wait <duration>` wait up to this long while the task is unfinished (at most 120s)

scope: `look`

### `tasks`

list the app tasks on the screen now (the front one first): package, task id and whether it is in front. Use it to see whether the app is still there. Apps parked with handoff park are not on this screen and are not listed.

scope: `look` · runs in the screen queue

### `title [text]`

show what you are doing on the floating window (up to 40 characters, for the user; no text = clear; cleared when this turn ends).

scope: `operate`

### `permissions`

this agent's permissions now: whether each class of action is allowed, confirmed each time or not allowed, when temporary allows end, which apps confirm every time, are blocked or read-only, and the fixed limits. Read it once before a task and plan by it; pass it on when the user asks. The phone checks every command against the same table before it runs.

scope: `look`

### `elevate <class|level> [--why <reason>] [--for <round|today|always>]`

ask the user ahead for more permission, with your reason: the phone shows a confirmation (notification → fingerprint) and the user picks this turn, today or permanently. Classes caution/destroy/system/operate/rate, levels careful/free/free_system.
A temporary permission ends by itself; payments cannot be asked for (each payment is confirmed on its own). A command that meets a Not allowed class brings up the same confirmation.

- `--why <reason>` why you need it (shown on the card, up to 120 characters)
- `--for <round|today|always>` the range you hope for (default round; the user may pick another)

scope: `look`

### `done`

this turn is finished: the screen pauses to save power at once, the page is kept 10 minutes, and other sessions can take over (without done, it ends after N quiet minutes, set in the app).

scope: `operate` · runs in the screen queue

### `keep | off <name|all>`

list / stop watches that run after this turn (started with act --stop … --keep).
See also act --hold (pause between turns): starting on Android 15, pausing an actively refreshing app may cause the system to kill it.

scope: `operate`

### `stop [package] [--force]`

close the app on the screen.

- `--force` stop the whole app (also on the main screen): the phone asks the user to confirm

scope: `operate` (--force needs system) · runs in the screen queue

### `release`

release the screen now.

scope: `operate` · runs in the screen queue

### `handoff in [package] | out [package] | park <app> | unpark <app> [--note <text>]`

in: move the app from the user's main screen to the background screen and carry on (returns four blocks; the phone asks the user to confirm); out: hand it back to the main screen when the user must act.
handoff park <app>: move the app task to a hidden parking display; handoff unpark <app>: return it to the foreground. Look and act return APP_PARKED if the current target is still parked. Turn end, Stop or user takeover clears parked tasks.

- `--note <text>` what the notification to the user says on out

scope: `operate` (in needs system) · runs in the screen queue

### `sound [list] | mute <app> | unmute <app> | plays <app> on|off | screen on|off`

app sound: list shows the apps silent now (and by whom), the ones muted by hand, the ones that keep sound on the screen, and whether screen muting is on; mute silences an app on every screen (until unmute); unmute gives its sound back; plays lets an app keep its sound on the screen; screen turns screen muting on or off. Sound already playing changes only when the app plays again.

scope: `system` (list needs look only)

### `ask "<question>" [--point] [--area] [--choose <A|B|C>] [--text] [--wait <duration>] [--async]`

ask the user on the phone (notification + the app's home page) and wait for the answer. Default --point: the user taps "the one I mean" on the screen's picture; you get the screen point and the element there (#N). Use it when a place is hard to put into words.
An answer is information, not permission: payments still need the phone's confirmation.

- `--point` tap once on the picture (default)
- `--area` drag a box on the picture; returns the box and the elements in it
- `--choose <A|B|C>` choose one of 2–8 options
- `--text` answer with a line of text
- `--wait <duration>` how long to wait (default 50s, at most 5m; no answer is ASK_PENDING, send the same ask again later for the answer)
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate`

### `teach "<what to show>" [--name <name>] [--wait <duration>] [--again] [--async]`

ask the user to do it once on the background screen (a lesson): a request appears on the phone, the user takes over the screen, does it, reviews it, and it is saved as a segment of the app. Returns the segment name and steps; then act "use <name> <values>…" runs it. Words the user typed become blanks {1} {2}… by default, filled in order when used.
For flows that are hard to describe, keep failing, or the user knows better. launch the app to show first, put the page back where it starts (clear words you typed, a result, a dialog) and look once to check before sending.

- `--name <name>` suggested segment name (the user may change it)
- `--wait <duration>` how long to wait (default 50s, at most 5m; not done yet is ASK_PENDING, send the same teach again later for the result)
- `--again` ask the user to show it again (without it, a teach that already has a result returns that result and does not ask again)
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate`

### `schedule ["when"] | off <name|all> [--name <name>] [--for <duration>]`

book the times you will use the phone (for timed tasks): 3 minutes before each window the service is started, and the selected permission source (Shizuku or root) and the connection are checked; the user is notified if it is not ready, and the result is sent to this session as an event. With no argument: the schedules and whether it is ready now.
Times: "07:00", "07:00,19:30", "weekdays 08:30", "mon,wed,fri 21:00", "every 2h from 08:00 to 22:00" (at least 30 minutes apart, at most 8 entries).

- `--name <name>` the schedule's name (the same name replaces it; default w1, w2…)
- `--for <duration>` how long each window stays up (default 10m, at most 30m)

scope: `operate`

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

### `look [x1,y1,x2,y2] [--last <duration>] [--from <time>] [--to <time>] [--count <count>] [--every <interval>] [--page <page>] [--detail <detail>] [--layout <layout>] [--marks <on|off>]`

ask for pictures: no argument is the screen now; a region zooms into it; --last/--from/--count looks back at the recording (one frame before each action, touches circled). Ask for more detail, another layout, no marks, or the next page. At most 8 pictures, about 3 MB a reply; --page for more.

- `--last <duration>` this long up to now (e.g. 30s, 2m)
- `--from <time>` start this long ago (20s) or at a clock time (14:03:10)
- `--to <time>` end this long ago or at a clock time (default now)
- `--count <count>` at most this many frames (default 12, at most 96)
- `--every <interval>` take one every interval (e.g. 0.5s), repeats left out
- `--page <page>` which page when there are more than 8 pictures
- `--detail <detail>` low, normal (default) or high
- `--layout <layout>` auto (default), single (one frame each), strip (6 in a row) or 3x4
- `--marks <on|off>` ruler and touch circles on the picture (default on)

scope: `look` · runs in the screen queue

### `frames [--last <duration>] [--from <time>] [--to <time>] [--count <count>] [--every <interval>] [--page <page>] [--detail <detail>] [--layout <layout>] [--marks <on|off>]`

old form of look --last …: look back at the recording, by default the [Meanwhile] of the last reply.

- `--last <duration>` this long up to now (e.g. 30s, 2m)
- `--from <time>` start this long ago (20s) or at a clock time (14:03:10)
- `--to <time>` end this long ago or at a clock time (default now)
- `--count <count>` at most this many frames (default 12, at most 96)
- `--every <interval>` take one every interval (e.g. 0.5s), repeats left out
- `--page <page>` which page when there are more than 8 pictures
- `--detail <detail>` low, normal (default) or high
- `--layout <layout>` auto (default), single (one frame each), strip (6 in a row) or 3x4
- `--marks <on|off>` ruler and touch circles on the picture (default on)

scope: `look` · runs in the screen queue

### `zoom x1,y1,x2,y2 [--scale <factor>] [--detail <detail>] [--marks <on|off>]`

old form of look x1,y1,x2,y2: zoom into a region (screen coordinates on the picture's edges).

- `--scale <factor>` zoom factor (default follows the region size)
- `--detail <detail>` low, normal (default) or high
- `--marks <on|off>` ruler and touch circles on the picture (default on)

scope: `look` · runs in the screen queue

### `collect [--pages <count>] [--from-top] [--until <text>] [--async]`

scroll down page by page from here and collect every item (a card or list row is one item, deduplicated). Says when there is more above; --from-top goes to the top first.

- `--pages <count>` pages to go through at most (default 8, at most 40)
- `--from-top` scroll back to the top of the page before collecting
- `--until <text>` stop when this text is seen
- `--async` do not wait for it: returns a task id at once, fetch the result later with task <id> (for long operations or short connection timeouts)

scope: `operate` · runs in the screen queue

### `fix [code] [--open]`

fix the problems listed in status and give sound back to apps left silent; each is checked again afterwards and fixed is that check. Steps that change system settings or open a settings page on the main screen: the phone asks the user to confirm.

- `--open` when the user must act by hand, open the page on the main screen

scope: `system`

### `diag`

export a diagnostic bundle (versions, status, recent logs and swallowed exceptions; none of your typed text or screenshots) as text, to hand to the developer when something is wrong.

scope: `look`

## Result codes

| code | next | meaning |
|---|---|---|
| `OK` | `none` | Done as expected. |
| `CHECKPOINT` | `look` | The run stopped at a check step for you to look at the screen: if it is right, go on with the command in the reply (use <name> … from N); if not, put it right first, then go on. |
| `STEP_MISSED` | `look` | A step was done but what it expected never came (until / wait timed out, typed text did not land). The stretch stopped there and skipped the rest. Look at the screenshot before deciding. |
| `NOT_READ` | `look` | Screen reading could not keep up during the wait (OCR busy or failed), so no post-action reading was captured; do not assume the text is absent — inspect the screenshot or retry shortly. |
| `NOT_FOUND` | `look` | The text, number or segment you named is not there. The reply lists similar text and input boxes with their points. |
| `AMBIGUOUS` | `fix_command` | The text you named is on the screen several times; nothing was tapped. The reply lists every place with its point: use coordinates or #N. |
| `STALE_ELEMENT` | `look` | The element #N pointed to is no longer where the last list had it (the page changed). Nothing was tapped. Look again and use the new numbers. |
| `OUT_OF_BOUNDS` | `fix_command` | The point is outside the screen. |
| `INVALID_STEP` | `fix_command` | A step is written wrong; nothing in the stretch ran. |
| `INVALID_ARGS` | `fix_command` | Bad arguments; the command did not run. |
| `GESTURE_NEEDS_UNTIL` | `fix_command` | Closed-loop gestures (hold / seek) require until <condition>; nothing in the stretch ran. |
| `GESTURE_NEEDS_AREA` | `fix_command` | text: / gone: conditions inside a closed-loop gesture must include a region (in x1,y1,x2,y2); nothing in the stretch ran. |
| `BAD_RULE` | `fix_command` | Bad short-range rule (--on); nothing in this act ran. Syntax: --on "<cond> => <tap\|key back\|scroll\|wait duration>[ x1..x3]", at most 3 rules, condition cannot be stable / still. |
| `RULE_OFF` | `none` | A short-range --on rule was disabled for the rest of this act (limit reached, app switched, target missing/ambiguous, or no visual change after running). |
| `HOLD_UNSUPPORTED` | `none` | This device or Android version does not support --hold (Android 14 / API 34+ required, or the system does not support am freeze); the app was not paused and the command ran normally. |
| `HOLD_REFUSED` | `none` | Refused or failed to pause this app (system/persistent/IME/system media process, freeze could not be verified, or the system killed the app while pausing). The action itself completed normally. |
| `UNKNOWN_OPTION` | `fix_command` | The command has no such option; it did not run. |
| `UNKNOWN_COMMAND` | `fix_command` | No such command. Tapping, typing, swiping and waiting are all act steps. |
| `FOCUS_ELSEWHERE` | `look` | The focused input box is not where you just tapped; nothing was typed. |
| `TYPE_FAILED` | `look` | The text could not be sent. |
| `SNAP_BACK` | `look` | The dragged item snapped back to its starting position after release (may not have passed the trigger threshold or drop is not accepted). |
| `NO_CHANGE` | `look` | The step was done but the picture did not visibly change afterwards (the tap may have missed, or nothing changes here). |
| `UNPROVEN` | `look` | The step acted where the screen keeps moving by itself; the change afterwards may be only the animation and does not show the step worked. Check the picture. |
| `SCREEN_RESUMED` | `look` | The screen just resumed from its power-save pause; while paused the app was not in front and may have paused itself or shown a menu. Go by this reply's picture. |
| `NEEDS_CONFIRM` | `user_on_phone` | The user must confirm this in Agent-VScreen on the phone (risk control, or a permission you asked for with elevate). This step was not done. The user may not have seen the notification: send them the line under [Waiting for the user], and once they say it's done, resend the exact same command. |
| `CONFIRM_PENDING` | `user_on_phone` | This step needs the user's confirmation on the phone (payment / irreversible / an app set to ask every time / something that affects the main screen); the user has not answered and it was not done. The user may not have seen the notification: send them the line under [Waiting for the user], and once they say it's done, send this stretch again. A rule (--on) meeting such a button sends no request and aborts the act; then there is no [Waiting for the user] and the step is yours to do. |
| `USER_DENIED` | `ask_user` | The user denied this step on the phone. Don't retry; ask the user what they want. |
| `CONFIRM_REQUIRED` | `user_on_phone` | Same as CONFIRM_PENDING (old name). |
| `APP_BLOCKED` | `ask_user` | The user blocked background operation of this app. Don't get around it or enter the same app another way. |
| `APP_IN_USE` | `ask_user` | The user is using this app on the main screen. Ask the user first; once they agree, launch --asked (a separate copy) or handoff in (from their page). |
| `APP_WITH_USER` | `ask_user` | The app you were using is with the user (main screen). Wait for the handback event, or launch another app. |
| `OCR_DOWN` | `none` | Text recognition is unavailable (both engines failed): elements come from accessibility only, words on the picture may be missing; trust the screenshot. |
| `APP_PARKED` | `fix_command` | This app is parked on the parking display (a hidden display, not the screen): its page and logins are kept, but it is neither visible nor operable now. Bring it back with vscreen handoff unpark <app>, then carry on; the screen can hold another app meanwhile. |
| `APP_READ_ONLY` | `ask_user` | The user set this app to read-only: you may look, scroll and go back, not tap / type / swipe. |
| `USER_TAKEOVER` | `ask_user` | The user is operating the screen. Stop, tell the user, and wait for the hand-back (vscreen events --wait for handback); the screen and its page stay, don't release or launch again. |
| `THERMAL_STOP` | `ask_user` | Overheating stopped background work. Do not retry; ask the user to resume in the Home power menu after cooling. |
| `USER_STOPPED` | `ask_user` | The user pressed "Stop AI". Everything is refused and only the user can lift it; pass on the line that says how. |
| `CONTROL_CHANGED` | `look` | The user took over, stopped or handed back the screen after this command arrived: the screen it was planned for may be gone, and the rest of its actions were not done. Look with act (no steps), then send what is needed for what you see. |
| `REREAD_REQUIRED` | `look` | The user just had the screen and the page may have changed; this was not done. Refused only this once: look with act (no steps), then send it again. |
| `HOST_RESTARTED` | `look` | The Agent-VScreen process restarted and the last task was cut off. This reply is the screen now; don't assume earlier steps completed. |
| `MAIN_SCREEN_CHANGED` | `ask_user` | An app or task of ours came to the main screen and disturbed what the user is using. Stop and tell the user; take it back with handoff in. The user switching apps on the main screen is not reported. |
| `SCOPE_DENIED` | `ask_user` | This token is not allowed to do this (the scope the user gave the token in the app). |
| `PROTECTED_APP` | `none` | This is Agent-VScreen itself, the launcher or system UI; it cannot be opened, moved, parked or stopped on the background screen. |
| `NO_SCREEN` | `fix_command` | There is no screen now: launch an app first. |
| `NO_APP` | `fix_command` | No such app, or no app on the screen. apps <keyword> finds packages. |
| `NO_FRAME` | `retry_later` | The screen has no picture yet; try again shortly. |
| `NO_SESSION` | `fix_command` | The request names no session. |
| `BUSY` | `retry_later` | The screen belongs to another session now. The reply says whose (owner) and when it frees up by itself (frees_in_s seconds; null while it is running a command). |
| `QUEUE_TIMEOUT` | `retry_later` | Earlier commands are still running; this one waited in the queue for over 60 s and did not run. |
| `CANCELLED` | `none` | The caller disconnected or cancelled the task; the command stopped before its next step. |
| `CONNECTION_LOST` | `ask_user` | The privileged background connection broke (Shizuku or the root service stopped); the screen and its app are gone, not because of your actions. Tell the user; launch again once it is back. |
| `NOT_CONNECTED` | `ask_user` | The screen service cannot be used: Shizuku (or root) stopped or is not granted; the reply says which. Pass that line on to the user. A service that is merely off or hidden never reports this. |
| `DISABLED` | `ask_user` | The user turned the Agent-VScreen service off. |
| `CREATE_FAILED` | `ask_user` | The screen could not be created. status says why. |
| `STALE_SCREEN` | `look` | This action was meant for an older screen that has been rebuilt. |
| `NOT_SUPPORTED` | `ask_user` | This phone cannot do this (the screen service is unavailable). See degraded in status. |
| `INPUT_FAILED` | `look` | The system did not accept the injected input. |
| `APP_NOT_FOUND` | `fix_command` | No launch entry for this package. |
| `APP_NOT_SUPPORTED` | `fix_command` | This app cannot open on the background screen (e.g. Agent-VScreen itself). |
| `LAUNCH_FAILED` | `look` | The app did not start. |
| `LAUNCH_RESOLVER` | `fix_command` | It stopped on the system's "Open with" chooser: the package has no launcher entry. |
| `LAUNCHED_ON_MAIN_SCREEN` | `ask_user` | The system opened the app on the main screen instead. Don't go on there; tell the user. |
| `LAUNCH_NOT_ON_TOP` | `look` | After starting, another app is in front on the screen. |
| `HANDOFF_NO_TASK` | `ask_user` | There is no such app task to move on the main screen (or the background screen). |
| `HANDOFF_DENIED` | `ask_user` | The system did not let the app move between the screens. |
| `CLOSE_FAILED` | `look` | Could not close just the task on the screen. |
| `MAIN_SCREEN_TASK` | `none` | This task is on the main screen; it was left alone. |
| `SCHEDULE_LIMIT` | `fix_command` | Too many schedules (8): remove one with schedule off. |
| `NOT_READY` | `ask_user` | Agent-VScreen did not answer or is not ready (frozen by the system, service off, Shizuku / root not running). The CLI already tried to wake it once. Tell the user why; for timed tasks, book the time with schedule first. |
| `ASK_PENDING` | `user_on_phone` | The question (or teach's request to demonstrate) is on the phone and the user has not done it yet. The user may not have seen the notification: send them the line under [Waiting for the user], and once they say it's done, send the same ask / teach again to get the result (valid for 10 minutes). |
| `ASK_DECLINED` | `ask_user` | The user chose not to answer. Don't ask the same again; go another way or tell the user where you stopped. |
| `USER_FEEDBACK` | `ask_user` | The user did not answer the way you asked but said something instead (in feedback, verbatim). Most likely the question, the page or your reading was off: adjust to it; don't ask the same again. |
| `NOT_AT_START` | `look` | The lesson was recorded from a certain page and the screen is not there now, so nothing was done. The reply describes the starting page: get there first, then use it. |
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
| `BODY_TOO_LARGE` | `fix_command` | The request body is too large (at most 1 MB). |
| `HEADERS_TOO_LARGE` | `fix_command` | The request headers are too large. |
| `REQUEST_TIMEOUT` | `retry_later` | The request did not arrive in time. |
| `SIGNATURE_REQUIRED` | `ask_user` | Requests from other devices through the external Tailscale app must be signed (the vscreen CLI signs; through the built-in Tailscale none is needed). For clients that cannot sign, such as MCP: ask the user to turn off "signed requests only" under Other devices, or use the built-in Tailscale. |
| `TASK_STARTED` | `none` | The task started; fetch its result with task <id>. |
| `TIME_SKEW` | `ask_user` | The caller's clock is too far from the phone's, so the signature is not accepted. Fix the caller's clock. |
| `UNSUPPORTED_MCP_PROTOCOL` | `fix_command` | This MCP server currently supports initialization-based revisions (2025-06-18, 2025-03-26, 2024-11-05); retry with initialize. |
| `OPERATION_FAILED` | `look` | It failed; the reply says why. |
| `INTERNAL_ERROR` | `none` | Internal tool error. Send it to the developer (vscreen diag exports diagnostics). |

