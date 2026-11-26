<p align="center"><img src="docs/logo.svg" width="96" alt="Agent-VScreen"></p>

# Agent-VScreen

<p align="center">
  <a href="https://github.com/kilolonion/agent-vscreen/releases/latest"><img src="https://img.shields.io/github/v/release/kilolonion/agent-vscreen" alt="最新正式版"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/kilolonion/agent-vscreen" alt="license"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/Shizuku%20or%20root-supported-blue" alt="Shizuku or root">
  <img src="https://img.shields.io/badge/MCP-supported-8A2BE2" alt="MCP">
</p>

安卓手机上的**后台副屏**，手机、电脑或云端的 AI agent 都能用。

中文 · [English](README.md)

Agent-VScreen 在一块看不见的虚拟屏上打开和操作别的应用，你的主屏照常用，不会被打断。屏幕上只有一个悬浮小窗，让你看到 AI 在做什么，随时可以接管或叫停。

不管 agent 跑在这台手机上，还是在电脑、服务器或另一台手机上，都可以调用它：带终端的 agent 用命令行 `vscreen`，支持 MCP 的用 MCP，自己写代码的用 HTTP。

<p align="center"><img src="docs/app.png" width="760" alt="Agent-VScreen 现在、接入、记录和设置页面"></p>
<p align="center"><sub>App 界面：现在（agent 正在副屏上做什么、接管或停止、最近的任务）、接入（已接入的 agent、远程连接、令牌）、记录（每次任务、提问和预约，带步骤缩略图）、设置（谁能连进来、agent 能做多少、应用规则、悬浮窗）。</sub></p>

<p align="center"><img src="docs/agents.png" width="860" alt="Minis、Eta、RikkaHub、Muse 四个安卓 agent 都通过 vscreen 在副屏打开 Linux.do 客户端"></p>
<p align="center"><sub>同一条安装命令，Minis、Eta、RikkaHub、Muse 四个不同的安卓 agent 都能接入并操作副屏。</sub></p>

<p align="center"><img src="docs/demo.gif" width="760" alt="用户在主屏滑动网页的同时，agent 在后台副屏里打开时钟并启动一个 10 分钟定时器，悬浮窗实时显示副屏"></p>
<p align="center"><sub>后台操作：你在 Chrome 里照常看网页，agent 在后台副屏里打开时钟、设好 10 分钟定时器并启动。终端里是真实发出的 <code>vscreen</code> 命令（等待的部分 6 倍速）。<a href="docs/promo/background.mp4?raw=true">下载 MP4</a></sub></p>

<p align="center"><img src="docs/takeover.gif" width="760" alt="用户长按悬浮窗接管副屏，自己滑动和点按，再交还给 agent 继续"></p>
<p align="center"><sub>随时接管：长按悬浮窗就能自己操作副屏，用完交还，agent 重新读屏后接着做。<a href="docs/promo/takeover.mp4?raw=true">下载 MP4</a></sub></p>

<p align="center"><img src="docs/tour.gif" width="760" alt="依次浏览 App 的现在、接入、记录和设置四个页面"></p>
<p align="center"><sub>agent 工作时的 App 界面一览。<a href="docs/promo/tour.mp4?raw=true">下载 MP4</a></sub></p>
<p align="center"><sub>三段 MP4 都带配乐，另有一段 34 秒的<a href="docs/promo/mix.mp4?raw=true">混剪版</a>。配乐：“Wallpaper”，Kevin MacLeod（incompetech.com），<a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>。</sub></p>

- `main` 分支版本：1.4.2-beta3（versionCode 54） · [测试版 APK](https://github.com/kilolonion/agent-vscreen/releases/tag/v1.4.2-beta3) · [最新正式版 APK](https://github.com/kilolonion/agent-vscreen/releases/latest)
- 包名：`com.kilon.agentvscreen`
- 系统：Android 11 及以上（arm64），推荐 Android 14 及以上
- 特权：默认用 [Shizuku](https://shizuku.rikka.app/)，不需要 root；已 root 的手机也可以直接用 Root

## 它能做什么

- **后台操作应用**：在副屏上打开应用、点、滑、输入、按键、等画面变化，每一步都核对结果，没做成就停下并写明现在的画面。
- **把画面交给 agent**：每次返回现在的界面、能看到的字、可点的元素和截图。本应用不调用任何大模型，画面的意思由 agent 自己的模型判断。
- **能等、能盯**：动作后可以等某段字出现、消失或画面稳定；也能在这一轮结束后继续盯着，条件出现就通知你或叫醒 agent。
- **和你配合**：agent 可以请你在画面上指出一样东西（`ask`），或请你亲手做一遍存成示教（`teach`）；要密码、验证码的步骤交到你的主屏上做。
- **悬浮窗**：边框颜色表示现在谁在用副屏（彩虹色 AI 在操作、蓝色待命、绿色你接管、红色已叫停）；拖动移动、双指缩放、甩到边上折成小标签；按住画面 0.5 秒接管；也能打开全屏页，用手机自己的输入法打字。
- **不和 agent 抢权限**：App 不占用 adb，不改无线调试；你的 agent 自己用的 adb 或 Shizuku 照常可用。

## agent 能用它做什么

这份清单和 agent 收到的说明来自同一张能力表（`Abilities.kt`），由测试自动写入，不要手改。

<!-- abilities:start -->
- **用户要我在手机上做任何事：打开某个应用、查信息、比价、看某页内容、填表、发帖、测试应用……**：`vscreen launch <应用>` 打开并看，`vscreen act "<步骤>"` 点、滑、输入、按键，每次都返回截图和元素列表
- **我要为一件会用到手机的事设定时：用户说了定时、每天、每周、到点（签到、打卡、抢购、定时查看），或我自己建了定时任务、周期任务、cron、自动化、提醒**：两边都要设：我的定时只叫醒我，叫不醒手机。同一时间用 `vscreen schedule "<时间>" --name <名字>` 给手机预约，它会在每次之前拉起服务、检查权限，没准备好就提醒用户；改时间、取消时两边一起改
- **要等一件以后才发生的事：到货、降价、有回复、直播开始、排队轮到、下载完成；或我想隔一会儿再看一次画面**：`vscreen act --stop "text:到货" --keep notify` 让手机一直盯着，看到就通知用户（`--keep wake` 改成叫醒我，用 `vscreen events` 取）
- **要读完一个很长的列表或整页内容（全部订单、聊天记录、搜索结果、评论）**：`vscreen collect --pages 10` 自动翻页，返回去重后的每一条
- **不确定用户指的是画面上哪个东西（哪个商品、哪个按钮、哪条消息），或要用户选一个**：`vscreen ask "你说的是哪一个？"` 让用户在手机上的副屏画面里点出来（也可以 `--choose`、`--text`）
- **一个流程说不清、试了几次都不对，或用户自己更清楚怎么做**：`vscreen teach "要演示什么"` 请用户在副屏上亲手做一遍，存成一段，之后 `act "use 段名 参数"` 照做
- **同一个应用会反复用：有固定入口、每次都弹的窗、要先暂停的画面，或对机器节奏敏感**：`vscreen note` 给这个应用记经验、存步骤（`note save`）、设前置/收尾步骤和仿人节奏，下次 launch 会列出来
- **有一步只能用户本人做（密码、验证码、人脸、扫码），或要用的应用用户正在用**：`vscreen handoff out` 把应用交到用户主屏并告诉他做什么；`vscreen handoff in` 把主屏上的应用接到副屏
- **应用没声音，或用户要让某个应用静音、恢复声音**：`vscreen sound` 查看，`sound mute|unmute <应用>` 静音或恢复
- **切换或暂存应用**：`vscreen tasks` 查任务；`launch --keep` 切回原页；`handoff park/unpark` 暂存或取回
- **要做的事超出我的许可（如卸载、系统操作）**：`vscreen permissions` 查权限，`vscreen elevate <类别> --why 原因` 申请
- **画面上的字太小、是图标，或者根本没有字（游戏、画布、图表），或想回看刚才发生了什么**：`vscreen look` 要图：现在的画面、放大一块、回看录像都用它；`vscreen spots` 找可点的色块
- **连不上手机、命令一直失败或出了怪问题**：先分清是没开还是坏了：没在运行、服务关着或收起是用户的选择，转告用户打开即可，不用修；`vscreen status` 里 ready 为假时才是故障，`vscreen fix` 修它列出的项，也恢复被留下静音的应用
<!-- abilities:end -->

### 在副屏切换与暂存应用

用 `vscreen tasks` 查看副屏应用的包名、任务 ID 和前台状态。`vscreen launch <应用> --keep` 把已有任务切到前台，不重启应用；如果应用不在副屏上，就正常启动。

用 `vscreen handoff park <应用>` 把任务移到隐藏的停放屏，再用 `vscreen handoff unpark <应用>` 移回副屏前台。`tasks` 不列出停放的任务。如果当前目标仍是被停放的应用，`look` 和 `act` 返回 `APP_PARKED`，直到取回它或切换到其他应用。本轮结束（`done` 或空闲超时）、Stop 或用户接管时会关闭停放任务；停放不是持久保存。

## 为什么好用

- **有时间感**：agent 工作时，后台每秒看副屏约 5 次、读一次字并记下时间。每次返回的【这段时间】写出从上次返回到现在（包括 agent 思考的那几秒）出现、消失、一闪而过的字，画面哪里变了、变了多久，倒计时、加载、弹窗都跟得上。
- **看得清**：无障碍控件树和手机上离线运行的 OCR（PP-OCRv6，另带 ML Kit）合并成带编号和坐标的元素列表，`tap #N` 和 `tap 画面上的字` 都能点；`look` 放大一块或回看录像，`collect` 边滚边收集整页内容并去重。
- **一套步骤语言**：`try`、`if`、`repeat … until`、通配 `*`、第 N 处 `字@2`，脚本、存下的段和示教都用它。每个应用能记经验、存步骤，下次打开时列出来。
- **多个 agent 也不乱**：每个接入的 agent 有自己的令牌和记录；同一块副屏同一时间只给一个会话，别人在用时返回 `BUSY`；可以给应用设禁止或只读，付款这类步骤要你在手机上确认。
- **不绑模型**：手机这边不调用任何模型，换哪个 agent、哪个模型都能用。

## 安装

<p align="center"><a href="docs/promo/tutorial.zh.mp4?raw=true"><img src="docs/tutorial.zh.png" width="760" alt="新手教学视频：授权、接入 agent、交给它第一件事、随时接管、回看记录"></a></p>
<p align="center"><sub>新手教学视频（中文界面和配音，约 2 分钟）：从授权到让 agent 做第一件事。<a href="docs/promo/tutorial.zh.mp4?raw=true">下载 MP4</a></sub></p>

1. 安装 APK，打开 Agent-VScreen。
2. 在 **首次配置** 里选一种权限来源：
   - **Shizuku（默认）**：先启动 Shizuku，再点 **授权 Shizuku**。手机每次重启后要重新启动 Shizuku。
   - **Root**：手机已 root 时，点 **手机已 root？改用 Root**，在 root 管理器弹窗里授权，无需安装 Shizuku。以后可以在 **设置 › 权限来源** 切换；切换会关闭当前副屏。
3. 点 **一键配置其余项**：悬浮窗、通知、只读无障碍、电池优化和后台运行。荣耀、华为、小米、OPPO、vivo 等手机再点 **去设置后台管理**，允许自启动、关联启动和后台活动，否则系统可能把它冻结在后台。

### 验证下载的 APK

安装前，用 Android SDK Build Tools 自带的 [`apksigner`](https://developer.android.com/tools/apksigner) 验证。把 `downloaded.apk` 换成下载的 APK 路径：

```sh
apksigner verify --print-certs downloaded.apk
```

验证必须成功，且签名证书的 SHA-256 指纹必须与官方发布证书（`CN=Agent-VScreen`）一致：

`56:B1:48:24:54:9E:69:80:17:EB:A9:07:CB:C3:34:7E:E3:A4:BA:30:90:7E:FB:D6:06:8E:A5:1F:F4:49:57:56`

`apksigner` 输出的指纹是小写且不带冒号。应核对证书指纹，不要只看证书名称，也不要拿 APK 文件本身的 SHA-256 校验值来比。验证失败或指纹不符时，请勿安装。

## 接入 agent

在 App 的 **接入** 页点 **接入新的 agent**：起个名字，选 agent 在哪、怎么连，最后点 **复制给 agent 的话** 原样发给它。它会装好或配好工具，并把一段说明记进自己的长期记忆，以后的新对话也知道手机上有这个工具。每个接入的 agent 有自己的令牌，在接入页可以随时让它作废。

怎么连有三种，本地和云端都一样：

- **命令行**：带终端的 agent 装好 `vscreen` 命令，用 `vscreen launch 设置`、`vscreen act "tap 搜索; type 关于手机"` 这样调用。
- **MCP**：支持 MCP 的 agent 填上地址和令牌（连接方式 `httpStream`）。
- **HTTP**：自己写调用的程序直接请求 `/v2` 接口，见 [API.md](API.md)。

### 本地：agent 在这台手机上

选 **这台手机上**。服务只监听 `127.0.0.1:18765`，手机以外的设备连不进来。手机上的终端类 agent、支持 MCP 的聊天应用都按上面的方式接入；Operit 另有一个现成的工具包，见 [integrations/operit](integrations/operit/)。

18765 被别的应用（或分身里的同一个 App）占着时，服务会自动改用后面一个空闲端口，并在 App 里写明是谁占着；本机的 agent 会自己找到新端口，不用重新接入。

### 云端：agent 在电脑、服务器或另一台手机上

选 **其它设备上**，先在 **远程连接** 里连好 Tailscale，复制出来的给 agent 的话、安装命令和 MCP 配置就会换成 Tailscale 地址。

- **内置 Tailscale（推荐）**：不用装 Tailscale App，也不占系统 VPN，在 App 里登录一次就行，只有你自己 Tailscale 网络里的设备连得进来。自建的 Headscale 也能用，在高级选项里填服务器地址。
- **Tailscale App**：手机上已经装了 Tailscale App、或要靠出口节点上网时用它。内置节点只负责让别的设备连进来，不接管手机自己的上网。
- 也可以用 SSH 端口转发。

令牌等于这台手机的操作权限：内置 Tailscale 在加密连接上接受令牌，其它远程连接默认要求给请求签名。本应用不提供公网链接。不要把令牌贴进公开的配置或截图。

## 文档

- [API.md](API.md)：命令行、MCP、HTTP 的完整说明，以及会话、盯守、权限和安全、远程连接、已知限制。
- [docs/REFERENCE.md](docs/REFERENCE.md)：每条命令的参数和每个结果代码（由代码生成）。
- [docs/TASKS.md](docs/TASKS.md)：发版前在真机上跑的固定任务集。
- [docs/CHANGELOG.zh-CN.md](docs/CHANGELOG.zh-CN.md)：更早版本的更新内容。

## 1.4.2-beta3 更新内容

- **可直接用 Root**：已 root 的手机可以不装 Shizuku。默认仍用 Shizuku，不需要 root。
- **自己在副屏打开应用**：从首页打开，不必通过 agent。你使用副屏时，agent 会收到 `BUSY`；首页会显示你离开时的画面。
- **更新渠道**：在设置里选择 **正式版** 或 **测试版（含正式版）**，更新说明支持 Markdown。
- **更少卡死**：后台线程出错不会让整个进程退出，副屏服务和文字识别有超时，应用列表会缓存。

## 从源码编译

需要 JDK 21、Android SDK（compileSdk 37）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

内置 Tailscale 是一个用 Go 写的小程序（`tsbridge/`）。编 APK 之前先运行一次 `tsbridge/build.sh`，生成 `app/src/main/jniLibs/arm64-v8a/libtsbridge.so`；不运行也能编，只是没有内置 Tailscale。

正式签名可选：把 `keystore.properties.example` 复制成 `keystore.properties` 并填好，不填就用 debug 密钥签名。

## 许可

MIT。内置的 OCR 模型（`app/src/main/assets/vscreen-ocr/`）来自 PaddleOCR，沿用它的 Apache-2.0 许可。无字图标识别模型（`app/src/main/assets/vscreen-detector/model.onnx`，以及可选下载的大模型，见 [agent-vscreen-models](https://github.com/kilolonion/agent-vscreen-models)）是单独的文件，采用 AGPL-3.0（见同目录 NOTICE.txt，训练代码在 `tools/detector`）；App 只把它当数据读取，开启前会先说明许可。

## 致谢

- [LINUX DO](https://linux.do)
- [OpenMinis](https://github.com/OpenMinis)
