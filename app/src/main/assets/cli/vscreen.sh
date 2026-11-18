#!/bin/sh
# vscreen — command-line client for Agent-VScreen @@VERSION@@ (talks to the app on 127.0.0.1; needs curl).
#
#   vscreen launch Settings
#   vscreen act "tap Search; type headphones; key enter until text:All"
#   vscreen help all
#
# Requests are signed (the token itself is never sent; replies are checked) when sha256sum, shasum or openssl exists.
# Pictures the command made are downloaded to $VSCREEN_SHOT_DIR and their paths printed in the text.
# Config: ~/.config/agent-vscreen/env (written by `install`, same names), overridden by environment variables:
#   VSCREEN_URL      default http://127.0.0.1:@@PORT@@
#   VSCREEN_TOKEN    the access token shown in the app
#   VSCREEN_SESSION  who owns the screen (default "default"; one session at a time)
#   VSCREEN_SHOT_DIR where pictures go (default $TMPDIR/vscreen-shots or /tmp/vscreen-shots)
#   VSCREEN_SHOTS    "none" = do not download pictures (the text keeps their URLs)
#   VSCREEN_SHOT_KEEP how many downloaded pictures to keep (default 40; all are removed after done / release)
#   VSCREEN_NO_UPDATE  set = this script never replaces itself when the app is updated
#   VSCREEN_PROXY    a proxy to reach the phone through (e.g. socks5h://127.0.0.1:1055 for a userspace Tailscale);
#                    without it a remote address follows the usual http_proxy / no_proxy variables

CLI_VERSION="@@VERSION@@"
umask 077
CONF_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/agent-vscreen"
# The file holds the same names as the environment (VSCREEN_URL / VSCREEN_TOKEN); a variable already set wins.
# It is read line by line, not sourced, so it can never override what the caller set. Old *_SAVED names still work.
conf() { [ -f "$CONF_DIR/env" ] && sed -n -e "s/^$1=//p" -e "s/^$1_SAVED=//p" "$CONF_DIR/env" | head -n1; }
URL="${VSCREEN_URL:-$(conf VSCREEN_URL)}"; URL="${URL:-http://127.0.0.1:@@PORT@@}"
TOKEN="${VSCREEN_TOKEN:-$(conf VSCREEN_TOKEN)}"
SESSION="${VSCREEN_SESSION:-default}"
SHOT_DIR="${VSCREEN_SHOT_DIR:-${TMPDIR:-/tmp}/vscreen-shots}"

if ! command -v curl >/dev/null 2>&1; then
  echo "vscreen: «需要 curl（Termux: pkg install curl；Alpine: apk add curl；Debian: apt install curl）|needs curl (Termux: pkg install curl; Alpine: apk add curl; Debian: apt install curl)»" >&2; exit 3
fi

# 1.3: only the phone itself (127.0.0.1 / localhost) skips proxies. A remote phone is reached the way this machine
# reaches anything (http_proxy / no_proxy), or through VSCREEN_PROXY; before, every proxy was ignored and callers that
# can only get out through one (a cloud container, a company network) could not connect.
PROXY="${VSCREEN_PROXY:-$(conf VSCREEN_PROXY)}"
case "$URL" in
  http://127.0.0.1:*|http://localhost:*|http://\[::1\]:*) c() { curl --noproxy '*' "$@"; } ;;
  # 1.3 beta8: VSCREEN_PROXY wins over no_proxy (a container's no_proxy often lists 100.64.0.0/10, which made curl
  # skip the proxy and fail at once).
  *) if [ -n "$PROXY" ]; then c() { curl --noproxy '' --proxy "$PROXY" "$@"; }; else c() { curl "$@"; }; fi ;;
esac

# ── signing ──────────────────────────────────────────────────────────────────────────────────────────────────────
# K = sha256hex(token secret). A request carries the token's id, the time, a random nonce and
# sha256hex(K \n time \n nonce \n METHOD \n target \n sha256hex(body) \n K): the app can check who sent it and that
# nothing was changed, and the token never travels. The reply carries X-VScreen-Proof, checked the same way.
if command -v sha256sum >/dev/null 2>&1; then sha() { sha256sum | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then sha() { shasum -a 256 | cut -d' ' -f1; }
elif command -v openssl >/dev/null 2>&1; then sha() { openssl dgst -sha256 | sed 's/^.*= *//'; }
else sha() { return 1; }; NOSIGN=1; fi
case "$TOKEN" in
  avs_*_*) KID=$(printf '%s' "$TOKEN" | cut -d_ -f2); SECRET=$(printf '%s' "$TOKEN" | cut -d_ -f3-) ;;
  *) KID=main; SECRET="$TOKEN" ;;
esac
[ -z "$NOSIGN" ] && K=$(printf '%s' "$SECRET" | sha)
hex() { od -An -v -tx1 | tr -d ' \n'; }

# 1.4.0-beta15: on this phone the service takes the first free port of the ten from the set one (another app, or a
# copy of the app in a parallel space, may hold the set port). Find it: the instance that holds THIS token answers
# /v2/ping?me=<id>&n=<nonce> with sha256hex(K \n nonce \n ping \n K); nobody else can. A port that times out (a frozen
# holder) ends the search: the wake-up below handles a frozen app.
find_port() {
  [ -n "$NOSIGN" ] || [ -z "$TOKEN" ] && return 0
  case "$URL" in http://127.0.0.1:*|http://localhost:*) ;; *) return 0 ;; esac
  _b=${URL%:*}; _p0=${URL##*:}; _p0=${_p0%%/*}
  case "$_p0" in ''|*[!0-9]*) return 0 ;; esac
  _n=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')
  _w=$(printf '%s\n%s\nping\n%s' "$K" "$_n" "$K" | sha)
  _i=0
  while [ $_i -lt 10 ]; do
    _p=$((_p0 + _i)); _i=$((_i + 1))
    _r=$(c -s -m 2 "$_b:$_p/v2/ping?me=$KID&n=$_n" 2>/dev/null); _rc=$?
    case "$_r" in *"\"you\":\"$_w\""*) [ $_p -ne $_p0 ] && URL="$_b:$_p"; return 0 ;; esac
    [ $_rc -eq 28 ] && [ $_i -eq 1 ] && return 0
  done
  return 0
}
find_port

# curl may receive an interim response or a proxy CONNECT response before the app's.
http_status() { awk '/^HTTP\/[0-9.]+ / { code=$2 } END { sub(/\r$/, "", code); print code }' "$1"; }

# req METHOD TARGET BODYFILE HEADERFILE OUTFILE [curl args…]: one request, signed when possible. Returns curl's status;
# 90 when the reply's proof is wrong.
req() {
  m="$1"; t="$2"; bf="$3"; hf="$4"; of="$5"; shift 5
  if [ -n "$NOSIGN" ]; then
    if [ "$m" = "GET" ]; then c -sS -H "X-VScreen-Client: cli" -D "$hf" -o "$of" -H "Authorization: Bearer $TOKEN" "$@" "$URL$t"
    else c -sS -H "X-VScreen-Client: cli" -X "$m" -D "$hf" -o "$of" -H "Authorization: Bearer $TOKEN" --data-binary "@$bf" "$@" "$URL$t"; fi
    return $?
  fi
  ts=$(date +%s); nonce=$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')
  bh=$(sha < "$bf")
  sig=$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s' "$K" "$ts" "$nonce" "$m" "$t" "$bh" "$K" | sha)
  if [ "$m" = "GET" ]; then
    c -sS -H "X-VScreen-Client: cli" -D "$hf" -o "$of" -H "X-VScreen-Key: $KID" -H "X-VScreen-Time: $ts" -H "X-VScreen-Nonce: $nonce" -H "X-VScreen-Sign: $sig" "$@" "$URL$t"
  else
    c -sS -H "X-VScreen-Client: cli" -X "$m" -D "$hf" -o "$of" -H "X-VScreen-Key: $KID" -H "X-VScreen-Time: $ts" -H "X-VScreen-Nonce: $nonce" -H "X-VScreen-Sign: $sig" --data-binary "@$bf" "$@" "$URL$t"
  fi
  rq=$?; [ $rq -ne 0 ] && return $rq
  got=$(tr -d '\r' < "$hf" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Pp]roof: *//p' | head -n1)
  st=$(http_status "$hf")
  # Refusals made before the token was checked (401/403/429…) carry no proof; a 200 to a signed request always does.
  if [ "$st" = "200" ]; then
    want=$(printf '%s\n%s\nresp\n%s\n%s' "$K" "$nonce" "$(sha < "$of")" "$K" | sha)
    [ "$got" = "$want" ] || return 90
  fi
  return 0
}

# The skill: how an agent should use vscreen (and not build its own virtual screen or reach for adb). Nothing is
# written into any agent's own folders: install prints it for the agent to save as its own skill / memory, and keeps
# a copy in $CONF_DIR/SKILL.md (refreshed when the app is updated).
skill_sync() {
  mkdir -p "$CONF_DIR" && c -fsS "$URL/skill" -o "$CONF_DIR/SKILL.md.new" || return 0
  mv -f "$CONF_DIR/SKILL.md.new" "$CONF_DIR/SKILL.md"
  if [ "$1" = "show" ]; then
    # 1.1.6: short. The request to remember comes from the user's own message (copied from the app), not from here.
    echo
    echo "«要记住的说明（存进你的长期记忆，存好后执行 vscreen remembered \"<存在哪里>\"）：|Note to remember (save it in your long-term memory, then run vscreen remembered \"<where you saved it>\"):»"
    c -fsS "$URL/memory" 2>/dev/null || echo "«（取不到，见 $CONF_DIR/SKILL.md）|(could not fetch it; see $CONF_DIR/SKILL.md)»"
    echo
    echo "«完整的 skill 版本在 $CONF_DIR/SKILL.md；用法速查：vscreen guide|The full skill version is in $CONF_DIR/SKILL.md; quick usage: vscreen guide»"
  else
    echo "«（skill 已随 App 更新：$CONF_DIR/SKILL.md，如果你存过旧版，请换成新的）|(the skill was updated with the app: $CONF_DIR/SKILL.md; if you saved an old copy, replace it)»" >&2
  fi
}

if [ "$1" = "install" ]; then
  tok="$2"; dest="$3"
  [ -n "$tok" ] || { echo "«用法: vscreen install <令牌> [安装目录]|usage: vscreen install <token> [install dir]»" >&2; exit 2; }
  if [ -z "$dest" ]; then
    for d in "$PREFIX/bin" "$HOME/.local/bin" /usr/local/bin; do
      [ -n "$d" ] && [ "$d" != "/bin" ] || continue
      mkdir -p "$d" 2>/dev/null && [ -w "$d" ] && { dest="$d"; break; }
    done
  fi
  [ -n "$dest" ] || { echo "vscreen: «找不到可写的安装目录，请指定：vscreen install <令牌> <目录>|no writable install dir found, give one: vscreen install <token> <dir>»" >&2; exit 2; }
  # 1.4.0-beta7: which agent installed (the token's id, never its secret), so each agent has its own record.
  tid=$(printf '%s' "$tok" | sed -n 's/^avs_\([a-z0-9]*\)_.*/\1/p')
  c -fsS "$URL/cli?via=install&id=$tid" -o "$dest/vscreen" || { echo "vscreen: «下载失败：Agent-VScreen 在运行吗？|download failed: is Agent-VScreen running?»" >&2; exit 3; }
  chmod +x "$dest/vscreen"
  mkdir -p "$CONF_DIR"
  printf 'VSCREEN_URL=%s\nVSCREEN_TOKEN=%s\n' "$URL" "$tok" > "$CONF_DIR/env"
  [ -n "$PROXY" ] && printf 'VSCREEN_PROXY=%s\n' "$PROXY" >> "$CONF_DIR/env"
  chmod 600 "$CONF_DIR/env" 2>/dev/null
  echo "«已安装：$dest/vscreen（令牌存在 $CONF_DIR/env）|Installed: $dest/vscreen (token saved in $CONF_DIR/env)»"
  skill_sync show
  case ":$PATH:" in *":$dest:"*) ;; *) echo "«注意：$dest 不在 PATH 里，用全路径调用或加进 PATH|Note: $dest is not on PATH; call it by full path or add it to PATH»" ;; esac
  "$dest/vscreen" status >/dev/null 2>&1 && echo "«连接正常：vscreen help 看用法|Connected: vscreen help shows usage»" || echo "«已安装，但现在连不上：确认 App 里服务已启动|Installed, but it cannot connect now: check that the service is on in the app»"
  exit 0
fi

SESSION=$(printf '%s' "$SESSION" | sed 's/[^A-Za-z0-9_.:-]/_/g')
tmp=$(mktemp -d "${TMPDIR:-/tmp}/vscreen.XXXXXX") || exit 3
trap 'rm -rf "$tmp"' EXIT INT TERM
: > "$tmp/empty"

if [ "$1" = "events" ] && [ "$2" = "--raw" ]; then
  req GET "/v2/events?session=$SESSION&wait=${3:-0}" "$tmp/empty" "$tmp/h" "$tmp/b"; rc=$?
  if [ "$rc" -eq 0 ] && [ "$(http_status "$tmp/h")" != 200 ]; then rc=1; fi
  cat "$tmp/b" 2>/dev/null; echo; exit $rc
fi

# The arguments, one per line, hex-encoded: any text (quotes, newlines, any language) arrives exactly as given.
: > "$tmp/body"
for a in "$@"; do
  if [ -z "$a" ]; then echo 00 >> "$tmp/body"; else printf '%s' "$a" | hex >> "$tmp/body"; echo >> "$tmp/body"; fi
done
# A frozen app still accepts the connection (the kernel does) but never answers: find out in 5 s, not minutes.
# 1.3: on this phone, wake it first (an explicit broadcast thaws the process and starts the service), then try again.
# 1.3 beta2: whether the connection itself was made is kept (time_connect): no connection = the phone is not
# reachable (Tailscale node offline, network), which is not the same as an app that accepted it and stays silent.
alive() { ct=$(c -s -m 5 -o "$tmp/ping" -w '%{time_connect}' "$URL/v2/ping"); }
pidof_app() { sed -n 's/.*"pid":\([0-9]*\).*/\1/p' "$1" 2>/dev/null | head -n1; }
# 1.3 beta7: a command whose app dies on the way never gets an answer (the connection to a proxy or a userspace
# Tailscale stays open). While it runs, look every 20 s: the app answering with another process id (it restarted), or
# not answering three times in a row, ends the wait with a clear message instead of hanging for minutes.
# 1.3.0: it runs with its output on /dev/null: a caller reading our output through a pipe waits until every holder
# of that pipe is gone, and a sleeping watcher held it for up to 20 s after the command had finished.
watch_post() {
  wp0=$(pidof_app "$tmp/ping"); miss=0
  while kill -0 "$1" 2>/dev/null; do
    # 1.3.0: one-second steps, so it ends within a second of the command (a lone `sleep 20` outlived it).
    i=0; while [ $i -lt 20 ] && kill -0 "$1" 2>/dev/null; do sleep 1; i=$((i + 1)); done
    kill -0 "$1" 2>/dev/null || break
    if c -s -m 8 -o "$tmp/ping2" "$URL/v2/ping" 2>/dev/null; then
      miss=0; wp=$(pidof_app "$tmp/ping2")
      if [ -n "$wp0" ] && [ -n "$wp" ] && [ "$wp" != "$wp0" ]; then echo restarted > "$tmp/lost"; break; fi
    else
      miss=$((miss + 1)); [ $miss -ge 3 ] && { echo silent > "$tmp/lost"; break; }
    fi
  done
  if [ -s "$tmp/lost" ]; then command -v pkill >/dev/null 2>&1 && pkill -P "$1" 2>/dev/null; kill "$1" 2>/dev/null; fi
}
# One request id for this command: if the connection breaks mid-way, the retry gets the result of the first run
# instead of running the command a second time.
rid="c$$-$(date +%s)-$(od -An -N4 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
target="/v2/run?format=text&session=$SESSION&request_id=$rid"
# 1.3 beta4: why a remote phone does not answer, as precisely as this machine can tell. With the tailscale command
# here, a Tailscale ping separates "the phone's node is offline" (service off or paused, phone offline, login needed)
# from "the node is up but the app does not answer". Either way the user gets the one place to look.
remote_probe() {
  _rh=$(printf '%s' "$URL" | sed 's#^[a-z]*://##; s#[:/].*##'); _rv=
  if command -v tailscale >/dev/null 2>&1; then
    # Only a ping that went out and got nothing back says "offline"; a tailscale command that cannot reach its own
    # daemon (userspace or another socket) knows nothing about the phone.
    _ro=$(tailscale ping -c 1 --timeout 3s "$_rh" 2>&1); case "$_ro" in *pong*) _rv=up ;; *"timed out"*|*"no reply"*|*offline*) _rv=down ;; esac
  fi
}
remote_why() {
  if [ "$1" = en ]; then
    case $_rv in
      up) echo "The phone is online in Tailscale, but Agent-VScreen does not answer: the app may be frozen or restarting. Ask the user to open Agent-VScreen." ;;
      down) echo "The phone's Tailscale node is offline. Usually the user's choice, not a fault: the service is On demand (devices elsewhere cannot wake it; it is online during a booked window or after an agent on the phone woke it) or Off. Next: the phone is offline or the built-in Tailscale needs logging in again. Ask the user to open Agent-VScreen and read the state at the top of its home page; for access at any time, choose Always on with the power button." ;;
      *) echo "The phone did not answer. Most likely the user's choice, not a fault: the service is On demand (devices elsewhere cannot wake it; it is online during a booked window) or Off. Next: the phone is offline, its built-in Tailscale logged out, or less often the system froze the app. Ask the user to open Agent-VScreen and read the state at the top of its home page; for access at any time, choose Always on with the power button." ;;
    esac
  else
    case $_rv in
      up) echo "手机在 Tailscale 上在线，但 Agent-VScreen 没有回应：可能被系统冻结或正在重启。请用户打开一次 Agent-VScreen。" ;;
      down) echo "手机的 Tailscale 节点不在线。通常是用户的选择而不是故障：服务是“按需”（其它设备叫不醒它，只在预约时间里或手机上的 agent 叫醒后在线）或“关闭”。其次是手机没网，或内置 Tailscale 要重新登录。请用户打开 Agent-VScreen 看首页顶上的状态；要随时连得上，在右上角电源键里选“常驻”。" ;;
      *) echo "手机没有回应。多半是用户的选择而不是故障：服务是“按需”（其它设备叫不醒它，只在预约时间里在线）或“关闭”。其次是手机没网、内置 Tailscale 掉了登录，少数情况是系统把 App 冻结了。请用户打开 Agent-VScreen 看首页顶上的状态；要随时连得上，在右上角电源键里选“常驻”。" ;;
    esac
  fi
}
post() { req POST "$target" "$tmp/body" "$tmp/${1:-h}" "$tmp/${2:-b}" --connect-timeout 5 -H "Content-Type: application/x-vscreen-hex" -H "Accept: text/plain"; }
# 1.3: the command and the liveness check go out together (before, the check came first and cost a round trip on
# every command). If the check says the app is frozen, the command is abandoned, the app woken, and the command sent
# again with the same request id (it runs once).
post & ppid=$!
alive; pr=$?
# 1.4.0-beta4: on this phone a refused connection (curl 7) means the app is not running at all (never opened since a
# restart, or cleared by the system). That is woken the same way as a frozen one; before, it went straight to
# "cannot reach", and agents told the user to repair something that was only switched off.
case "$URL" in http://127.0.0.1:*|http://localhost:*) [ $pr -eq 7 ] && { refused=1; pr=28; } ;; esac
if [ $pr -ne 28 ]; then watch_post $ppid </dev/null >/dev/null 2>&1 & wpid=$!; wait $ppid; rc=$?; kill $wpid 2>/dev/null; wait $wpid 2>/dev/null; sent=1
  if [ -s "$tmp/lost" ]; then
    case "$(cat "$tmp/lost")" in
      restarted) echo "vscreen: APP_RESTARTED «这条命令执行中，手机上的 Agent-VScreen 重启了（可能被系统杀掉后又拉起），回复丢了。命令可能做了一部分：先 vscreen act 看一眼现在的画面再决定。|Agent-VScreen on the phone restarted while this command ran (likely killed by the system and started again); the reply was lost. The command may be partly done: look with vscreen act before going on.»" >&2 ;;
      *) remote_probe 2>/dev/null; echo "vscreen: NOT_READY «这条命令执行中，手机连续 1 分钟没有回应，不再等了。命令可能做了一部分。$(remote_why)|The phone stopped answering for a minute while this command ran; stopped waiting. The command may be partly done. $(remote_why en)»" >&2 ;;
    esac
    exit 3
  fi
else command -v pkill >/dev/null 2>&1 && pkill -P $ppid 2>/dev/null; kill $ppid 2>/dev/null; wait $ppid 2>/dev/null; sent=; fi
if [ $pr -eq 28 ]; then
  case "$URL" in
    http://127.0.0.1:*|http://localhost:*)
      if command -v am >/dev/null 2>&1; then
        _wb=$(am broadcast -n com.kilon.agentvscreen/.host.WakeReceiver -a com.kilon.agentvscreen.WAKE 2>&1)
        case "$_wb" in *'data="blocked"'*)
          echo "vscreen: NOT_READY «用户关闭了 Agent-VScreen，叫不醒它。这是用户的选择，不是故障，不用修：请用户在 App 右上角的电源键里选“常驻”或“按需”。|The user turned Agent-VScreen off, so it cannot be woken. That is their choice, not a fault, and needs no repair: ask them to choose Always on or On demand with the power button at the top of the app.»" >&2
          exit 3 ;;
        esac
        for i in 1 2 3; do sleep 2; alive && { pr=0; break; }; done
        [ $pr -eq 0 ] && echo "«（Agent-VScreen 刚才没有响应，已唤醒）|(Agent-VScreen was not answering; it was woken up)»" >&2
      fi ;;
  esac
fi
# 1.3 beta5: a phone elsewhere that does not answer is never called "frozen" from here. Through a proxy (a userspace
# Tailscale, a SOCKS / HTTP proxy) the connection is made to the proxy, so a made connection says nothing about the
# phone; the likely causes are the remote ones (hidden, blocked, offline, logged out), with a ping to narrow them.
if [ $pr -eq 28 ]; then
  case "$URL" in
    http://127.0.0.1:*|http://localhost:*) ;;
    *) remote_probe; echo "vscreen: NOT_READY «5 秒内连不上手机（$URL）。$(remote_why)|could not reach the phone within 5 s ($URL). $(remote_why en)»" >&2
       exit 3 ;;
  esac
fi
if [ $pr -eq 28 ] && [ -n "$refused" ]; then
  echo "vscreen: NOT_READY «Agent-VScreen 没在运行（用户还没打开它，或系统把它清掉了），叫也没叫起来。这不是故障，不用修：请用户打开一次 Agent-VScreen；手机重启过的话先启动 Shizuku。|Agent-VScreen is not running (the user has not opened it, or the system cleared it) and could not be started. This is not a fault and needs no repair: ask the user to open Agent-VScreen once; after a phone restart, start Shizuku first.»" >&2
  exit 3
fi
if [ $pr -eq 28 ]; then
  echo "vscreen: NOT_READY «Agent-VScreen 5 秒没有响应，唤醒也没成功，多半是系统把它冻结在后台了。请用户打开一次 Agent-VScreen，按首页“后台运行受限”的提示允许它后台活动（荣耀/华为：应用启动管理 › 手动管理）。定时任务请先 vscreen schedule 预约时间。|Agent-VScreen did not answer for 5 s and could not be woken; the system most likely froze it in the background. Ask the user to open Agent-VScreen once and allow background activity as \"Background running restricted\" on its home page says (Honor/Huawei: App launch › Manage manually). For timed tasks, book the time with vscreen schedule first.»" >&2
  exit 3
fi
# (the abandoned first try may still be writing its files: the second one uses its own)
[ -z "$sent" ] && { post h1 b1; rc=$?; mv -f "$tmp/h1" "$tmp/h" 2>/dev/null; mv -f "$tmp/b1" "$tmp/b" 2>/dev/null; }
case $rc in 18|52|56)
  sleep 1
  post; rc=$? ;;
esac
if [ $rc -eq 90 ]; then
  echo "vscreen: «回复的校验不对：回复不是这台手机上的 Agent-VScreen 发出的，或在路上被改过。结果没有采用。|the reply failed verification: it did not come from Agent-VScreen on this phone, or it was changed on the way. The result was discarded.»" >&2
  exit 3
fi
if [ $rc -ne 0 ]; then
  case "$URL" in
    http://127.0.0.1:*|http://localhost:*) echo "vscreen: NOT_READY «Agent-VScreen 没在运行（$URL，curl $rc）。这不是故障，不用修：请用户打开一次 Agent-VScreen；手机重启过的话先启动 Shizuku。|Agent-VScreen is not running ($URL, curl $rc). This is not a fault and needs no repair: ask the user to open Agent-VScreen once; after a phone restart, start Shizuku first.»" >&2 ;;
    *) if [ -n "$PROXY" ]; then case $rc in 5|7|97)
         echo "vscreen: «连不上本机的代理 $PROXY（curl $rc），请求没有发出去：检查代理是否在运行（例如 userspace tailscaled 的 SOCKS5 端口）。|cannot reach the local proxy $PROXY (curl $rc); the request never left this machine: check the proxy is running (e.g. the SOCKS5 port of a userspace tailscaled).»" >&2; exit 3 ;; esac; fi
       remote_probe; echo "vscreen: «连不上手机上的 Agent-VScreen（$URL，curl $rc）。$(remote_why)|cannot reach Agent-VScreen on the phone ($URL, curl $rc). $(remote_why en)»" >&2 ;;
  esac
  exit 3
fi
code=$(http_status "$tmp/h")
if [ "$code" = "404" ]; then
  echo "vscreen: «这台手机上的 Agent-VScreen 太旧（没有 /v2）：请用户更新 App。|Agent-VScreen on this phone is too old (no /v2): ask the user to update the app.»" >&2; exit 3
fi
if [ "$code" != "200" ]; then
  case "$code" in
    401) echo "vscreen: «令牌没有通过：在 Agent-VScreen 里复制接入的话重新执行一遍（会更新 $CONF_DIR/env 里的 VSCREEN_TOKEN），或设置环境变量 VSCREEN_TOKEN。详情：|the token was not accepted: copy the connect message in Agent-VScreen and run it again (it updates VSCREEN_TOKEN in $CONF_DIR/env), or set the VSCREEN_TOKEN environment variable. Details:»" >&2 ;;
    *) echo "vscreen: «请求被拒绝（HTTP $code）：|the request was refused (HTTP $code):»" >&2 ;;
  esac
  cat "$tmp/b" >&2; echo >&2
  [ -n "$NOSIGN" ] && echo "vscreen: «这台机器没有 sha256sum / shasum / openssl，请求没法签名；从其它设备连接时需要其中一个。|this machine has no sha256sum / shasum / openssl, so requests cannot be signed; one of them is needed to connect from another device.»" >&2
  exit 3
fi
# Version parity: the app serves the matching client; when the app was updated, this script replaces itself once.
# The new copy is fetched signed and its proof checked: an unsigned or altered copy is never installed.
srv=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Vv]ersion: *//p' | head -n1)
if [ -n "$srv" ] && [ "$srv" != "$CLI_VERSION" ] && [ -z "$VSCREEN_NO_UPDATE" ]; then
  self=$(command -v "$0" 2>/dev/null || echo "$0")
  if [ -n "$NOSIGN" ]; then
    echo "«（App 已更新到 $srv，但这台机器不能签名校验，命令行没有自动更新：装 sha256sum / openssl，或重新执行接入命令）|(the app was updated to $srv, but this machine cannot verify signatures, so the CLI did not update itself: install sha256sum / openssl, or run the connect command again)»" >&2
  elif [ -w "$self" ] && [ -w "$(dirname "$self")" ] && req GET "/v2/cli" "$tmp/empty" "$tmp/h2" "$tmp/new" && [ "$(http_status "$tmp/h2")" = 200 ] && grep -q "^CLI_VERSION=\"$srv\"" "$tmp/new"; then
    # A new file moved over the old one: this running copy keeps reading the old file safely.
    cp "$tmp/new" "$self.new.$$" && chmod +x "$self.new.$$" && mv -f "$self.new.$$" "$self" &&
      echo "«（vscreen 命令行已随 App 更新到 $srv）|(the vscreen CLI was updated with the app to $srv)»" >&2
    skill_sync
  fi
fi
exitc=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Ee]xit: *//p' | head -n1)
imgs=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Ii]mages: *//p' | head -n1)
if [ -n "$imgs" ] && [ "$VSCREEN_SHOTS" != "none" ]; then
  mkdir -p "$SHOT_DIR"
  old_ifs=$IFS; IFS=,
  for n in $imgs; do
    IFS=$old_ifs
    case "$n" in vscreen_*.jpg) ;; *) continue ;; esac
    if req GET "/v2/shot/$n" "$tmp/empty" "$tmp/h3" "$tmp/shot" && [ "$(http_status "$tmp/h3")" = 200 ] && [ -s "$tmp/shot" ]; then
      mv -f "$tmp/shot" "$SHOT_DIR/$n" &&
        sed "s|http[s]*://[^ ]*/v2/shot/$n|$SHOT_DIR/$n|g" "$tmp/b" > "$tmp/b2" && mv "$tmp/b2" "$tmp/b"
    fi
    IFS=,
  done
  IFS=$old_ifs
fi
mkdir -p "$SHOT_DIR" 2>/dev/null && cp "$tmp/b" "$SHOT_DIR/last.txt" 2>/dev/null
# Pictures are kept for the recent commands only: at most $VSCREEN_SHOT_KEEP (default 40) files, and none after the
# turn ends (done / release). They used to pile up without limit in the agent's cache.
case "$1" in
  done|release) rm -f "$SHOT_DIR"/vscreen_*.jpg 2>/dev/null ;;
  *) keep=${VSCREEN_SHOT_KEEP:-40}
     # shellcheck disable=SC2012
     ls -1t "$SHOT_DIR"/vscreen_*.jpg 2>/dev/null | tail -n +"$((keep + 1))" | while IFS= read -r f; do rm -f "$f"; done ;;
esac
cat "$tmp/b"
exit "${exitc:-0}"
