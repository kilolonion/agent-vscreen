package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNgrokURL(t *testing.T) {
	for in, want := range map[string]string{
		"":                               "",
		"Name.ngrok-free.app":            "https://name.ngrok-free.app",
		"https://name.ngrok-free.app/x/": "https://name.ngrok-free.app",
	} {
		if got := ngrokURL(in); got != want {
			t.Errorf("ngrokURL(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestNgrokProxyMarksPublicAndTakesNgrokSource(t *testing.T) {
	var got http.Header
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { got = r.Header.Clone() }))
	defer up.Close()
	h := ngrokProxy(up.Listener.Addr().String(), "s3cret")
	req := httptest.NewRequest("GET", "/mcp", nil)
	req.Header.Set("X-AVS-Public", "forged")
	req.Header.Set("X-VScreen-Tailnet", "forged")
	req.Header.Set("X-Forwarded-For", "10.9.9.9, 203.0.113.7")
	h.ServeHTTP(httptest.NewRecorder(), req)
	if got.Get("X-AVS-Public") != "s3cret" || got.Get("X-VScreen-Tailnet") != "" || got.Get("X-AVS-Public-Src") != "203.0.113.7" {
		t.Fatalf("headers: %v", got)
	}
	req = httptest.NewRequest("GET", "/mcp", nil)
	req.Header.Set("X-Forwarded-For", "evil.example")
	h.ServeHTTP(httptest.NewRecorder(), req)
	if got.Get("X-AVS-Public-Src") != "" {
		t.Fatalf("name used as source: %v", got)
	}
}
