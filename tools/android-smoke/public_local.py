#!/usr/bin/env python3
"""Stage 7 (2.0.0-beta7) local PublicServer (127.0.0.1:18785) OAuth 2.1 + MCP emulator smoke test.

Covers plan sections 7.6, 7.7, and 7.8 on an Android emulator without a live Tailscale account:
  1) Missing/wrong X-AVS-Public -> 403; unlisted paths (/v2/ping, /v2/run, /cli, /v2/shot/a.jpg) -> 404
  2) Pre-auth 405 + Allow: POST on GET/DELETE /mcp (both PublicServer and local ApiServer)
  3) Anti-spoofing: X-VScreen-Tailnet + local token on PublicServer -> 401 (never treated as tailnet)
  4) Discovery (Inspector & mcp-remote): unauthenticated POST /mcp -> 401 + WWW-Authenticate;
     PRM at both /.well-known/oauth-protected-resource and /mcp identical; AS metadata root-only
  5) Full OAuth 2.1 flow: POST /oauth/register -> GET /oauth/authorize (PKCE S256 + resource) ->
     pairing HTML & 8-char code -> /oauth/authorize/status pending -> debug hook approve:operate ->
     status approved -> POST /oauth/token -> single-use code replay rejected -> MCP initialize /
     tools/list / vscreen_status
  6) Scope & boundary checks: system command (vscreen_fix) -> SCOPE_DENIED; local ApiServer
     rejects public OAuth access token (401); audit.jsonl records source="public"
  7) 1-hour expiry via clock hook (+3601s) -> 401 + WWW-Authenticate
  8) Refresh token rotation, client_id & RFC 8707 invalid_target checks, and retired refresh reuse
     revoking ALL client tokens (both new access token and new refresh token invalidated)
  9) Full logcat scan verifying 0 occurrences of all access tokens, refresh tokens, auth code,
     and TSB_PUBLIC_SECRET (7.7)

Usage:
  python3 tools/android-smoke/public_local.py test-apks/app-debug.apk
"""
import argparse
import base64
import hashlib
import http.client
import json
import re
import secrets
import subprocess
import sys
import time
import urllib.parse

APP = "com.kilon.agentvscreen"
PUB_LOCAL_PORT = 18785
API_LOCAL_PORT = 18766
FAILED = []


def run(*argv, timeout=90, check_rc=True):
    p = subprocess.run(argv, text=True, timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = (p.stdout or "").strip()
    if check_rc and p.returncode != 0:
        raise subprocess.CalledProcessError(p.returncode, argv, output=out)
    return p.returncode, out


def shell(*args, timeout=90, check_rc=True):
    return run("adb", "shell", *args, timeout=timeout, check_rc=check_rc)


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" :: " + detail if detail else ""), flush=True)
    if not ok:
        FAILED.append(name)
    return ok


def read_app_file(rel_path):
    _, out = shell("run-as", APP, "cat", rel_path, "2>/dev/null", "||", "true", check_rc=False)
    return out


def send_public_hook(cmd):
    shell("run-as", APP, "rm", "-f", "files/ci-public.txt", check_rc=False)
    shell(
        "am", "broadcast",
        "-a", f"{APP}.CI_SEED",
        "-n", f"{APP}/.CiHook",
        "--es", "public", cmd,
    )
    deadline = time.time() + 15
    while time.time() < deadline:
        raw = read_app_file("files/ci-public.txt").strip()
        if raw:
            return json.loads(raw)
        time.sleep(0.2)
    raise RuntimeError(f"public hook {cmd!r} timed out")


def http_req(port, method, path, headers=None, body=None, timeout=20):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        hdrs = dict(headers or {})
        if body is not None and isinstance(body, str):
            body = body.encode("utf-8")
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8", errors="replace")
        hdr_map = {k.lower(): v for k, v in resp.getheaders()}
        return resp.status, hdr_map, raw
    finally:
        conn.close()


def pkce_pair():
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii").rstrip("=")
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).decode("ascii").rstrip("=")
    return verifier, challenge


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", help="Path to app-debug.apk")
    args = ap.parse_args()

    print("Installing debug APK...", flush=True)
    run("adb", "install", "-r", "-g", args.apk, timeout=180)
    # Seed local ApiServer via CI_SEED
    shell("run-as", APP, "rm", "-f", "files/ci.txt", check_rc=False)
    shell("am", "broadcast", "-a", f"{APP}.CI_SEED", "-n", f"{APP}/.CiHook")
    main_token, api_port = "", 0
    deadline = time.time() + 45
    while time.time() < deadline:
        lines = read_app_file("files/ci.txt").strip().splitlines()
        if len(lines) >= 2 and lines[1].isdigit() and int(lines[1]) > 0:
            main_token, api_port = lines[0].strip(), int(lines[1].strip())
            break
        time.sleep(0.5)
    if not main_token or api_port <= 0:
        raise RuntimeError("CI_SEED did not produce main token and bound port")

    # Clear logcat BEFORE starting public server & issuing any public secret/tokens so our leak check is exact.
    run("adb", "logcat", "-c", check_rc=False)

    start_info = send_public_hook("start")
    check("hook_start", start_info.get("ok") is True and start_info.get("port", 0) > 0, f"port={start_info.get('port')}")
    pub_port = int(start_info["port"])
    pub_secret = start_info["secret"]
    tailnet_secret = start_info.get("tailnet_secret", "")

    run("adb", "forward", f"tcp:{PUB_LOCAL_PORT}", f"tcp:{pub_port}")
    run("adb", "forward", f"tcp:{API_LOCAL_PORT}", f"tcp:{api_port}")

    base = f"http://127.0.0.1:{PUB_LOCAL_PORT}"
    resource = f"{base}/mcp"
    pub_hdr = {"X-AVS-Public": pub_secret, "Host": f"127.0.0.1:{PUB_LOCAL_PORT}"}
    secrets_to_check = [("TSB_PUBLIC_SECRET", pub_secret)]
    if tailnet_secret:
        secrets_to_check.append(("TSB_SECRET", tailnet_secret))

    # J.1 regression (J-API34-REPRO): re-delivering HostService.onStartCommand (as BootReceiver does
    # on late ACTION_BOOT_COMPLETED) runs PublicAccess.sync() and MUST keep 127.0.0.1:18785 serving.
    shell("am", "start-foreground-service", "-n", f"{APP}/.host.HostService")
    time.sleep(1.5)
    st_boot, _, _ = http_req(PUB_LOCAL_PORT, "GET", "/.well-known/oauth-protected-resource", pub_hdr)
    check("host_service_restart_keeps_18785", st_boot == 200, f"status={st_boot}")

    # 1) Missing / wrong X-AVS-Public -> 403 (even if attacker spoofs X-AVS-Public-Src)
    st, _, _ = http_req(PUB_LOCAL_PORT, "GET", "/.well-known/oauth-protected-resource", {"Host": f"127.0.0.1:{PUB_LOCAL_PORT}"})
    check("missing_secret_403", st == 403, f"status={st}")
    st, _, _ = http_req(PUB_LOCAL_PORT, "GET", "/.well-known/oauth-protected-resource", {
        "Host": f"127.0.0.1:{PUB_LOCAL_PORT}",
        "X-AVS-Public-Src": "203.0.113.99",
    })
    check("spoof_public_src_no_secret_403", st == 403, f"status={st}")
    st, _, _ = http_req(PUB_LOCAL_PORT, "GET", "/.well-known/oauth-protected-resource", {"X-AVS-Public": "wrong", "Host": f"127.0.0.1:{PUB_LOCAL_PORT}"})
    check("wrong_secret_403", st == 403, f"status={st}")

    # 2) Unlisted paths -> 404
    for p_method, p_path in [
        ("GET", "/v2/ping"),
        ("POST", "/v2/run"),
        ("GET", "/v2/shot/x.jpg"),
        ("GET", "/cli"),
        ("GET", "/.well-known/oauth-authorization-server/mcp"),
    ]:
        st, _, _ = http_req(PUB_LOCAL_PORT, p_method, p_path, pub_hdr, "{}" if p_method == "POST" else None)
        check(f"unlisted_404_{p_path}", st == 404, f"status={st}")

    # 3) 7.8 #3: GET / DELETE /mcp -> 405 + Allow: POST BEFORE auth (both PublicServer and ApiServer)
    for m in ("GET", "DELETE"):
        st, hdrs, _ = http_req(PUB_LOCAL_PORT, m, "/mcp", pub_hdr)
        check(f"public_mcp_{m}_405", st == 405 and hdrs.get("allow") == "POST", f"status={st} allow={hdrs.get('allow')}")
        st2, hdrs2, _ = http_req(API_LOCAL_PORT, m, "/mcp", {"Host": f"127.0.0.1:{API_LOCAL_PORT}"})
        check(f"local_mcp_{m}_405", st2 == 405 and hdrs2.get("allow") == "POST", f"status={st2} allow={hdrs2.get('allow')}")

    # 4) 7.7 Anti-spoofing: X-VScreen-Tailnet + main token on PublicServer -> 401 (never accepted as tailnet)
    spoof_hdr = dict(pub_hdr)
    spoof_hdr["X-VScreen-Tailnet"] = tailnet_secret
    spoof_hdr["X-VScreen-Peer"] = "alice@mac"
    spoof_hdr["Authorization"] = f"Bearer {main_token}"
    spoof_hdr["Content-Type"] = "application/json"
    st, hdrs, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", spoof_hdr, '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
    check("spoof_tailnet_rejected_401", st == 401 and "resource_metadata=" in hdrs.get("www-authenticate", ""), f"status={st}")
    # Clear lockout counter so test stays clean
    send_public_hook("clear_lockout")

    # 5) Discovery (Inspector & mcp-remote): unauth POST /mcp -> 401 + WWW-Authenticate
    st, hdrs, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", dict(pub_hdr, **{"Content-Type": "application/json"}), '{"jsonrpc":"2.0","id":1,"method":"initialize"}')
    www = hdrs.get("www-authenticate", "")
    expected_prm_url = f"{base}/.well-known/oauth-protected-resource"
    check("unauth_mcp_401_www_auth", st == 401 and expected_prm_url in www, f"status={st} www={www}")

    st_root, _, body_root = http_req(PUB_LOCAL_PORT, "GET", "/.well-known/oauth-protected-resource", pub_hdr)
    st_mcp, _, body_mcp = http_req(PUB_LOCAL_PORT, "GET", "/.well-known/oauth-protected-resource/mcp", pub_hdr)
    prm_root = json.loads(body_root)
    prm_mcp = json.loads(body_mcp)
    check(
        "prm_both_paths_identical",
        st_root == 200 and st_mcp == 200 and prm_root == prm_mcp and prm_root.get("resource") == resource and prm_root.get("scopes_supported") == ["look", "operate"],
        f"prm={prm_root}",
    )

    st_as, _, body_as = http_req(PUB_LOCAL_PORT, "GET", "/.well-known/oauth-authorization-server", pub_hdr)
    as_meta = json.loads(body_as)
    check(
        "as_metadata_root",
        st_as == 200 and as_meta.get("issuer") == base and as_meta.get("code_challenge_methods_supported") == ["S256"] and as_meta.get("token_endpoint_auth_methods_supported") == ["none"],
        f"issuer={as_meta.get('issuer')}",
    )

    # 6) Dynamic registration (RFC 7591)
    redirect_uri = "http://127.0.0.1:6274/oauth/callback"
    reg_body = json.dumps({
        "client_name": "SmokeRemoteClient",
        "redirect_uris": [redirect_uri],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
    })
    st_reg, _, body_reg = http_req(PUB_LOCAL_PORT, "POST", "/oauth/register", dict(pub_hdr, **{"Content-Type": "application/json"}), reg_body)
    reg = json.loads(body_reg)
    client_id = reg.get("client_id", "")
    check("oauth_register_201", st_reg == 201 and client_id.startswith("pub_"), f"status={st_reg} cid={client_id}")

    # 7) Authorize + pairing HTML + status poll + debug-hook approval
    verifier, challenge = pkce_pair()
    q = urllib.parse.urlencode({
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "resource": resource,
        "scope": "look operate system",
        "state": "smoke-state-42",
    })
    st_auth, _, html_auth = http_req(PUB_LOCAL_PORT, "GET", f"/oauth/authorize?{q}", pub_hdr)
    code_match = re.search(r'<div class="code">([A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4})</div>', html_auth)
    id_match = re.search(r'var id="(pair_[0-9a-f]+)"', html_auth)
    check("oauth_authorize_html_pairing", st_auth == 200 and code_match is not None and id_match is not None, f"status={st_auth}")
    pairing_id = id_match.group(1)

    st_p1, _, body_p1 = http_req(PUB_LOCAL_PORT, "GET", f"/oauth/authorize/status?id={pairing_id}", pub_hdr)
    check("pairing_status_pending", st_p1 == 200 and json.loads(body_p1).get("status") == "pending", body_p1)

    # Approve as "operate" (even though client also passed "system" in scope, system is never granted)
    appr = send_public_hook("approve:operate")
    check("debug_hook_approve", appr.get("ok") is True, str(appr))

    st_p2, _, body_p2 = http_req(PUB_LOCAL_PORT, "GET", f"/oauth/authorize/status?id={pairing_id}", pub_hdr)
    p2 = json.loads(body_p2)
    redir = p2.get("redirect_uri", "")
    parsed_redir = urllib.parse.urlparse(redir)
    qs = urllib.parse.parse_qs(parsed_redir.query)
    auth_code = qs.get("code", [""])[0]
    check(
        "pairing_status_approved",
        st_p2 == 200 and p2.get("status") == "approved" and redir.startswith(redirect_uri) and bool(auth_code) and qs.get("state", [""])[0] == "smoke-state-42",
        f"status={p2.get('status')} state={qs.get('state')}",
    )
    secrets_to_check.append(("auth_code", auth_code))

    # 8) Token exchange + single-use code enforcement
    tok_form = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": auth_code,
        "redirect_uri": redirect_uri,
        "client_id": client_id,
        "code_verifier": verifier,
        "resource": resource,
    })
    st_tok, _, body_tok = http_req(PUB_LOCAL_PORT, "POST", "/oauth/token", dict(pub_hdr, **{"Content-Type": "application/x-www-form-urlencoded"}), tok_form)
    tok = json.loads(body_tok)
    access_1 = tok.get("access_token", "")
    refresh_1 = tok.get("refresh_token", "")
    check(
        "oauth_token_issued",
        st_tok == 200 and bool(access_1) and bool(refresh_1) and tok.get("expires_in") == 3600 and tok.get("scope") == "operate",
        f"status={st_tok} expires_in={tok.get('expires_in')} scope={tok.get('scope')}",
    )
    secrets_to_check.append(("access_token_1", access_1))
    secrets_to_check.append(("refresh_token_1", refresh_1))

    st_replay, _, body_replay = http_req(PUB_LOCAL_PORT, "POST", "/oauth/token", dict(pub_hdr, **{"Content-Type": "application/x-www-form-urlencoded"}), tok_form)
    check("auth_code_single_use", st_replay == 400 and json.loads(body_replay).get("error") == "invalid_grant", f"status={st_replay}")

    # 9) MCP initialize, tools/list, vscreen_status, and system-scope denial
    mcp_hdr = dict(pub_hdr, **{"Authorization": f"Bearer {access_1}", "Content-Type": "application/json"})
    st_init, _, body_init = http_req(PUB_LOCAL_PORT, "POST", "/mcp", mcp_hdr, json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "smoke", "version": "1.0"}},
    }))
    check("mcp_initialize", st_init == 200 and "result" in json.loads(body_init), f"status={st_init}")

    st_list, _, body_list = http_req(PUB_LOCAL_PORT, "POST", "/mcp", mcp_hdr, json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}))
    tools = json.loads(body_list).get("result", {}).get("tools", [])
    tool_names = {t.get("name") for t in tools}
    check("mcp_tools_list", st_list == 200 and "vscreen" in tool_names and "vscreen_act" in tool_names, f"count={len(tools)}")

    st_stat, _, body_stat = http_req(PUB_LOCAL_PORT, "POST", "/mcp", mcp_hdr, json.dumps({
        "jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "vscreen_status", "arguments": {}},
    }))
    stat_res = json.loads(body_stat).get("result", {})
    check("mcp_vscreen_status", st_stat == 200 and stat_res.get("isError") is False, f"status={st_stat}")

    # System command MUST return SCOPE_DENIED
    st_sys, _, body_sys = http_req(PUB_LOCAL_PORT, "POST", "/mcp", mcp_hdr, json.dumps({
        "jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "vscreen_fix", "arguments": {}},
    }))
    sys_code = json.loads(body_sys).get("result", {}).get("structuredContent", {}).get("code")
    check("public_system_scope_denied", st_sys == 200 and sys_code == "SCOPE_DENIED", f"code={sys_code}")

    # Local ApiServer MUST reject public OAuth access token (7.7)
    st_loc, _, _ = http_req(API_LOCAL_PORT, "POST", "/v2/run", {
        "Host": f"127.0.0.1:{API_LOCAL_PORT}",
        "Authorization": f"Bearer {access_1}",
        "Content-Type": "application/json",
    }, '{"cmd":"status"}')
    check("local_api_rejects_public_token", st_loc == 401, f"status={st_loc}")

    # Audit log must record source="public"
    audit_raw = read_app_file("files/audit/audit.jsonl")
    check("audit_records_public_source", '"source":"public"' in audit_raw, "checked files/audit/audit.jsonl")

    # 10) Advance clock by 1 hour + 1 second -> access_1 expires (7.6)
    adv = send_public_hook("advance_ms:3601000")
    check("clock_advance_1h", adv.get("ok") is True, str(adv))
    st_exp, hdrs_exp, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", mcp_hdr, json.dumps({"jsonrpc": "2.0", "id": 5, "method": "tools/list"}))
    check("access_token_expired_after_1h", st_exp == 401 and expected_prm_url in hdrs_exp.get("www-authenticate", ""), f"status={st_exp}")

    # 11) Refresh token checks: invalid_target (7.8 #7), wrong client_id, rotation, and reuse revocation (7.2)
    st_bad_res, _, body_bad_res = http_req(PUB_LOCAL_PORT, "POST", "/oauth/token", dict(pub_hdr, **{"Content-Type": "application/x-www-form-urlencoded"}), urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": refresh_1,
        "client_id": client_id,
        "resource": "https://other.example/mcp",
    }))
    check("refresh_invalid_target", st_bad_res == 400 and json.loads(body_bad_res).get("error") == "invalid_target", body_bad_res)

    st_bad_cid, _, body_bad_cid = http_req(PUB_LOCAL_PORT, "POST", "/oauth/token", dict(pub_hdr, **{"Content-Type": "application/x-www-form-urlencoded"}), urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": refresh_1,
        "client_id": "pub_wrong",
    }))
    check("refresh_wrong_client_id", st_bad_cid == 400 and json.loads(body_bad_cid).get("error") == "invalid_grant", body_bad_cid)

    # Valid refresh (omitting resource -> keeps originally bound resource per 7.8 #7)
    st_ref, _, body_ref = http_req(PUB_LOCAL_PORT, "POST", "/oauth/token", dict(pub_hdr, **{"Content-Type": "application/x-www-form-urlencoded"}), urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": refresh_1,
        "client_id": client_id,
    }))
    ref = json.loads(body_ref)
    access_2 = ref.get("access_token", "")
    refresh_2 = ref.get("refresh_token", "")
    check("refresh_rotated", st_ref == 200 and bool(access_2) and access_2 != access_1 and bool(refresh_2) and refresh_2 != refresh_1, f"status={st_ref}")
    secrets_to_check.append(("access_token_2", access_2))
    secrets_to_check.append(("refresh_token_2", refresh_2))

    mcp_hdr_2 = dict(pub_hdr, **{"Authorization": f"Bearer {access_2}", "Content-Type": "application/json"})
    st_list_2, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", mcp_hdr_2, json.dumps({"jsonrpc": "2.0", "id": 6, "method": "tools/list"}))
    check("refreshed_access_token_works", st_list_2 == 200, f"status={st_list_2}")

    # 11b) Section I: per-source lockout isolation (IPv4 /32, IPv6 /64) & X-Forwarded-For ignored
    send_public_hook("clear_lockout")
    src_a_429 = False
    for attempt in range(9):
        bad_hdr = dict(pub_hdr, **{
            "X-AVS-Public-Src": "198.51.100.10",
            "X-Forwarded-For": f"10.0.0.{attempt + 1}",
            "Authorization": f"Bearer unknown_attacker_token_{attempt}",
            "Content-Type": "application/json",
        })
        st_att, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", bad_hdr, json.dumps({"jsonrpc": "2.0", "id": 100 + attempt, "method": "tools/list"}))
        if st_att == 429:
            src_a_429 = True
            break
    check("src_a_locked_out_ignores_xff", src_a_429, "198.51.100.10 hit 429 despite rotating X-Forwarded-For")

    # Source B (different IPv4) MUST remain completely unaffected with valid token AND unauth probe
    src_b_ok_hdr = dict(mcp_hdr_2, **{"X-AVS-Public-Src": "198.51.100.20"})
    st_b_ok, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", src_b_ok_hdr, json.dumps({"jsonrpc": "2.0", "id": 120, "method": "tools/list"}))
    st_b_unauth, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", dict(pub_hdr, **{"X-AVS-Public-Src": "198.51.100.20", "Content-Type": "application/json"}), '{"jsonrpc":"2.0","id":121,"method":"initialize"}')
    check("per_source_lockout_isolated_ipv4", st_b_ok == 200 and st_b_unauth == 401, f"srcB valid={st_b_ok} unauth={st_b_unauth}")

    # IPv6 /64 bucketing: lock 2001:db8:aaaa:1111::1 -> ::99 in same /64 gets 429; 2222::1 in other /64 gets 200
    v6_locked = False
    for attempt in range(9):
        bad_v6 = dict(pub_hdr, **{
            "X-AVS-Public-Src": "2001:db8:aaaa:1111::1",
            "Authorization": f"Bearer unknown_v6_token_{attempt}",
            "Content-Type": "application/json",
        })
        st_v6, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", bad_v6, json.dumps({"jsonrpc": "2.0", "id": 130 + attempt, "method": "tools/list"}))
        if st_v6 == 429:
            v6_locked = True
            break
    st_v6_same64, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", dict(mcp_hdr_2, **{"X-AVS-Public-Src": "2001:db8:aaaa:1111::99"}), json.dumps({"jsonrpc": "2.0", "id": 140, "method": "tools/list"}))
    st_v6_other64, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", dict(mcp_hdr_2, **{"X-AVS-Public-Src": "2001:db8:aaaa:2222::1"}), json.dumps({"jsonrpc": "2.0", "id": 141, "method": "tools/list"}))
    # 2.0.0-beta11 (P7): the lock stops guessing, not a client that holds a valid token behind the same egress.
    bad_same64 = dict(pub_hdr, **{"X-AVS-Public-Src": "2001:db8:aaaa:1111::99", "Authorization": "Bearer unknown_v6_token_x", "Content-Type": "application/json"})
    st_v6_same64_bad, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", bad_same64, json.dumps({"jsonrpc": "2.0", "id": 142, "method": "tools/list"}))
    check("per_source_lockout_ipv6_64", v6_locked and st_v6_same64_bad == 429 and st_v6_same64 == 200 and st_v6_other64 == 200,
          f"locked={v6_locked} same64_bad={st_v6_same64_bad} same64_valid={st_v6_same64} other64={st_v6_other64}")
    send_public_hook("clear_lockout")

    # Reuse retired refresh_1 -> revokes ALL tokens of this client (access_2 AND refresh_2 stop working!)
    st_reuse, _, body_reuse = http_req(PUB_LOCAL_PORT, "POST", "/oauth/token", dict(pub_hdr, **{"Content-Type": "application/x-www-form-urlencoded"}), urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": refresh_1,
        "client_id": client_id,
    }))
    check("retired_refresh_reuse_rejected", st_reuse == 400 and json.loads(body_reuse).get("error") == "invalid_grant", f"status={st_reuse}")

    st_rev_at, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/mcp", mcp_hdr_2, json.dumps({"jsonrpc": "2.0", "id": 7, "method": "tools/list"}))
    check("reuse_revoked_access_token_2", st_rev_at == 401, f"status={st_rev_at}")

    st_rev_rt, _, _ = http_req(PUB_LOCAL_PORT, "POST", "/oauth/token", dict(pub_hdr, **{"Content-Type": "application/x-www-form-urlencoded"}), urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": refresh_2,
        "client_id": client_id,
    }))
    check("reuse_revoked_refresh_token_2", st_rev_rt == 400, f"status={st_rev_rt}")

    # 12) 7.7: Logcat MUST NOT contain any access token, refresh token, auth code, or TSB_PUBLIC_SECRET
    _, logcat_text = run("adb", "logcat", "-d", timeout=60, check_rc=False)
    leaked = [label for label, val in secrets_to_check if val and val in logcat_text]
    check("logcat_no_secret_leaks", len(leaked) == 0, f"leaked={leaked}" if leaked else f"checked {len(secrets_to_check)} secrets, 0 hits")

    # Also verify private audit.jsonl does not leak any raw secret/token/code
    audit_after = read_app_file("files/audit/audit.jsonl")
    audit_leaked = [label for label, val in secrets_to_check if val and val in audit_after]
    check("audit_no_secret_leaks", len(audit_leaked) == 0, f"leaked={audit_leaked}" if audit_leaked else "0 hits")

    send_public_hook("reset")

    if FAILED:
        print(f"SUMMARY failed ({len(FAILED)}): {', '.join(FAILED)}", flush=True)
        sys.exit(1)
    print("SUMMARY all passed", flush=True)


if __name__ == "__main__":
    main()
