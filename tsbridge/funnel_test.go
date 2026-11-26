package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"testing"
)

func TestPreparePublicProxyRequestStripsInternalHeaders(t *testing.T) {
	target, _ := url.Parse("http://127.0.0.1:18785")
	rp := httputil.NewSingleHostReverseProxy(target)
	base := rp.Director

	req, err := http.NewRequest("POST", "https://phone.tailnet.ts.net/mcp", strings.NewReader(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Host = "phone.tailnet.ts.net"
	req.Header.Set("X-VScreen-Tailnet", "spoofed-tailnet-secret")
	req.Header.Set("X-VScreen-Peer", "alice@example.com")
	req.Header.Set("X-VScreen-Key", "main")
	req.Header.Set("X-VScreen-Token", "local-token")
	req.Header.Set("X-AVS-Public", "spoofed-public-secret")
	req.Header["x-avs-custom"] = []string{"evil"}
	req.Header["X-VSCREEN-MIXED"] = []string{"evil2"}
	req.Header.Set("Authorization", "Bearer real-oauth-token")
	req.Header.Set("Content-Type", "application/json")

	preparePublicProxyRequest(req, base, "real-bridge-secret")

	if got := req.Header.Get("X-AVS-Public"); got != "real-bridge-secret" {
		t.Fatalf("X-AVS-Public = %q, want real-bridge-secret", got)
	}
	for k := range req.Header {
		lk := strings.ToLower(k)
		if strings.HasPrefix(lk, "x-vscreen-") {
			t.Fatalf("header %q must be stripped", k)
		}
		if strings.HasPrefix(lk, "x-avs-") && lk != "x-avs-public" {
			t.Fatalf("unexpected x-avs header %q must be stripped", k)
		}
	}
	if got := req.Header.Get("Authorization"); got != "Bearer real-oauth-token" {
		t.Fatalf("Authorization = %q, want preserved", got)
	}
	if got := req.Host; got != "phone.tailnet.ts.net" {
		t.Fatalf("Host = %q, want phone.tailnet.ts.net", got)
	}
	if req.URL.Host != "127.0.0.1:18785" {
		t.Fatalf("URL.Host = %q, want 127.0.0.1:18785", req.URL.Host)
	}
}

func TestEmitPreservesStickyFunnelStatus(t *testing.T) {
	var buf bytes.Buffer
	origOut := outWriter
	outWriter = &buf
	defer func() {
		outWriter = origOut
		outMu.Lock()
		last = ""
		outMu.Unlock()
		funnelMu.Lock()
		funnelState = ""
		funnelReason = ""
		lastBase = status{}
		hasLastBase = false
		funnelMu.Unlock()
	}()

	outMu.Lock()
	last = ""
	outMu.Unlock()
	funnelMu.Lock()
	funnelState = ""
	funnelReason = ""
	lastBase = status{}
	hasLastBase = false
	funnelMu.Unlock()

	emit(status{State: "Running", IP: "100.64.0.1", DNSName: "phone.tailnet.ts.net"})
	setFunnelStatus("error", "HTTPS must be enabled in the admin console")
	// Subsequent periodic status tick without Funnel set must keep funnel=error and reason.
	emit(status{State: "Running", IP: "100.64.0.1", DNSName: "phone.tailnet.ts.net", Online: 2})
	setFunnelStatus("on", "")
	emit(status{State: "Running", IP: "100.64.0.1", DNSName: "phone.tailnet.ts.net", Online: 3})

	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	if len(lines) != 5 {
		t.Fatalf("expected 5 emitted status lines, got %d:\n%s", len(lines), buf.String())
	}

	var s2, s3, s4, s5 status
	if err := json.Unmarshal([]byte(lines[1]), &s2); err != nil {
		t.Fatal(err)
	}
	if s2.Funnel != "error" || !strings.Contains(s2.FunnelReason, "HTTPS must be enabled") {
		t.Fatalf("line 2 = %+v", s2)
	}
	if err := json.Unmarshal([]byte(lines[2]), &s3); err != nil {
		t.Fatal(err)
	}
	if s3.Funnel != "error" || s3.Online != 2 || s3.FunnelReason != s2.FunnelReason {
		t.Fatalf("line 3 lost sticky funnel error: %+v", s3)
	}
	if err := json.Unmarshal([]byte(lines[3]), &s4); err != nil {
		t.Fatal(err)
	}
	if s4.Funnel != "on" || s4.FunnelReason != "" {
		t.Fatalf("line 4 = %+v", s4)
	}
	if err := json.Unmarshal([]byte(lines[4]), &s5); err != nil {
		t.Fatal(err)
	}
	if s5.Funnel != "on" || s5.Online != 3 {
		t.Fatalf("line 5 lost sticky funnel=on: %+v", s5)
	}
}
