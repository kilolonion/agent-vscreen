"""Exercise the packaged Android node against a disposable Headscale via real UI controls."""
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.kilon.agentvscreen"
FL = "com.follow.clash"
HOST = "headscale.smoke.test"
URL = "http://" + HOST + ":18081"
D = Path("hs").resolve()
CLI = [str(D / "headscale_0.27.1_linux_amd64"), "-c", str(D / "config.yaml")]


def run(*args, timeout=45):
    return subprocess.check_output(args, text=True, timeout=timeout, stderr=subprocess.STDOUT).strip()


def adb(*args):
    return run("adb", *args)


def sh(*args):
    return adb("shell", *args)


def fixtures():
    s = (D / "example.yaml").read_text()
    for old, new in [
        ("http://127.0.0.1:8080", URL), ("127.0.0.1:8080", "127.0.0.1:18081"),
        ("/var/lib/headscale/", str(D) + "/"), ("/var/run/headscale/headscale.sock", str(D / "hs.sock")),
        ("disable_check_updates: false", "disable_check_updates: true"),
        ("  urls:\n    - https://controlplane.tailscale.com/derpmap/default", "  urls: []"),
        ("  paths: []", "  paths: [" + str(D / "derp.yaml") + "]")]:
        assert old in s, old
        s = s.replace(old, new)
    (D / "derp.yaml").write_text("regions:\n  900:\n    regionid: 900\n    regioncode: local\n    regionname: local\n    nodes:\n      - name: local\n        regionid: 900\n        hostname: localhost\n        ipv4: 127.0.0.1\n        ipv6: none\n")
    (D / "config.yaml").write_text(s)
    profiles = D / "profiles"
    profiles.mkdir()
    (profiles / "smoke.yaml").write_text("mode: rule\nhosts:\n  headscale.smoke.test: 10.0.2.2\ndns:\n  enable: true\n  use-hosts: true\n  nameserver: [10.0.2.2]\nproxies: []\nproxy-groups: []\nrules:\n  - MATCH,DIRECT\n")
    for args in [CLI + ["serve"], [sys.executable, "-m", "http.server", "18082", "--bind", "127.0.0.1", "--directory", str(profiles)]]:
        with open(D / ("process-" + str(len(args)) + ".log"), "w") as out:
            subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=out, stderr=out, start_new_session=True)
    for _ in range(25):
        try:
            if urllib.request.urlopen("http://127.0.0.1:18081/health", timeout=1).status == 200: break
        except OSError: time.sleep(.4)
    else: raise RuntimeError("Headscale failed health check")
    run(*CLI, "users", "create", "mobile-ci")


def nodes():
    if "dumped" not in sh("uiautomator", "dump", "/data/local/tmp/window.xml").lower(): return []
    return list(ET.fromstring(sh("cat", "/data/local/tmp/window.xml")).iter("node"))


def tap(node):
    a,b,c,d = map(int,re.findall(r"\d+",node.get("bounds")))
    sh("input","tap",str((a+c)//2),str((b+d)//2))


def find(package, labels=(), typ=None, wait=40):
    end=time.monotonic()+wait
    while time.monotonic()<end:
        tree=nodes()
        for n in tree:
            if n.get("package")!=package: continue
            val=(n.get("text") or n.get("content-desc","")).strip()
            if (typ and n.get("class")==typ) or any(x.casefold()==val.casefold() for x in labels):
                return n
        time.sleep(.7)
    # Report only sanitized labels from this disposable test.
    displayed=[(n.get("text") or n.get("content-desc") or "")[:100] for n in tree if n.get("package")==package]
    displayed=[s for s in displayed if s and not any(v in s.lower() for v in ("http","/register/","key","token"))]
    raise RuntimeError("Missing "+repr(labels or typ)+"; labels="+repr(displayed[:28]))


def vpn():
    adb("install","test-apks/flclash.apk")
    sh("appops","set",FL,"ACTIVATE_VPN","allow")
    sh("monkey","-p",FL,"1")
    tap(find(FL,["Agree","同意"],wait=55))
    time.sleep(5)
    sh("am","start","-a","android.intent.action.VIEW","-d","flclash://install-config?url=http%3A%2F%2F10.0.2.2%3A18082%2Fsmoke.yaml","-p",FL)
    tap(find(FL,["Confirm","OK","确定","确认"]))
    time.sleep(5)
    sh("am","start","-a",FL+".action.START","-p",FL)
    for _ in range(45):
        conn=sh("dumpsys","connectivity")
        if re.search(r"NetworkAgentInfo[^\n]*(?:VPN|TRANSPORT_VPN)",conn,re.I) and FL in sh("dumpsys","activity","services",FL):
            print("PASS FlClash Android VPN connected",flush=True);return
        time.sleep(1)
    raise RuntimeError("FlClash VPN did not become active")


def test(mode):
    sh("wm","size","1080x2800");sh("wm","density","240")
    if mode=="flclash":vpn()
    adb("install","test-apks/app-release.apk")
    sh("am","start","-W","-n",APP+"/.ui.MainActivity","--es","page","REMOTE")
    tap(find(APP,["Built-in Tailscale","内置 Tailscale"]))
    tap(find(APP,["Advanced","高级选项"]))
    tap(find(APP,typ="android.widget.EditText"))
    sh("input","text",URL)
    sh("input","keyevent","4")
    tap(find(APP,["Save and reconnect","保存并重连"]))
    print("PASS Headscale advanced sheet saved",flush=True)
    try:
        tap(find(APP,["Copy login link","复制登录链接"],wait=92))
    except RuntimeError:
        logs=sh("logcat","-d","-s","Tailnet:I").lower()
        print("Bridge diagnostics:",[v for v in ["exec format error","permission denied","linker","context canceled","no route","no dns fallback","bridge exited","controlhttp","not found","sigsegv"] if v in logs])
        raise
    tap(find(APP,["Advanced","高级选项"]))
    tap(find(APP,typ="android.widget.EditText"))
    sh("input","keycombination","113","29") if int(sh("getprop","ro.build.version.sdk"))>=34 else sh("input","keyevent","123")
    sh("input","keyevent","279")
    text=" ".join(n.get("text","") for n in nodes())
    match=re.search(re.escape(URL)+r"/register/([A-Za-z0-9_-]+)",text)
    if not match:raise RuntimeError("The copied text did not contain a registration URL from the fixture")
    print("PASS received a link from private Headscale",flush=True)
    run(*CLI,"nodes","register","--user","mobile-ci","--key",match.group(1))
    sh("input","keyevent","4");sh("input","keyevent","4")
    for _ in range(35):
        records=json.loads(run(*CLI,"nodes","list","-o","json"))
        if any(r.get("online") for r in records):
            print("PASS Headscale confirms Android node online, FlClash="+mode,flush=True);return
        time.sleep(1)
    raise RuntimeError("Registered node did not become online")


def device_diagnostics():
    # Keep test-only failure evidence free of registration URLs and configuration values.
    relevant = sh("logcat","-d","-t","3500")
    lines = [ln for ln in relevant.splitlines() if any(t in ln for t in (
        "Tailnet ", "AndroidRuntime:", "FATAL EXCEPTION", "Fatal signal", "ndk_translation:",
        "libtsbridge.so", "force finishing", "com.kilon.agentvscreen.ui.MainActivity"))]
    for line in lines[-60:]:
        if re.search(r"authorization|token|key[=:]|register/|tskey-|secret",line,re.I): continue
        print(re.sub(r"https?://[^ ]+", "<url>",line)[:260])
    print("Visible packages", sorted({n.get("package","") for n in nodes()}))
    print("VPN interfaces", sh("ip","-br","link","show")[:450])

if sys.argv[1]=="fixtures":fixtures()
else:
    try:test(sys.argv[1])
    except Exception:
        device_diagnostics()
        raise
