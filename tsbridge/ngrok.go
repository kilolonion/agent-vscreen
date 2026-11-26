package main

// 2.0.0-beta11 (rainbow: also support ngrok for public MCP). The same binary runs as an ngrok agent when
// TSB_MODE=ngrok: it opens one HTTPS endpoint on the user's ngrok account (golang.ngrok.com/ngrok/v2, MIT) and
// forwards every request to PublicServer on 127.0.0.1, exactly like Funnel does (internal headers stripped,
// X-AVS-Public set, the client address from ngrok's X-Forwarded-For as X-AVS-Public-Src). Sharing the binary keeps
// the APK from carrying a second Go runtime and reuses the Android DNS setup (dns.go).
//
//	env:    NGROK_AUTHTOKEN (never logged), AVS_NGROK_URL (optional, e.g. https://name.ngrok-free.app),
//	        TSB_PUBLIC_TARGET (default 127.0.0.1:18785), TSB_PUBLIC_SECRET, TSB_DNS.
//	stdout: one JSON object per change: {"ngrok":"connecting|online|error","url":"…","reason":"…"}.
//	stdin:  EOF (the app died) or "quit" ends the process.

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"time"

	"golang.ngrok.com/ngrok/v2"
)

type ngrokStatus struct {
	State  string `json:"ngrok"`
	URL    string `json:"url,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// ngrokURL normalises what the user typed for their domain: "name.ngrok-free.app", "https://name.ngrok-free.app/".
func ngrokURL(v string) string {
	v = strings.TrimSpace(v)
	if v == "" {
		return ""
	}
	if !strings.Contains(v, "://") {
		v = "https://" + v
	}
	u, err := url.Parse(v)
	if err != nil || u.Host == "" {
		return ""
	}
	return "https://" + strings.ToLower(u.Host)
}

// ngrokSource: the caller's address as ngrok reports it (the last X-Forwarded-For entry is the one ngrok added).
func ngrokSource(xff string) string {
	parts := strings.Split(xff, ",")
	ip := strings.TrimSpace(parts[len(parts)-1])
	if net.ParseIP(ip) == nil {
		return ""
	}
	return ip
}

func ngrokProxy(pubTarget, pubSecret string) http.Handler {
	ptu, _ := url.Parse("http://" + pubTarget)
	prp := httputil.NewSingleHostReverseProxy(ptu)
	prp.FlushInterval = -1
	pbase := prp.Director
	prp.Director = func(r *http.Request) {
		src := ngrokSource(r.Header.Get("X-Forwarded-For"))
		preparePublicProxyRequest(r, pbase, pubSecret)
		r.Header.Del("X-Forwarded-For")
		if src != "" {
			r.Header.Set("X-AVS-Public-Src", src)
		}
	}
	prp.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusBadGateway)
		fmt.Fprintf(w, `{"ok":false,"code":"APP_NOT_ANSWERING","error":"Agent-VScreen public server did not answer (%s)"}`, strings.ReplaceAll(err.Error(), `"`, `'`))
	}
	return bounded(prp)
}

func runNgrok() {
	token := strings.TrimSpace(os.Getenv("NGROK_AUTHTOKEN"))
	pubSecret := os.Getenv("TSB_PUBLIC_SECRET")
	pubTarget := env("TSB_PUBLIC_TARGET", "127.0.0.1:18785")
	want := ngrokURL(os.Getenv("AVS_NGROK_URL"))
	if token == "" || pubSecret == "" {
		emitRaw(ngrokStatus{State: "error", Reason: "NGROK_AUTHTOKEN or TSB_PUBLIC_SECRET missing"})
		os.Exit(2)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		// The app holds our stdin: EOF means it is gone.
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			if strings.TrimSpace(sc.Text()) == "quit" {
				break
			}
		}
		cancel()
		time.Sleep(2 * time.Second)
		os.Exit(0)
	}()

	online := ""
	agent, err := ngrok.NewAgent(
		ngrok.WithAuthtoken(token),
		ngrok.WithClientInfo("agent-vscreen", "1"),
		ngrok.WithEventHandler(func(e ngrok.Event) {
			switch ev := e.(type) {
			case *ngrok.EventAgentDisconnected:
				reason := ""
				if ev.Error != nil {
					reason = ev.Error.Error()
				}
				emitRaw(ngrokStatus{State: "connecting", Reason: reason})
			case *ngrok.EventAgentConnectSucceeded:
				if online != "" {
					emitRaw(ngrokStatus{State: "online", URL: online})
				}
			}
		}),
	)
	if err != nil {
		emitRaw(ngrokStatus{State: "error", Reason: err.Error()})
		os.Exit(3)
	}
	emitRaw(ngrokStatus{State: "connecting"})
	opts := []ngrok.EndpointOption{ngrok.WithDescription("Agent-VScreen public MCP")}
	if want != "" {
		opts = append(opts, ngrok.WithURL(want))
	}
	ln, err := agent.Listen(ctx, opts...)
	if err != nil {
		emitRaw(ngrokStatus{State: "error", Reason: err.Error()})
		os.Exit(3)
	}
	online = strings.TrimSuffix(ln.URL().String(), "/")
	emitRaw(ngrokStatus{State: "online", URL: online})
	srv := &http.Server{Handler: ngrokProxy(pubTarget, pubSecret), ReadHeaderTimeout: 30 * time.Second}
	go func() { <-ctx.Done(); _ = srv.Close(); _ = ln.Close() }()
	if err := srv.Serve(ln); err != nil && ctx.Err() == nil {
		emitRaw(ngrokStatus{State: "error", Reason: err.Error()})
		os.Exit(3)
	}
}
