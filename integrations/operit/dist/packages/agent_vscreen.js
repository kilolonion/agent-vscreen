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
            "description": { "zh": "Agent-VScreen 的令牌：在 Agent-VScreen › 接入 agent › MCP 里复制", "en": "Agent-VScreen token: copy it in Agent-VScreen › Connect an agent › MCP" },
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
                "zh": "Agent-VScreen 使用建议：\\n- 用户让你在手机上做事（打开某个应用、查信息、比价、签到、看某页内容）时，用 vscreen 在副屏上做，不要动用户的主屏，也不要用 adb、screencap、input 代替。\\n- 第一次用先调用 vscreen，命令填 status；提示没有令牌时，请用户在 Agent-VScreen › 接入 agent › MCP 复制令牌，再用 vscreen_configure 保存。\\n- 常用命令：launch <应用名>；act \"tap 搜索; type 耳机; key enter until text:综合\"；act（看一眼）；help act；done（这一轮做完）。\\n- 每次返回四段：做了什么 / 这段时间 / 现在 / 注意，附截图；画面的意思由你看图判断。\\n- 支付、转账、发送、删除、登录、隐私内容，先问用户。用户在悬浮窗上按了停止（USER_STOPPED）就停下，把解除方法转告用户。",
                "en": "Agent-VScreen advice:\\n- When the user asks you to do something on the phone (open an app, look something up, compare prices, check in, read a page), do it with vscreen on the background screen; don't touch the user's screen, and don't use adb, screencap or input instead.\\n- First call vscreen with the command status; if it says the token is missing, ask the user to copy it in Agent-VScreen › Connect an agent › MCP, then save it with vscreen_configure.\\n- Common commands: launch <app>; act \"tap Search; type headphones; key enter until text:All\"; act (take a look); help act; done (this turn is finished).\\n- Every result has four blocks: Done / Meanwhile / Now / Notes, with screenshots; you judge what the screen means by looking.\\n- Ask the user before paying, transferring, sending, deleting, logging in or touching private content. If the user pressed Stop on the floating window (USER_STOPPED), stop and pass on how to undo it."
            },
            "parameters": [],
            "advice": true
        },
        {
            "name": "vscreen",
            "description": {
                "zh": "在副屏上操作手机应用。command 写 vscreen 命令行后面的部分，例如 launch 设置、act \"tap 搜索; type 耳机; key enter\"、act、status、help all。返回文字和截图。",
                "en": "Operate phone apps on the background screen. command is what follows vscreen on the command line, e.g. launch Settings, act \"tap Search; type headphones; key enter\", act, status, help all. Returns text and screenshots."
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
    function noToken() {
        return t("还没有 Agent-VScreen 的令牌：请用户打开 Agent-VScreen，在“接入 agent › MCP”里复制令牌发给你，然后调用 vscreen_configure 保存（或在 Operit 的环境变量里填 VSCREEN_TOKEN）。还没装 Agent-VScreen 的话，先装好并按 App 首页完成配置。",
            "No Agent-VScreen token yet: ask the user to open Agent-VScreen, copy the token under \"Connect an agent › MCP\" and send it to you, then save it with vscreen_configure (or set VSCREEN_TOKEN in Operit's environment variables). If Agent-VScreen is not installed, install it and finish the setup on its home page first.");
    }
    function unreachable(cfg, why) {
        return t("连不上 Agent-VScreen（" + cfg.url + "，" + why + "）：请用户打开 Agent-VScreen，确认首页“服务”在运行；手机重启后要先启动 Shizuku 再打开它。Agent-VScreen 在别的设备上时，用 vscreen_configure 填它的 Tailscale 地址。",
            "Cannot reach Agent-VScreen (" + cfg.url + ", " + why + "): ask the user to open Agent-VScreen and check that the service is running on its home page; after a phone restart, start Shizuku first, then open it. If Agent-VScreen runs on another device, set its Tailscale address with vscreen_configure.");
    }

    async function post(cfg, path, body, seconds) {
        return await Tools.Net.http({
            url: cfg.url + path,
            method: "POST",
            headers: { "Accept": "application/json", "Content-Type": "application/json; charset=utf-8", "Authorization": "Bearer " + cfg.token },
            body: JSON.stringify(body),
            connect_timeout: 10,
            read_timeout: seconds,
            validateStatus: false
        });
    }

    async function image(cfg, url) {
        const r = await Tools.Net.http({
            url: url,
            method: "GET",
            headers: { "Authorization": "Bearer " + cfg.token },
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
            res = await post(cfg, "/v1/run", { cmd: command, session: session }, 330);
        } catch (e) {
            return { ok: false, text: unreachable(cfg, e && e.message ? e.message : String(e)) };
        }
        if (res.statusCode === 401) return { ok: false, text: t("令牌不对：请用户在 Agent-VScreen 的“接入 agent › MCP”里重新复制令牌，再调用 vscreen_configure 保存。", "Wrong token: ask the user to copy the token again under \"Connect an agent › MCP\" in Agent-VScreen, then save it with vscreen_configure.") };
        let data;
        try { data = JSON.parse(String(res.content || "")); } catch (e) {
            return { ok: false, text: unreachable(cfg, "HTTP " + res.statusCode) };
        }
        let text = String(data.text || data.error || "");
        const images = Array.isArray(data.images) ? data.images.slice(0, MAX_IMAGES) : [];
        const links = [];
        for (let i = 0; i < images.length; i += 1) {
            const img = images[i] || {};
            let link = "";
            try { link = await image(cfg, String(img.url || "")); } catch (e) { link = ""; }
            const label = t("[图" + (i + 1) + "]", "[picture " + (i + 1) + "]");
            if (img.url) text = text.split(String(img.url)).join(link ? label : String(img.url));
            if (link) links.push(label + " " + link);
        }
        if (links.length) text += "\n\n" + links.join("\n");
        return { ok: data.ok !== false, code: data.code || null, text: text };
    }

    async function vscreen(params) {
        const command = String((params && params.command) || "").trim();
        if (!command) return t("command 不能为空，例如 launch 设置、act、status、help all", "command is empty; e.g. launch Settings, act, status, help all");
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
        return t("已保存，连接正常（" + cfg.url + "）。现在可以用 vscreen 了，例如 launch 设置。", "Saved and connected (" + cfg.url + "). vscreen is ready, e.g. launch Settings.");
    }

    return { vscreen: vscreen, vscreen_configure: vscreen_configure };
})();

exports.vscreen = agentVScreen.vscreen;
exports.vscreen_configure = agentVScreen.vscreen_configure;
