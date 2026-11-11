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
            "description": { "zh": "Agent-VScreen 的令牌：在 Agent-VScreen › 接入 agent › 3. 令牌 里复制（或在 权限 › 令牌 里给 Operit 新建一个）", "en": "Agent-VScreen token: copy it in Agent-VScreen › Connect an agent › 3. Token (or make one for Operit under Permissions › Tokens)" },
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
                "zh": "Agent-VScreen 使用建议：\\n- 用户让你在手机上做事（打开某个应用、查信息、比价、签到、看某页内容）时，用 vscreen 在副屏上做，不要动用户的主屏，也不要用 adb、screencap、input 代替。\\n- 每次任务开头先调用 vscreen，命令填 guide（一页，讲“看→做→核对”的循环和出错时怎么办）；提示没有令牌时，请用户在 Agent-VScreen › 接入 agent › 3. 令牌 复制令牌，再用 vscreen_configure 保存。\\n- 常用命令：launch <应用名>；act \"tap 搜索; type 耳机; key enter until text:综合\"；act（看一眼）；help act；done（这一轮做完）。\\n- 每次返回文字和截图；没做成时末尾有 ok=false、code 和 next（look 先看一眼 / fix_command 改命令 / ask_user 问用户 / retry_later 稍后再试），照 next 做。画面的意思由你看图判断。\\n- 付款等高风险步骤由用户在手机上确认：CONFIRM_PENDING 就请用户去手机上确认后再试，USER_DENIED 不要重试。登录、发送、删除、隐私内容也先问用户。用户在悬浮窗上按了停止（USER_STOPPED）就停下，把解除方法转告用户。",
                "en": "Agent-VScreen advice:\\n- When the user asks you to do something on the phone (open an app, look something up, compare prices, check in, read a page), do it with vscreen on the background screen; don't touch the user's screen, and don't use adb, screencap or input instead.\\n- At the start of each task call vscreen with the command guide (one page: the look → act → check loop and what to do when something fails); if it says the token is missing, ask the user to copy it in Agent-VScreen › Connect an agent › 3. Token, then save it with vscreen_configure.\\n- Common commands: launch <app>; act \"tap Search; type headphones; key enter until text:All\"; act (take a look); help act; done (this turn is finished).\\n- Every result is text plus screenshots; when a command did not do what was asked it ends with ok=false, a code and next (look / fix_command / ask_user / retry_later): follow next. You judge what the screen means by looking.\\n- High-risk steps such as paying are confirmed by the user on the phone: on CONFIRM_PENDING ask the user to confirm there and try again; on USER_DENIED do not retry. Ask the user before logging in, sending, deleting or touching private content too. If the user pressed Stop on the floating window (USER_STOPPED), stop and pass on how to undo it."
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
                "zh": "保存 Agent-VScreen 的令牌和地址（存进 Operit 的环境变量），并检测能否连上。",
                "en": "Save the Agent-VScreen token and address (as Operit environment variables) and test the connection."
            },
            "parameters": [
                {
                    "name": "token",
                    "description": { "zh": "Agent-VScreen 的令牌（用户在 App 里复制给你）", "en": "Agent-VScreen token (the user copies it from the app)" },
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
// Talks to Agent-VScreen 1.2's /v2 API. On this phone: Authorization: Bearer <token>. From another device (Tailscale):
// a signed request (X-VScreen-Key/Time/Nonce/Sign), so the token itself never travels — 1.2 refuses unsigned remote calls.
const agentVScreen = (function () {
    const DEFAULT_URL = "http://127.0.0.1:18765";
    const MAX_IMAGES = 4;

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
        return t("还没有 Agent-VScreen 的令牌：请用户打开 Agent-VScreen，在“接入 agent › 3. 令牌”里复制令牌（或在“权限 › 令牌”里给 Operit 新建一个）发给你，然后调用 vscreen_configure 保存（或在 Operit 的环境变量里填 VSCREEN_TOKEN）。还没装 Agent-VScreen 的话，先装好并按 App 首页完成配置。",
            "No Agent-VScreen token yet: ask the user to open Agent-VScreen and copy the token under \"Connect an agent › 3. Token\" (or make one for Operit under \"Permissions › Tokens\") and send it to you, then save it with vscreen_configure (or set VSCREEN_TOKEN in Operit's environment variables). If Agent-VScreen is not installed, install it and finish the setup on its home page first.");
    }
    function unreachable(cfg, why) {
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

    async function run(cfg, command, session) {
        let res;
        try {
            // act can wait for minutes (wait / until / --watch); the server answers when the stretch ends.
            res = await post(cfg, "/v2/run", { cmd: command, session: session, request_id: "operit-" + randomId(12) }, 330);
        } catch (e) {
            return { ok: false, text: unreachable(cfg, e && e.message ? e.message : String(e)) };
        }
        let data;
        try { data = JSON.parse(String(res.content || "")); } catch (e) {
            return { ok: false, text: unreachable(cfg, "HTTP " + res.statusCode) };
        }
        if (res.statusCode === 401 || res.statusCode === 403 || res.statusCode === 429) {
            const why = String((data && (data.error || data.text || data.message)) || "");
            const code = String((data && data.code) || res.statusCode);
            const fix = (code === "BAD_TOKEN" || code === "BAD_SIGNATURE")
                ? t("请用户在 Agent-VScreen 的“接入 agent › 3. 令牌”里重新复制令牌，再调用 vscreen_configure 保存。", "Ask the user to copy the token again under \"Connect an agent › 3. Token\" in Agent-VScreen, then save it with vscreen_configure.")
                : t("把这句原样转告用户。", "Pass this on to the user as it is.");
            return { ok: false, code: code, text: code + (why ? "：" + why : "") + "\n" + fix };
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

    async function vscreen_configure(params) {
        const over = {};
        if (params && params.token != null && String(params.token).trim()) over.token = String(params.token).trim();
        if (params && params.base_url != null && String(params.base_url).trim()) over.url = baseUrl(params.base_url);
        if (over.token) await Tools.SoftwareSettings.writeEnvironmentVariable("VSCREEN_TOKEN", over.token);
        if (over.url) await Tools.SoftwareSettings.writeEnvironmentVariable("VSCREEN_URL", over.url);
        const cfg = config(over);
        if (!cfg.token) return noToken();
        const r = await run(cfg, "status", "operit");
        if (!r.ok) return r.text;
        return t("已保存，连接正常（" + cfg.url + "）。现在可以用 vscreen 了：先 guide 看用法，再 launch <应用名>。", "Saved and connected (" + cfg.url + "). vscreen is ready: guide first for the usage, then launch <app>.");
    }

    return { vscreen: vscreen, vscreen_configure: vscreen_configure, _sha256: sha256, _authHeaders: authHeaders };
})();

exports.vscreen = agentVScreen.vscreen;
exports.vscreen_configure = agentVScreen.vscreen_configure;
