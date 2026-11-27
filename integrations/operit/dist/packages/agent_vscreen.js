/* METADATA
{
    "name": "agent_vscreen",
    "display_name": {
        "zh": "Agent-VScreen 后台操作",
        "en": "Agent-VScreen Background Operation"
    },
    "description": {
        "zh": "在一块看不见的副屏上打开、查看和操作手机上的任何应用，不占用用户正在用的主屏。需要安装 Agent-VScreen App（Shizuku 授权）。",
        "en": "Open, read and operate any app on the phone on an invisible background screen, without taking the screen the user is using. Requires the Agent-VScreen app (Shizuku permission)."
    },
    "enabledByDefault": true,
    "category": "System",
    "env": [
        {
            "name": "VSCREEN_TOKEN",
            "description": { "zh": "Agent-VScreen 的令牌：在 Agent-VScreen › 接入 › 新建接入 里生成（或在 设置 › 令牌 里给 Operit 新建一个）", "en": "Agent-VScreen token: create one in Agent-VScreen › Connect › Connect an agent (or make one for Operit under Settings › Tokens)" },
            "required": false
        },
        {
            "name": "VSCREEN_URL",
            "description": { "zh": "Agent-VScreen 地址，默认 http://127.0.0.1:18765；Agent-VScreen 在别的手机上时填它的 Tailscale 地址", "en": "Agent-VScreen address, default http://127.0.0.1:18765; use its Tailscale address when it runs on another phone" },
            "required": false
        }
    ],
    "tools": [
        {
            "name": "usage_advice",
            "description": {
                "zh": "Agent-VScreen 使用建议：\n- 用户要你在手机上做事时，用 vscreen 在后台副屏上做；不要动用户的主屏，也不要用 adb、screencap、input 代替。\n- 每次任务开头调用 vscreen，命令填 guide（一页用法）；提示缺令牌时，请用户在 Agent-VScreen › 接入 › 新建接入（或 设置 › 令牌）里生成令牌，再用 vscreen_configure 保存。\n- 用法是一个循环：① 看：launch 或不带步骤的 act，读【现在】和截图。② 做：act 一小段步骤，最后一步写 until <你期待看到的>。③ 核对：ok 为真就继续；为假时这段停在了某一步，按 code / next 处理（help codes），先看图再决定。\n- next=`user_on_phone`（NEEDS_CONFIRM / CONFIRM_PENDING / ASK_PENDING）：请求已在手机上，用户多半没看到（通知常静音）：立刻把回复里的 tell_user 发给用户，等他说好了再原样重发，不要绕。\n- next=`ask_user`：只有用户能解决：停下告诉用户原因，不要重试或绕，被拒的事不要再问；权限不够用 `elevate` 申请。\n遇到这些情况：\n- 用户要我在手机上做任何事：打开某个应用、查信息、比价、看某页内容、填表、发帖、测试应用…… → `launch <应用>` 打开并看，`act \"<步骤>\"` 点、滑、输入、按键，每次都返回截图和元素列表\n- 我要为一件会用到手机的事设定时：用户说了定时、每天、每周、到点（签到、打卡、抢购、定时查看），或我自己建了定时任务、周期任务、cron、自动化、提醒 → 两边都要设：我的定时只叫醒我，叫不醒手机。同一时间用 `schedule \"<时间>\" --name <名字>` 给手机预约，它会在每次之前拉起服务、检查权限，没准备好就提醒用户；改时间、取消时两边一起改\n- 要等一件以后才发生的事：到货、降价、有回复、直播开始、排队轮到、下载完成；或我想隔一会儿再看一次画面 → `act --stop \"text:到货\" --keep notify` 让手机一直盯着，看到就通知用户（`--keep wake` 改成叫醒我，用 `events` 取）\n- 要读完一个很长的列表或整页内容（全部订单、聊天记录、搜索结果、评论） → `collect --pages 10` 自动翻页，返回去重后的每一条\n- 不确定用户指的是画面上哪个东西（哪个商品、哪个按钮、哪条消息），或要用户选一个 → `ask \"你说的是哪一个？\"` 让用户在手机上的副屏画面里点出来（也可以 `--choose`、`--text`）\n- 一个流程说不清、试了几次都不对，或用户自己更清楚怎么做 → `teach \"要演示什么\"` 请用户在副屏上亲手做一遍，存成一段，之后 `act \"use 段名 参数\"` 照做\n- 同一个应用会反复用：有固定入口、每次都弹的窗、要先暂停的画面，或对机器节奏敏感 → `note` 给这个应用记经验、存步骤（`note save`）、设前置/收尾步骤和仿人节奏，下次 launch 会列出来\n- 有一步只能用户本人做（密码、验证码、人脸、扫码），或要用的应用用户正在用 → `handoff out` 把应用交到用户主屏并告诉他做什么；`handoff in` 把主屏上的应用接到副屏\n- 应用没声音，或用户要让某个应用静音、恢复声音 → `sound` 查看，`sound mute|unmute <应用>` 静音或恢复\n- 切换或暂存应用 → `tasks` 查任务；`launch --keep` 切回原页；`handoff park/unpark` 暂存或取回\n- 要做的事超出我的许可（如卸载、系统操作） → `permissions` 查权限，`elevate <类别> --why 原因` 申请\n- 画面上的字太小、是图标，或者根本没有字（游戏、画布、图表），或想回看刚才发生了什么 → `look` 要图：现在的画面、放大一块、回看录像都用它；`spots` 找可点的色块\n- 连不上手机、命令一直失败或出了怪问题 → 先分清是没开还是坏了：没在运行、服务关着或收起是用户的选择，转告用户打开即可，不用修；`status` 里 ready 为假时才是故障，`fix` 修它列出的项，也恢复被留下静音的应用",
                "en": "Agent-VScreen advice:\n- When the user asks for anything on the phone, do it with vscreen on the background screen; don't touch the user's screen, and don't use adb, screencap or input instead.\n- At the start of each task call vscreen with the command guide (one page of usage); if it says the token is missing, ask the user to create one in Agent-VScreen › Connect › Connect an agent (or Settings › Tokens), then save it with vscreen_configure.\n- Use it as a loop: (1) look: launch, or act with no steps; read [Now] and the screenshot. (2) act: a short stretch of steps, the last one with until <what you expect to see>. (3) check: ok true, go on; false, the stretch stopped at a step: follow code / next (help codes) and look at the picture before deciding.\n- next=`user_on_phone` (NEEDS_CONFIRM / CONFIRM_PENDING / ASK_PENDING): the request is on the phone and the user most likely hasn't seen it (notifications are often silent). Send the user the reply's tell_user line now, wait until they say it's done, then resend unchanged; don't go around it or keep resending.\n- next=`ask_user`: only the user can lift it: stop and tell them why; don't retry, go around, or ask again what was refused. For more permission ask with `elevate`.\nWhen:\n- the user wants anything done on the phone: open an app, look something up, read a page, fill a form, test an app… → `launch <app>` opens it and looks, `act \"<steps>\"` taps, swipes, types and presses keys; every reply has a screenshot and the elements\n- anything timed that will use the phone: the user asks for daily or at 8:00 (check-ins, periodic checks), or I set up my own timer, cron or reminder → set both: my timer wakes me, not the phone. Book the same time with `schedule \"<when>\" --name <name>`; change or cancel both together\n- something will happen later: back in stock, a price drop, a reply, a download finishing; or I want to look again in a while → `act --stop \"text:In stock\" --keep notify` makes the phone keep watching and notify the user when seen (`--keep wake` wakes me; fetch with `events`)\n- a long list or a whole page has to be read (all orders, a chat history, search results, comments) → `collect --pages 10` pages through it and returns every item once\n- it is unclear which thing on the screen the user means (which item, button, message), or the user should pick one → `ask \"Which one do you mean?\"` lets the user tap it on the screen's picture on the phone (also `--choose`, `--text`)\n- a flow is hard to describe, keeps failing, or the user knows better how to do it → `teach \"what to show\"` asks the user to do it once on the screen; it is saved as a segment, then `act \"use <name> <values>\"` replays it\n- the same app comes up again and again: a fixed way in, a popup every visit, a picture to pause first, or machine-like timing upsets it → `note` keeps what I learned about the app, saved steps (`note save`), before/after steps and human pacing; the next launch lists them\n- a step only the user can do (password, code, face, scanning), or the app I need is the one the user is using → `handoff out` puts the app on the user's screen with what to do; `handoff in` takes the app from the user's screen\n- an app has no sound, or the user wants an app muted or its sound back → `sound` shows it, `sound mute|unmute <app>` mutes or unmutes\n- switch or park apps → `tasks` lists tasks; `launch --keep` switches without reopening; `handoff park/unpark` sets aside or restores apps\n- the task needs more than my permission (uninstalling, system actions…) → `permissions` shows mine, `elevate <class> --why <reason>` asks\n- the text is too small or an icon, or there is no text at all (games, canvas, charts), or I need to see what just happened → `look` asks for pictures: the screen now, a region up close, or the recording; `spots` finds tappable color patches\n- the phone cannot be reached, commands keep failing, or something odd happens → First tell off from broken: not running, service off or hidden is the user's choice; tell the user to open it, nothing to repair. Only `status` with ready false is a fault, and `fix` repairs what it lists and gives sound back to apps left silent"
            },
            "parameters": [],
            "advice": true
        },
        {
            "name": "vscreen",
            "description": {
                "zh": "在副屏上操作手机应用。command 写 vscreen 命令行后面的部分，例如 guide、launch 设置、act \"tap 搜索; type 耳机; key enter\"、act、status、help all。返回文字和截图，没做成时附 code 和 next。",
                "en": "Operate phone apps on the background screen. command is what follows vscreen on the command line, e.g. guide, launch Settings, act \"tap Search; type headphones; key enter\", act, status, help all. Returns text and screenshots, plus code and next when it did not succeed."
            },
            "parameters": [
                {
                    "name": "command",
                    "description": { "zh": "vscreen 命令，如 launch 设置 或 act \"tap 搜索\"", "en": "vscreen command, e.g. launch Settings or act \"tap Search\"" },
                    "type": "string",
                    "required": true
                },
                {
                    "name": "session",
                    "description": { "zh": "会话名（可选，默认 operit）：一个副屏同一时间只属于一个会话", "en": "Session name (optional, default operit): a screen belongs to one session at a time" },
                    "type": "string",
                    "required": false
                }
            ]
        },
        {
            "name": "vscreen_configure",
            "description": {
                "zh": "保存 Agent-VScreen 的令牌或兑换 10 分钟一次性接入码（存进 Operit 的环境变量），并检测能否连上。",
                "en": "Save the Agent-VScreen token or exchange a 10-minute one-time onboarding code (as Operit environment variables) and test the connection."
            },
            "parameters": [
                {
                    "name": "code",
                    "description": { "zh": "10 分钟一次性接入码（avc_ 开头，由插件直接兑换为长期令牌并保存）", "en": "10-minute one-time onboarding code (starts with avc_; exchanged directly for a permanent token and saved)" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "token",
                    "description": { "zh": "Agent-VScreen 的令牌（或一次性接入码）", "en": "Agent-VScreen token (or one-time onboarding code)" },
                    "type": "string",
                    "required": false
                },
                {
                    "name": "base_url",
                    "description": { "zh": "地址（可选），默认 http://127.0.0.1:18765", "en": "Address (optional), default http://127.0.0.1:18765" },
                    "type": "string",
                    "required": false
                }
            ]
        }
    ]
}
*/
"use strict";
// Talks to Agent-VScreen's /v2 API (1.2 or newer). On this phone: Authorization: Bearer <token>. From another device (Tailscale):
// a signed request (X-VScreen-Key/Time/Nonce/Sign), so the token itself never travels — 1.2 refuses unsigned remote calls.
const agentVScreen = (function () {
    const DEFAULT_URL = "http://127.0.0.1:18765";
    const MAX_IMAGES = 8; // 1.4.0-beta2: the app decides which pictures (ImagePolicy); this is only a safety cap

    function zh() {
        try {
            const lang = typeof getLang === "function" ? String(getLang() || "") : "";
            if (lang) return lang.toLowerCase().startsWith("zh");
        } catch (e) { /* fall through */ }
        return true;
    }
    function t(zhText, enText) { return zh() ? zhText : enText; }

    function env(name) {
        if (typeof getEnv !== "function") return "";
        const v = getEnv(name);
        return v == null ? "" : String(v).trim();
    }
    function baseUrl(raw) {
        let v = String(raw || "").trim().replace(/\/+$/g, "");
        if (!v) v = DEFAULT_URL;
        if (!/^https?:\/\//i.test(v)) v = "http://" + v;
        if (!/:\d+$/.test(v.replace(/^https?:\/\//i, "").split("/")[0])) v = v + ":18765";
        return v;
    }
    function config(over) {
        return {
            url: baseUrl(over && over.url != null ? over.url : env("VSCREEN_URL")),
            token: String(over && over.token != null ? over.token : env("VSCREEN_TOKEN")).trim()
        };
    }
    function isLocal(url) {
        const host = url.replace(/^https?:\/\//i, "").split("/")[0].replace(/:\d+$/, "").toLowerCase();
        return host === "127.0.0.1" || host === "localhost" || host === "[::1]";
    }

    // ── SHA-256 (hex) over bytes; no crypto API is assumed in the ToolPkg runtime ──
    const K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    function utf8(s) {
        const out = [];
        for (let i = 0; i < s.length; i += 1) {
            let c = s.charCodeAt(i);
            if (c >= 0xd800 && c < 0xdc00 && i + 1 < s.length) { const d = s.charCodeAt(i + 1); if (d >= 0xdc00 && d < 0xe000) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i += 1; } }
            if (c < 0x80) out.push(c);
            else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
            else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
            else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        }
        return out;
    }
    function sha256(text) {
        const m = utf8(String(text));
        const bits = m.length * 8;
        m.push(0x80);
        while (m.length % 64 !== 56) m.push(0);
        for (let i = 7; i >= 0; i -= 1) m.push(i >= 4 ? 0 : (bits >>> (i * 8)) & 255);
        const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
        const w = new Array(64);
        const r = (x, n) => (x >>> n) | (x << (32 - n));
        for (let o = 0; o < m.length; o += 64) {
            for (let i = 0; i < 16; i += 1) w[i] = (m[o + 4 * i] << 24) | (m[o + 4 * i + 1] << 16) | (m[o + 4 * i + 2] << 8) | m[o + 4 * i + 3];
            for (let i = 16; i < 64; i += 1) {
                const s0 = r(w[i - 15], 7) ^ r(w[i - 15], 18) ^ (w[i - 15] >>> 3);
                const s1 = r(w[i - 2], 17) ^ r(w[i - 2], 19) ^ (w[i - 2] >>> 10);
                w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
            }
            let [a, b, c, d, e, f, g, hh] = h;
            for (let i = 0; i < 64; i += 1) {
                const t1 = (hh + (r(e, 6) ^ r(e, 11) ^ r(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
                const t2 = ((r(a, 2) ^ r(a, 13) ^ r(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
                hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
            }
            h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
            h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
        }
        return h.map(x => ("00000000" + (x >>> 0).toString(16)).slice(-8)).join("");
    }

    // A token is `avs_<id>_<secret>` (one per agent) or the older main token (id "main", the whole text is the secret).
    function parseToken(token) {
        const m = /^avs_([a-z0-9]{4,12})_([A-Za-z0-9]{16,64})$/.exec(token);
        return m ? { id: m[1], secret: m[2] } : { id: "main", secret: token };
    }
    function randomId(n) {
        const abc = "abcdefghijklmnopqrstuvwxyz0123456789";
        let s = "";
        for (let i = 0; i < n; i += 1) s += abc.charAt(Math.floor(Math.random() * abc.length));
        return s;
    }
    // Every non-ASCII character as \uXXXX: the bytes on the wire are then exactly the string that was signed.
    function asciiJson(o) {
        return JSON.stringify(o).replace(/[\u007f-￿]/g, ch => "\\u" + ("0000" + ch.charCodeAt(0).toString(16)).slice(-4));
    }
    function authHeaders(cfg, method, target, body) {
        const h = { "X-VScreen-Lang": zh() ? "zh" : "en" };
        if (isLocal(cfg.url)) { h["Authorization"] = "Bearer " + cfg.token; return h; }
        const tk = parseToken(cfg.token);
        const key = sha256(tk.secret);
        const time = String(Math.floor(Date.now() / 1000));
        const nonce = randomId(16);
        h["X-VScreen-Key"] = tk.id;
        h["X-VScreen-Time"] = time;
        h["X-VScreen-Nonce"] = nonce;
        h["X-VScreen-Sign"] = sha256(key + "\n" + time + "\n" + nonce + "\n" + method + "\n" + target + "\n" + sha256(body) + "\n" + key);
        return h;
    }

    function noToken() {
        return t("还没有 Agent-VScreen 的令牌：请用户打开 Agent-VScreen，在“接入 › 新建接入”里生成令牌（或在“设置 › 令牌”里给 Operit 新建一个）发给你，然后调用 vscreen_configure 保存（或在 Operit 的环境变量里填 VSCREEN_TOKEN）。还没装 Agent-VScreen 的话，先装好并按 App 首页完成配置。",
            "No Agent-VScreen token yet: ask the user to open Agent-VScreen and create a token under \"Connect › Connect an agent\" (or make one for Operit under \"Settings › Tokens\") and send it to you, then save it with vscreen_configure (or set VSCREEN_TOKEN in Operit's environment variables). If Agent-VScreen is not installed, install it and finish the setup on its home page first.");
    }
    function unreachable(cfg, why) {
        // 1.4.0-beta4: on this phone, nothing listening means the app is simply not running: not a fault to repair.
        if (/^http:\/\/(127\.0\.0\.1|localhost)[:/]/i.test(cfg.url))
            return t("Agent-VScreen 没在运行（" + cfg.url + "，" + why + "）。这不是故障，不用修：请用户打开一次 Agent-VScreen；手机重启过的话先启动 Shizuku。",
                "Agent-VScreen is not running (" + cfg.url + ", " + why + "). This is not a fault and needs no repair: ask the user to open Agent-VScreen once; after a phone restart, start Shizuku first.");
        return t("连不上 Agent-VScreen（" + cfg.url + "，" + why + "）：请用户打开 Agent-VScreen，确认首页“服务”在运行；手机重启后要先启动 Shizuku 再打开它。Agent-VScreen 在别的设备上时，用 vscreen_configure 填它的 Tailscale 地址。",
            "Cannot reach Agent-VScreen (" + cfg.url + ", " + why + "): ask the user to open Agent-VScreen and check that the service is running on its home page; after a phone restart, start Shizuku first, then open it. If Agent-VScreen runs on another device, set its Tailscale address with vscreen_configure.");
    }

    async function post(cfg, path, payload, seconds) {
        const body = asciiJson(payload);
        const headers = authHeaders(cfg, "POST", path, body);
        headers["Accept"] = "application/json";
        headers["Content-Type"] = "application/json; charset=utf-8";
        return await Tools.Net.http({
            url: cfg.url + path,
            method: "POST",
            headers: headers,
            body: body,
            connect_timeout: 10,
            read_timeout: seconds,
            validateStatus: false
        });
    }

    async function image(cfg, url) {
        // The server builds picture links from the address we called, so only the path is ours to sign.
        const path = "/" + url.replace(/^https?:\/\/[^/]+\//i, "");
        const r = await Tools.Net.http({
            url: cfg.url + path,
            method: "GET",
            headers: authHeaders(cfg, "GET", path, ""),
            connect_timeout: 10,
            read_timeout: 30,
            responseType: "arraybuffer",
            validateStatus: false
        });
        if (!r || r.statusCode >= 400 || !r.contentBase64) return "";
        return NativeInterface.registerImageFromBase64(String(r.contentBase64), "image/jpeg");
    }

    // 1.4.0-beta15: on this phone the service takes the first free port of the ten from the set one (another app, or
    // a copy of the app in a parallel space, may hold it). The instance holding THIS token proves it on
    // /v2/ping?me=<id>&n=<nonce> with sha256hex(K \n nonce \n ping \n K); the port found is kept for the next calls.
    let foundUrl = null;
    async function locate(cfg) {
        if (!isLocal(cfg.url) || !cfg.token) return cfg;
        if (foundUrl && foundUrl.from === cfg.url) return { url: foundUrl.url, token: cfg.token };
        const m = /^(.*):(\d+)$/.exec(cfg.url);
        if (!m) return cfg;
        const tk = parseToken(cfg.token);
        const key = sha256(tk.secret);
        const nonce = randomId(16);
        const want = sha256(key + "\n" + nonce + "\nping\n" + key);
        for (let i = 0; i < 10; i += 1) {
            const url = m[1] + ":" + (Number(m[2]) + i);
            try {
                const r = await Tools.Net.http({ url: url + "/v2/ping?me=" + encodeURIComponent(tk.id) + "&n=" + nonce, method: "GET", connect_timeout: 2, read_timeout: 2, validateStatus: false });
                const d = JSON.parse(String((r && r.content) || "{}"));
                if (d && d.you === want) { foundUrl = { from: cfg.url, url: url }; return { url: url, token: cfg.token }; }
            } catch (e) { if (i === 0 && /timeout/i.test(String(e && e.message))) return cfg; }
        }
        return cfg;
    }

    async function run(cfg0, command, session) {
        const usedCached = Boolean(foundUrl && foundUrl.from === cfg0.url);
        let cfg = await locate(cfg0);
        const reqId = "operit-" + randomId(12);
        let res;
        try {
            // act can wait for minutes (wait / until / --watch); the server answers when the stretch ends.
            res = await post(cfg, "/v2/run", { cmd: command, session: session, request_id: reqId }, 330);
        } catch (e) {
            if (usedCached) {
                foundUrl = null;
                cfg = await locate(cfg0);
                try {
                    res = await post(cfg, "/v2/run", { cmd: command, session: session, request_id: reqId }, 330);
                } catch (e2) {
                    return { ok: false, text: unreachable(cfg, e2 && e2.message ? e2.message : String(e2)) };
                }
            } else {
                return { ok: false, text: unreachable(cfg, e && e.message ? e.message : String(e)) };
            }
        }
        let data;
        try { data = JSON.parse(String(res.content || "")); } catch (e) {
            if (usedCached) {
                foundUrl = null;
                cfg = await locate(cfg0);
                try {
                    res = await post(cfg, "/v2/run", { cmd: command, session: session, request_id: reqId }, 330);
                    data = JSON.parse(String(res.content || ""));
                } catch (e2) {
                    return { ok: false, text: unreachable(cfg, res ? "HTTP " + res.statusCode : String(e2)) };
                }
            } else {
                return { ok: false, text: unreachable(cfg, "HTTP " + res.statusCode) };
            }
        }
        if (res.statusCode === 401 || res.statusCode === 403 || res.statusCode === 429) {
            const why = String((data && (data.error || data.text || data.message)) || "");
            const code = String((data && data.code) || res.statusCode);
            const fix = (code === "BAD_TOKEN" || code === "BAD_SIGNATURE")
                ? t("请用户在 Agent-VScreen 的“接入 › 新建接入”（或“设置 › 令牌”）里重新生成令牌，再调用 vscreen_configure 保存。", "Ask the user to create a new token under \"Connect › Connect an agent\" (or \"Settings › Tokens\") in Agent-VScreen, then save it with vscreen_configure.")
                : t("把这句原样转告用户。", "Pass this on to the user as it is.");
            return { ok: false, code: code, text: code + (why ? "：" + why : "") + "\n" + fix };
        }
        if (res.statusCode !== 200 || !data || typeof data.ok !== "boolean") {
            const why = data && (data.text || data.error || data.message);
            return { ok: false, text: "HTTP " + res.statusCode + " · " + (why || t("响应缺少有效的命令结果", "Missing a valid command result")) };
        }
        let text = String(data.text || data.error || "");
        const images = Array.isArray(data.images) ? data.images.slice(0, MAX_IMAGES) : [];
        const links = [];
        for (let i = 0; i < images.length; i += 1) {
            const img = images[i] || {};
            let link = "";
            try { link = img.url ? await image(cfg, String(img.url)) : ""; } catch (e) { link = ""; }
            const label = t("[图" + (i + 1) + "]", "[picture " + (i + 1) + "]");
            if (img.url) text = text.split(String(img.url)).join(link ? label : String(img.url));
            if (link) links.push(label + " " + link);
        }
        if (links.length) text += "\n\n" + links.join("\n");
        // /v2: ok is true only when the command did what was asked; code + next say what to do otherwise.
        if (data.ok === false && data.code) text += "\n\n" + t("结果：", "Result: ") + "ok=false code=" + data.code + " next=" + (data.next || "none") + t("（help codes 查每个代码的含义）", " (help codes explains each code)");
        return { ok: data.ok !== false, code: data.code || null, text: text };
    }

    async function vscreen(params) {
        const command = String((params && params.command) || "").trim();
        if (!command) return t("command 不能为空，例如 guide、launch 设置、act、status、help all", "command is empty; e.g. guide, launch Settings, act, status, help all");
        const cfg = config();
        if (!cfg.token) return noToken();
        const session = String((params && params.session) || "").trim() || "operit";
        const r = await run(cfg, command, session);
        return r.text;
    }

    async function exchangeCode(url, code) {
        let targetUrl = url;
        if (isLocal(url)) {
            const m = /^(.*):(\d+)$/.exec(url);
            if (m) {
                for (let i = 0; i < 10; i += 1) {
                    const probe = m[1] + ":" + (Number(m[2]) + i);
                    try {
                        const pr = await Tools.Net.http({ url: probe + "/v2/ping", method: "GET", connect_timeout: 2, read_timeout: 2, validateStatus: false });
                        const pd = JSON.parse(String((pr && pr.content) || "{}"));
                        if (pd && pd.app === "agent-vscreen") { targetUrl = probe; break; }
                    } catch (e) { /* try next port */ }
                }
            }
        }
        let r;
        try {
            r = await Tools.Net.http({
                url: targetUrl + "/v2/pair/exchange",
                method: "POST",
                headers: {
                    "Content-Type": "application/json; charset=utf-8",
                    "Accept": "application/json",
                    "X-VScreen-Lang": zh() ? "zh" : "en"
                },
                body: asciiJson({ code: code }),
                connect_timeout: 10,
                read_timeout: 15,
                validateStatus: false
            });
        } catch (e) {
            return { ok: false, text: unreachable({ url: targetUrl, token: "" }, e && e.message ? e.message : String(e)) };
        }
        let data = null;
        try { data = JSON.parse(String((r && r.content) || "")); } catch (e) { data = null; }
        if (r && r.statusCode === 200 && data && data.ok && data.token) {
            return { ok: true, token: String(data.token), url: targetUrl };
        }
        const errCode = String((data && data.code) || (r ? r.statusCode : "ERROR"));
        const why = String((data && (data.error || data.text || data.message)) || "");
        if (errCode === "CODE_INVALID_OR_EXPIRED" || (r && r.statusCode === 401)) {
            return {
                ok: false,
                text: "CODE_INVALID_OR_EXPIRED：" + (why || t("一次性接入码无效、已过期（超过 10 分钟）或已被使用。", "The one-time onboarding code is invalid, expired, or already used.")) + "\n" +
                    t("请用户在 Agent-VScreen 的“接入”里重新生成 10 分钟接入码再发给你。", "Ask the user to generate a fresh 10-minute onboarding code under Connect in Agent-VScreen.")
            };
        }
        return { ok: false, text: "HTTP " + (r ? r.statusCode : 0) + " · " + errCode + (why ? "：" + why : "") };
    }

    async function vscreen_configure(params) {
        const over = {};
        if (params && params.base_url != null && String(params.base_url).trim()) over.url = baseUrl(params.base_url);
        let rawCode = (params && params.code != null) ? String(params.code).trim() : "";
        const rawToken = (params && params.token != null) ? String(params.token).trim() : "";
        if (!rawCode && rawToken.startsWith("avc_")) {
            rawCode = rawToken;
        } else if (rawToken) {
            over.token = rawToken;
        }
        if (rawCode) {
            const targetUrl = over.url || baseUrl(env("VSCREEN_URL"));
            const ex = await exchangeCode(targetUrl, rawCode);
            if (!ex.ok) return ex.text;
            over.token = ex.token;
            foundUrl = null;
        }
        // Operit < 1.10 has no Tools.SoftwareSettings: the values cannot be saved from here. Check the connection with
        // them anyway, then tell the user where to put them by hand instead of failing with a script error.
        const settings = (typeof Tools !== "undefined" && Tools.SoftwareSettings && typeof Tools.SoftwareSettings.writeEnvironmentVariable === "function") ? Tools.SoftwareSettings : null;
        let saved = true;
        try {
            if (settings && over.token) await settings.writeEnvironmentVariable("VSCREEN_TOKEN", over.token);
            if (settings && over.url) await settings.writeEnvironmentVariable("VSCREEN_URL", over.url);
        } catch (e) { saved = false; }
        if (!settings) saved = false;
        const cfg = config(over);
        if (!cfg.token) return noToken();
        const r = await run(cfg, "status", "operit");
        const manual = t("这个 Operit 版本不能由工具保存设置（需要 Operit 1.10 或更新）。请在 Operit 的工具包设置里手动填写环境变量 VSCREEN_TOKEN" + (over.url ? " 和 VSCREEN_URL（" + cfg.url + "）" : "") + "，或者升级 Operit。",
            "This Operit version cannot save settings from a tool (needs Operit 1.10 or newer). Set the environment variable VSCREEN_TOKEN" + (over.url ? " and VSCREEN_URL (" + cfg.url + ")" : "") + " by hand in Operit's toolkit settings, or update Operit.");
        if (!r.ok) return saved ? r.text : r.text + "\n" + manual;
        if (!saved) return t("连接正常（" + cfg.url + "），但没有保存：", "Connected (" + cfg.url + "), but nothing was saved: ") + manual;
        return t("已保存，连接正常（" + cfg.url + "）。现在可以用 vscreen 了：先 guide 看用法，再 launch <应用名>。", "Saved and connected (" + cfg.url + "). vscreen is ready: guide first for the usage, then launch <app>.");
    }

    return { vscreen: vscreen, vscreen_configure: vscreen_configure, _sha256: sha256, _authHeaders: authHeaders };
})();

exports.vscreen = agentVScreen.vscreen;
exports.vscreen_configure = agentVScreen.vscreen_configure;
