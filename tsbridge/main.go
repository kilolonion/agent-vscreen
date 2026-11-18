// tsbridge: the built-in Tailscale node of Agent-VScreen (1.1.6; 1.1.7 login link fix; 1.1.8 own control servers).
//
// It joins the user's tailnet as its own device (userspace tsnet: no VPN slot, no root, no Tailscale app) and forwards
// HTTP that tailnet peers send to <node>:PORT on to the app's local server at 127.0.0.1:PORT. Every forwarded request
// carries X-VScreen-Tailnet: <secret> (a random value the app gives us at start, so nobody on the phone can fake a
// tailnet request) and X-VScreen-Peer: <who> (the peer's Tailscale login and device, from WhoIs).
//
// The app runs it as a child process and talks over pipes:
//
//	stdout: one JSON object per line whenever the state changes ({"state":"Running","ip":"100.x", ...}).
//	stdin:  commands, one per line: "login" (ask for a fresh login link), "logout", "quit". EOF (the app died) ends this process too.
//
// Configuration comes from the environment so nothing secret shows up in `ps`:
//
//	TSB_DIR (state dir), TSB_HOSTNAME, TSB_PORT, TSB_TARGET (host:port), TSB_SECRET, TSB_AUTHKEY (optional),
//	TSB_CONTROL_URL (optional, e.g. a Headscale server), TSB_DNS (comma separated resolvers of the phone's network).
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/net/dnscache"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

type status struct {
	State    string `json:"state"`
	AuthURL  string `json:"auth_url,omitempty"`
	IP       string `json:"ip,omitempty"`
	DNSName  string `json:"dns_name,omitempty"`
	Tailnet  string `json:"tailnet,omitempty"`
	User     string `json:"user,omitempty"`
	Peers    int    `json:"peers"`
	Online   int    `json:"online"`
	Error    string `json:"error,omitempty"`
	LastPeer string `json:"last_peer,omitempty"`
	// 1.1.7: why a login link is not there yet (control server unreachable, etc.), and how long we have waited.
	Health   string `json:"health,omitempty"`
	WaitingS int    `json:"waiting_s,omitempty"`
	// 1.1.8: the control server this node talks to ("" = Tailscale's own), and the last login error the backend
	// reported (unreachable server, bad address, rejected key ...), so the app can name the real cause.
	Control    string `json:"control,omitempty"`
	LoginError string `json:"login_error,omitempty"`
	// 1.3 beta4: when this node's login (node key) expires, unix seconds (0 = never or unknown), and how old the
	// login link shown to the user is.
	KeyExpiry int64 `json:"key_expiry,omitempty"`
	// 1.3 beta6: "Running" only says the node is logged in. Whether it can actually be reached is what control and
	// the home relay say: ControlOK = the coordination server is in touch; RelayOK = the home DERP is connected;
	// Relay = its region code; Flaps = how often either went down in the last 10 minutes; Problem = the health text.
	ControlOK bool   `json:"control_ok"`
	RelayOK   bool   `json:"relay_ok"`
	Relay     string `json:"relay,omitempty"`
	Flaps     int    `json:"flaps,omitempty"`
	Problem   string `json:"problem,omitempty"`
	DownS     int    `json:"down_s,omitempty"`
	LinkAgeS  int    `json:"link_age_s,omitempty"`
	// 1.3.0: logged in, but the relay has not been reached yet since this start (or since the node came back):
	// "connecting", not "connected" and not "dropped". Before, "Running" showed as connected at once and the relay's
	// 10 s connect timeout then looked like a drop.
	Connecting bool `json:"connecting,omitempty"`
}

// normalizeControlURL turns what a person types ("hs.example.com", "https://hs.example.com/") into the URL tsnet needs.
// Without a scheme tsnet fails with "unsupported protocol scheme" and just sits in NeedsLogin.
func normalizeControlURL(v string) string {
	v = strings.TrimRight(strings.TrimSpace(v), "/")
	if v == "" {
		return ""
	}
	if !strings.Contains(v, "://") {
		v = "https://" + v
	}
	return v
}

// Only reap an orphan of this executable in this node's own working directory.
// A live supervisor owns its child; a competing starter must wait for the file lock.
func orphanBridge(procDir string, exe, stateDir os.FileInfo) bool {
	binary, err := os.Stat(filepath.Join(procDir, "exe"))
	if err != nil || !os.SameFile(binary, exe) {
		return false
	}
	cwd, err := os.Stat(filepath.Join(procDir, "cwd"))
	if err != nil || !os.SameFile(cwd, stateDir) {
		return false
	}
	status, err := os.ReadFile(filepath.Join(procDir, "status"))
	if err != nil {
		return false
	}
	for _, line := range strings.Split(string(status), "\n") {
		if fields := strings.Fields(line); len(fields) == 2 && fields[0] == "PPid:" {
			return fields[1] == "1"
		}
	}
	return false
}

func stopStale(dir string) {
	exe, err := os.Stat("/proc/self/exe")
	if err != nil {
		return
	}
	stateDir, err := os.Stat(dir)
	if err != nil {
		return
	}
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return
	}
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid <= 1 || pid == os.Getpid() {
			continue
		}
		procDir := filepath.Join("/proc", entry.Name())
		if !orphanBridge(procDir, exe, stateDir) {
			continue
		}
		proc, err := os.FindProcess(pid)
		if err != nil {
			continue
		}
		if orphanBridge(procDir, exe, stateDir) && proc.Signal(syscall.SIGTERM) == nil {
			for i := 0; i < 30 && orphanBridge(procDir, exe, stateDir); i++ {
				time.Sleep(100 * time.Millisecond)
			}
			if orphanBridge(procDir, exe, stateDir) {
				_ = proc.Kill()
			}
		}
		_ = proc.Release()
	}
}

// hasSavedLogin: this node has logged in before (a profile is stored), whatever the backend says right now.
func hasSavedLogin(lc interface {
	ProfileStatus(context.Context) (ipn.LoginProfile, []ipn.LoginProfile, error)
}) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cur, _, err := lc.ProfileStatus(ctx)
	return err == nil && cur.ID != "" && cur.UserProfile.LoginName != ""
}

// controlUnreachable: the health notes say the control server could not be reached (not that it refused the login).
func controlUnreachable(health []string) bool {
	for _, h := range health {
		l := strings.ToLower(h)
		for _, w := range []string{"deadline exceeded", "fetch control key", "unable to connect", "coordination server", "timeout", "timed out", "connection refused", "no route", "network is unreachable", "dial tcp", "eof", "tls handshake"} {
			if strings.Contains(l, w) {
				return true
			}
		}
	}
	return false
}

// loginError pulls "The last login error was: X" out of the backend's health messages.
func loginError(health []string) string {
	for _, h := range health {
		if i := strings.Index(h, "last login error was: "); i >= 0 {
			return strings.TrimSpace(h[i+len("last login error was: "):])
		}
	}
	return ""
}

var (
	outMu    sync.Mutex
	last     string
	lastPeer string
	peerMu   sync.Mutex
)

func emit(s status) {
	peerMu.Lock()
	s.LastPeer = lastPeer
	peerMu.Unlock()
	b, _ := json.Marshal(s)
	outMu.Lock()
	defer outMu.Unlock()
	if string(b) == last {
		return
	}
	last = string(b)
	fmt.Fprintln(os.Stdout, string(b))
}

// 1.3: tsnet repeats some lines every few seconds (the login prompt, with its link) and they filled the app's
// diagnostics. Each distinct line is written at most once per 10 minutes, and login links are cut.
var (
	logMu   sync.Mutex
	logSeen = map[string]time.Time{}
	loginRe = regexp.MustCompile(`https?://\S*/(a|register)/\S+`)
)

// 1.3 beta6: the backend's own log is mostly noise, but the lines about links, relays and the control connection are
// what a flapping node needs explained. Those pass (at most 30 a minute), with the time.
var backendKeep = []string{"LinkChange", "home is now", "derphttp", "control: mapRoutine", "control: controlhttp", "netmon", "Rebind", "health(", "magicsock: derp-", "network map", "portmap"}
var backendMu sync.Mutex

// The state directory's lock (see main); a package variable so the file is never collected and closed.
var lockFile *os.File
var backendMin int64
var backendN int

func backendLog(f string, a ...any) {
	line := fmt.Sprintf(f, a...)
	keep := false
	for _, k := range backendKeep {
		if strings.Contains(line, k) {
			keep = true
			break
		}
	}
	if !keep {
		return
	}
	backendMu.Lock()
	m := time.Now().Unix() / 60
	if m != backendMin {
		backendMin, backendN = m, 0
	}
	backendN++
	n := backendN
	backendMu.Unlock()
	if n > 30 {
		return
	}
	fmt.Fprintln(os.Stderr, "ts: "+loginRe.ReplaceAllString(strings.TrimSpace(line), "<login link>"))
}

func userLog(f string, a ...any) {
	line := loginRe.ReplaceAllString(fmt.Sprintf(f, a...), "<login link>")
	logMu.Lock()
	defer logMu.Unlock()
	if t, ok := logSeen[line]; ok && time.Since(t) < 10*time.Minute {
		return
	}
	if len(logSeen) > 500 {
		logSeen = map[string]time.Time{}
	}
	logSeen[line] = time.Now()
	fmt.Fprintln(os.Stderr, line)
}

// 1.3: requests are bounded here, before they reach the app: a body over 1 MiB is refused from its declared length
// (before, about 9 MB were read first), and a request whose headers and body do not arrive within 30 s is dropped.
const maxBody = 1 << 20

func bounded(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ContentLength > maxBody {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Header().Set("Connection", "close")
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			fmt.Fprintf(w, `{"ok":false,"code":"BODY_TOO_LARGE","error":"request body over %d bytes"}`, maxBody)
			return
		}
		if r.Body != nil && r.Body != http.NoBody && r.ContentLength != 0 {
			// The body must arrive within 30 s; once it has, the deadline is lifted (a command may run for minutes).
			rc := http.NewResponseController(w)
			_ = rc.SetReadDeadline(time.Now().Add(30 * time.Second))
			r.Body = &untilEOF{ReadCloser: http.MaxBytesReader(w, r.Body, maxBody), done: func() { _ = rc.SetReadDeadline(time.Time{}) }}
		}
		h.ServeHTTP(w, r)
	})
}

type untilEOF struct {
	io.ReadCloser
	done func()
	once sync.Once
}

func (u *untilEOF) Read(p []byte) (int, error) {
	n, err := u.ReadCloser.Read(p)
	if err == io.EOF {
		u.once.Do(u.done)
	}
	return n, err
}

func env(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

// Android has no /etc/resolv.conf: Go's own resolver would ask 127.0.0.1:53 and fail. Use the resolvers the app read
// from the phone’s networks (public defaults only for the standard service when Android supplies none).
func setupDNS(servers []string) {
	r := newResolver(servers)
	net.DefaultResolver = r
	// controlclient copies this field when tsnet starts. Its default is a
	// separate net.Resolver and does not pick up a new net.DefaultResolver.
	dnscache.Get().Forward = r
}

// Apps targeting Android 11+ may not list interfaces through netlink; net.Interfaces can fail there. Fall back to one
// synthetic interface carrying the address the kernel would use for the internet (a UDP "connect" sends nothing).
func setupInterfaces() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := net.Interfaces()
		if err == nil && len(ifs) > 0 {
			out := make([]netmon.Interface, 0, len(ifs))
			for i := range ifs {
				out = append(out, netmon.Interface{Interface: &ifs[i]})
			}
			return out, nil
		}
		c, derr := net.Dial("udp4", "8.8.8.8:53")
		if derr != nil {
			return nil, errors.Join(err, derr)
		}
		ip := c.LocalAddr().(*net.UDPAddr).IP
		c.Close()
		iface := &net.Interface{Index: 1, MTU: 1500, Name: "wlan0", Flags: net.FlagUp | net.FlagRunning | net.FlagMulticast}
		return []netmon.Interface{{Interface: iface, AltAddrs: []net.Addr{&net.IPNet{IP: ip, Mask: net.CIDRMask(24, 32)}}}}, nil
	})
}

func main() {
	// 1.3 beta8: this node lives and dies with the app. When the app's process is gone (killed by the system,
	// crashed, updated), the bridge must not stay behind holding the node: a new app process starts a new bridge
	// with the same node key, and two clients of one key throw each other off the relay for minutes. The kernel
	// sends SIGTERM when the parent goes; the parent id is also watched in case that signal is lost.
	ppid := os.Getppid()
	_, _, _ = syscall.RawSyscall(syscall.SYS_PRCTL, syscall.PR_SET_PDEATHSIG, uintptr(syscall.SIGTERM), 0)
	if os.Getppid() != ppid || ppid == 1 {
		fmt.Fprintln(os.Stderr, "quit: the app was already gone at start")
		os.Exit(4)
	}
	setupDNS(dnsServerList(os.Getenv("TSB_DNS"), normalizeControlURL(os.Getenv("TSB_CONTROL_URL")) != ""))
	setupInterfaces()
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true") // no log upload to Tailscale

	port := env("TSB_PORT", "18765")
	target := env("TSB_TARGET", "127.0.0.1:"+port)
	secret := os.Getenv("TSB_SECRET")
	if secret == "" {
		fmt.Fprintln(os.Stderr, "TSB_SECRET missing")
		os.Exit(2)
	}
	srv := &tsnet.Server{
		Dir:        env("TSB_DIR", "./ts"),
		Hostname:   env("TSB_HOSTNAME", "agent-vscreen"),
		AuthKey:    os.Getenv("TSB_AUTHKEY"),
		ControlURL: normalizeControlURL(os.Getenv("TSB_CONTROL_URL")),
		Logf:       backendLog,
		UserLogf:   userLog,
	}
	defer srv.Close()

	// 1.3 beta6: one node per identity. Two bridges on one state directory (the same node key) take turns on the
	// control server and the relay, so each is online for a while and then gone. The second one waits here until the
	// first has exited, and gives up after 20 s rather than fight it.
	// 1.3 beta8: a bridge left behind by an earlier app process (its parent gone) is ended first, not waited for.
	stopStale(srv.Dir)
	if f, err := os.OpenFile(filepath.Join(srv.Dir, ".bridge.lock"), os.O_CREATE|os.O_RDWR, 0o600); err == nil {
		deadline := time.Now().Add(20 * time.Second)
		for {
			if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err == nil {
				break
			}
			if time.Now().After(deadline) {
				emit(status{State: "Error", Error: "another built-in Tailscale node is still running on this phone"})
				os.Exit(3)
			}
			time.Sleep(500 * time.Millisecond)
		}
		lockFile = f // kept referenced: the lock lasts as long as this process
	} else {
		emit(status{State: "Error", Error: "unable to lock node state: " + err.Error()})
		os.Exit(3)
	}

	emit(status{State: "Starting"})
	if err := srv.Start(); err != nil {
		emit(status{State: "Error", Error: err.Error()})
		os.Exit(1)
	}
	lc, err := srv.LocalClient()
	if err != nil {
		emit(status{State: "Error", Error: err.Error()})
		os.Exit(1)
	}

	ln, err := srv.Listen("tcp", ":"+port)
	if err != nil {
		emit(status{State: "Error", Error: "listen: " + err.Error()})
		os.Exit(1)
	}
	tu, _ := url.Parse("http://" + target)
	rp := httputil.NewSingleHostReverseProxy(tu)
	rp.FlushInterval = -1
	base := rp.Director
	rp.Director = func(r *http.Request) {
		host := r.Host
		base(r)
		r.Host = host // the app checks that the caller named this node
		r.Header.Del("X-VScreen-Tailnet")
		r.Header.Del("X-VScreen-Peer")
		r.Header.Set("X-VScreen-Tailnet", secret)
		who := r.RemoteAddr
		if wi, err := lc.WhoIs(r.Context(), r.RemoteAddr); err == nil && wi != nil {
			name := ""
			if wi.UserProfile != nil {
				name = wi.UserProfile.LoginName
			}
			if wi.Node != nil {
				name += " @ " + strings.TrimSuffix(wi.Node.ComputedName, ".")
			}
			if name != "" {
				who = name
			}
		}
		r.Header.Set("X-VScreen-Peer", who)
		peerMu.Lock()
		lastPeer = who
		peerMu.Unlock()
	}
	rp.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusBadGateway)
		fmt.Fprintf(w, `{"ok":false,"code":"APP_NOT_ANSWERING","error":"Agent-VScreen on the phone did not answer (%s): open the app once / 手机上的 Agent-VScreen 没有回应：打开一次 App"}`, strings.ReplaceAll(err.Error(), `"`, `'`))
	}
	go func() {
		_ = (&http.Server{Handler: bounded(rp), ReadHeaderTimeout: 30 * time.Second}).Serve(ln)
	}()

	// Commands from the app; EOF = the app is gone.
	quit := make(chan struct{})
	var quitOnce sync.Once
	stop := func(why string) { quitOnce.Do(func() { fmt.Fprintln(os.Stderr, "quit:", why); close(quit) }) }
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	go func() { s := <-sigs; stop("signal " + s.String()) }()
	go func() {
		for range time.Tick(2 * time.Second) {
			if os.Getppid() != ppid {
				stop("the app process is gone")
				return
			}
		}
	}()
	// 1.1.7: tsnet asks the control server for a login link only once, and only if the backend is already in NeedsLogin
	// at that instant. When it is still NoState then (slow storage, an old state file, an expired node) or after a
	// logout or an expired link, the node sits in NeedsLogin with no link forever. So we ask ourselves: whenever the
	// state is NeedsLogin without a link, at most every 20 s, and right away on a "login" command from the app.
	var loginMu sync.Mutex
	var lastKick time.Time
	kick := func(force bool) {
		loginMu.Lock()
		if !force && time.Since(lastKick) < 20*time.Second {
			loginMu.Unlock()
			return
		}
		lastKick = time.Now()
		loginMu.Unlock()
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			if err := lc.StartLoginInteractive(ctx); err != nil {
				fmt.Fprintln(os.Stderr, "StartLoginInteractive:", err)
			}
		}()
	}
	go func() {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			switch strings.TrimSpace(sc.Text()) {
			case "logout":
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				_ = lc.Logout(ctx)
				cancel()
			case "login":
				kick(true)
			case "quit":
				stop("asked by the app")
				return
			}
		}
		stop(fmt.Sprint("the app closed our input ", sc.Err()))
	}()

	var needSince time.Time
	// 1.3 beta6: the real link state and its changes.
	var wasUp = false
	var everUp bool
	var runningSince time.Time
	var downSince time.Time
	var flaps []time.Time
	// 1.3 beta4: a login link is not good forever. One left open for 10 minutes (the user went away, the browser tab
	// was lost) is replaced with a fresh one, so the button in the app never opens a dead page.
	var link string
	var linkSince time.Time
	var keptLogin bool
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		st, err := lc.Status(ctx)
		cancel()
		if err != nil {
			emit(status{State: "Error", Error: err.Error()})
		} else {
			s := status{State: st.BackendState, AuthURL: st.AuthURL, Control: srv.ControlURL}
			if st.BackendState != "Running" {
				s.LoginError = loginError(st.Health)
				// 1.3.0: why it is not connected, in every state (before: only in NeedsLogin, so a node waiting for
				// the control server with a saved login said nothing for minutes).
				s.Health = strings.Join(st.Health, "; ")
				runningSince, wasUp, everUp = time.Time{}, false, false
			}
			if st.BackendState == "NeedsLogin" && st.AuthURL == "" {
				if needSince.IsZero() {
					needSince = time.Now()
				}
				s.WaitingS = int(time.Since(needSince) / time.Second)
				s.Health = strings.Join(st.Health, "; ")
				// 1.3 beta7: a node that has a saved login and lost the control server (slow or blocked network, a
				// proxy, the first seconds after a start) also reports NeedsLogin without a link. Starting an
				// interactive login then threw the saved login away, and the user was asked to log in again although
				// nothing had expired. Keep it: say "reconnecting" and wait for the server; ask for a new login only
				// when the server is answering and the node still wants one after a minute.
				saved := hasSavedLogin(lc)
				if saved && (controlUnreachable(st.Health) || time.Since(needSince) < time.Minute) {
					s.State = "Reconnecting"
					s.Problem = strings.Join(st.Health, "; ")
					if !keptLogin {
						keptLogin = true
						fmt.Fprintf(os.Stderr, "needs login but has a saved login: keeping it, waiting for the server (%s)\n", s.Problem)
					}
				} else if srv.AuthKey == "" {
					if saved {
						fmt.Fprintf(os.Stderr, "saved login refused by the server after %ds: asking for a new login\n", s.WaitingS)
					}
					kick(false)
				}
			} else {
				needSince = time.Time{}
				keptLogin = false
			}
			if st.BackendState == "NeedsLogin" && st.AuthURL != "" {
				if st.AuthURL != link {
					link, linkSince = st.AuthURL, time.Now()
				}
				s.LinkAgeS = int(time.Since(linkSince) / time.Second)
				if time.Since(linkSince) > 10*time.Minute && srv.AuthKey == "" {
					kick(true)
					linkSince = time.Now()
				}
			} else {
				link = ""
			}
			if len(st.TailscaleIPs) > 0 {
				for _, ip := range st.TailscaleIPs {
					if ip.Is4() {
						s.IP = ip.String()
					}
				}
			}
			if st.Self != nil {
				s.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
				if st.Self.KeyExpiry != nil && !st.Self.KeyExpiry.IsZero() {
					s.KeyExpiry = st.Self.KeyExpiry.Unix()
				}
				if u, ok := st.User[st.Self.UserID]; ok {
					s.User = u.LoginName
				}
			}
			if st.CurrentTailnet != nil {
				s.Tailnet = st.CurrentTailnet.Name
			}
			if st.BackendState == "Running" {
				if runningSince.IsZero() {
					runningSince = time.Now()
				}
				s.ControlOK, s.RelayOK = true, true
				var probs []string
				for _, h := range st.Health {
					l := strings.ToLower(h)
					switch {
					case strings.Contains(l, "coordination server"):
						s.ControlOK = false
						probs = append(probs, h)
					case strings.Contains(l, "relay server"):
						s.RelayOK = false
						probs = append(probs, h)
					}
				}
				if st.Self != nil {
					s.Relay = st.Self.Relay
					if s.Relay == "" {
						s.RelayOK = false
					}
				}
				// 1.3.0: the backend warns about a missing relay only after a 10 s grace, so in the first seconds after
				// "Running" no warning does not mean connected. Until then (and until it has been up once) it is
				// "connecting".
				if time.Since(runningSince) < 12*time.Second {
					s.RelayOK = false
				}
				s.Problem = strings.Join(probs, "; ")
				up := s.ControlOK && s.RelayOK
				if up != wasUp {
					if !up && everUp {
						downSince = time.Now()
						flaps = append(flaps, time.Now())
						fmt.Fprintf(os.Stderr, "link down: control_ok=%v relay=%q relay_ok=%v %s\n", s.ControlOK, s.Relay, s.RelayOK, s.Problem)
					} else if up && !everUp {
						fmt.Fprintf(os.Stderr, "link up %ds after login state Running, relay=%q\n", int(time.Since(runningSince)/time.Second), s.Relay)
					} else if up {
						fmt.Fprintf(os.Stderr, "link up again after %ds, relay=%q\n", int(time.Since(downSince)/time.Second), s.Relay)
					}
					if up {
						everUp = true
					}
					wasUp = up
				}
				s.Connecting = !everUp
				if !up && everUp {
					s.DownS = int(time.Since(downSince) / time.Second)
				}
				cut := time.Now().Add(-10 * time.Minute)
				for len(flaps) > 0 && flaps[0].Before(cut) {
					flaps = flaps[1:]
				}
				s.Flaps = len(flaps)
			}
			for _, p := range st.Peer {
				s.Peers++
				if p.Online {
					s.Online++
				}
			}
			emit(s)
		}
		select {
		case <-quit:
			return
		case <-tick.C:
		}
	}
}
