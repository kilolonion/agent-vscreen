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
#   VSCREEN_NO_UPDATE  set = this script never replaces itself when the app is updated

CLI_VERSION="@@VERSION@@"
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

# The app is local (or on the tailnet): never route it through an http(s)_proxy set for the internet.
c() { curl --noproxy '*' "$@"; }

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

# req METHOD TARGET BODYFILE HEADERFILE OUTFILE [curl args…]: one request, signed when possible. Returns curl's status;
# 90 when the reply's proof is wrong.
req() {
  m="$1"; t="$2"; bf="$3"; hf="$4"; of="$5"; shift 5
  if [ -n "$NOSIGN" ]; then
    if [ "$m" = "GET" ]; then c -sS -D "$hf" -o "$of" -H "Authorization: Bearer $TOKEN" "$@" "$URL$t"
    else c -sS -X "$m" -D "$hf" -o "$of" -H "Authorization: Bearer $TOKEN" --data-binary "@$bf" "$@" "$URL$t"; fi
    return $?
  fi
  ts=$(date +%s); nonce=$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')
  bh=$(sha < "$bf")
  sig=$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s' "$K" "$ts" "$nonce" "$m" "$t" "$bh" "$K" | sha)
  if [ "$m" = "GET" ]; then
    c -sS -D "$hf" -o "$of" -H "X-VScreen-Key: $KID" -H "X-VScreen-Time: $ts" -H "X-VScreen-Nonce: $nonce" -H "X-VScreen-Sign: $sig" "$@" "$URL$t"
  else
    c -sS -X "$m" -D "$hf" -o "$of" -H "X-VScreen-Key: $KID" -H "X-VScreen-Time: $ts" -H "X-VScreen-Nonce: $nonce" -H "X-VScreen-Sign: $sig" --data-binary "@$bf" "$@" "$URL$t"
  fi
  rq=$?; [ $rq -ne 0 ] && return $rq
  got=$(tr -d '\r' < "$hf" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Pp]roof: *//p' | head -n1)
  st=$(sed -n '1s/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p' "$hf")
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
  c -fsS "$URL/cli?via=install" -o "$dest/vscreen" || { echo "vscreen: «下载失败：Agent-VScreen 在运行吗？|download failed: is Agent-VScreen running?»" >&2; exit 3; }
  chmod +x "$dest/vscreen"
  mkdir -p "$CONF_DIR"
  printf 'VSCREEN_URL=%s\nVSCREEN_TOKEN=%s\n' "$URL" "$tok" > "$CONF_DIR/env"
  chmod 600 "$CONF_DIR/env" 2>/dev/null
  echo "«已安装：$dest/vscreen（令牌存在 $CONF_DIR/env）|Installed: $dest/vscreen (token saved in $CONF_DIR/env)»"
  skill_sync show
  case ":$PATH:" in *":$dest:"*) ;; *) echo "«注意：$dest 不在 PATH 里，用全路径调用或加进 PATH|Note: $dest is not on PATH; call it by full path or add it to PATH»" ;; esac
  "$dest/vscreen" status >/dev/null 2>&1 && echo "«连接正常：vscreen help 看用法|Connected: vscreen help shows usage»" || echo "«已安装，但现在连不上：确认 App 里服务已启动|Installed, but it cannot connect now: check that the service is on in the app»"
  exit 0
fi

SESSION=$(printf '%s' "$SESSION" | sed 's/[^A-Za-z0-9_.:-]/_/g')
tmp="${TMPDIR:-/tmp}/vscreen.$$"
mkdir -p "$tmp" || exit 3
trap 'rm -rf "$tmp"' EXIT INT TERM
: > "$tmp/empty"

if [ "$1" = "events" ] && [ "$2" = "--raw" ]; then
  req GET "/v2/events?session=$SESSION&wait=${3:-0}" "$tmp/empty" "$tmp/h" "$tmp/b"; rc=$?; cat "$tmp/b" 2>/dev/null; echo; exit $rc
fi

# The arguments, one per line, hex-encoded: any text (quotes, newlines, any language) arrives exactly as given.
: > "$tmp/body"
for a in "$@"; do
  if [ -z "$a" ]; then echo 00 >> "$tmp/body"; else printf '%s' "$a" | hex >> "$tmp/body"; echo >> "$tmp/body"; fi
done
# A frozen app still accepts the connection (the kernel does) but never answers: find out in 5 s, not minutes.
c -s -m 5 -o /dev/null "$URL/v2/ping"
if [ $? -eq 28 ]; then
  echo "vscreen: «Agent-VScreen 5 秒没有响应，多半是系统把它冻结在后台了。请用户打开一次 Agent-VScreen，按首页“后台运行受限”的提示允许它后台活动（荣耀/华为：应用启动管理 › 手动管理）。|Agent-VScreen did not answer for 5 s; the system most likely froze it in the background. Ask the user to open Agent-VScreen once and allow background activity as \"Background running restricted\" on its home page says (Honor/Huawei: App launch › Manage manually).»" >&2
  exit 3
fi
# One request id for this command: if the connection breaks mid-way, the retry gets the result of the first run
# instead of running the command a second time.
rid="c$$-$(date +%s)-$(od -An -N4 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
target="/v2/run?format=text&session=$SESSION&request_id=$rid"
req POST "$target" "$tmp/body" "$tmp/h" "$tmp/b" --connect-timeout 5 -H "Content-Type: application/x-vscreen-hex" -H "Accept: text/plain"
rc=$?
case $rc in 18|52|56)
  sleep 1
  req POST "$target" "$tmp/body" "$tmp/h" "$tmp/b" --connect-timeout 5 -H "Content-Type: application/x-vscreen-hex" -H "Accept: text/plain"; rc=$? ;;
esac
if [ $rc -eq 90 ]; then
  echo "vscreen: «回复的校验不对：回复不是这台手机上的 Agent-VScreen 发出的，或在路上被改过。结果没有采用。|the reply failed verification: it did not come from Agent-VScreen on this phone, or it was changed on the way. The result was discarded.»" >&2
  exit 3
fi
if [ $rc -ne 0 ]; then
  echo "vscreen: «连不上 Agent-VScreen（$URL，curl $rc）：打开 Agent-VScreen，确认“服务”已启动；手机重启后要先启动 Shizuku 再打开它。|cannot reach Agent-VScreen ($URL, curl $rc): open Agent-VScreen and check the service is on; after a phone restart, start Shizuku first, then open it.»" >&2
  exit 3
fi
code=$(sed -n '1s/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p' "$tmp/h")
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
  elif [ -w "$self" ] && [ -w "$(dirname "$self")" ] && req GET "/v2/cli" "$tmp/empty" "$tmp/h2" "$tmp/new" && grep -q "^CLI_VERSION=\"$srv\"" "$tmp/new"; then
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
    if req GET "/v2/shot/$n" "$tmp/empty" "$tmp/h3" "$SHOT_DIR/$n"; then
      sed "s|http[s]*://[^ ]*/v2/shot/$n|$SHOT_DIR/$n|g" "$tmp/b" > "$tmp/b2" && mv "$tmp/b2" "$tmp/b"
    fi
    IFS=,
  done
  IFS=$old_ifs
fi
mkdir -p "$SHOT_DIR" 2>/dev/null && cp "$tmp/b" "$SHOT_DIR/last.txt" 2>/dev/null
cat "$tmp/b"
exit "${exitc:-0}"
