package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tailcfg/nodecap"
	"tailscale.com/tsweb"
)

// ── Section A: LocalAPI cert/ route handler tests ───────────────────────────

type fakeCertFetcher struct {
	gotDomain      string
	gotMinValidity time.Duration
	pair           *ipnlocal.TLSCertKeyPair
	err            error
}

func (f *fakeCertFetcher) GetCertPEMWithValidity(ctx context.Context, domain string, minValidity time.Duration) (*ipnlocal.TLSCertKeyPair, error) {
	f.gotDomain = domain
	f.gotMinValidity = minValidity
	return f.pair, f.err
}

type fakeHTTPStatusErr struct {
	resp tsweb.HTTPError
}

func (e fakeHTTPStatusErr) Error() string             { return e.resp.Msg }
func (e fakeHTTPStatusErr) HTTPStatus() tsweb.HTTPError { return e.resp }

func TestServeCertHandlerPermissionsAndTypes(t *testing.T) {
	pair := &ipnlocal.TLSCertKeyPair{
		CertPEM: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: []byte("dummy-cert")}),
		KeyPEM:  pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: []byte("dummy-key")}),
	}

	t.Run("denied_without_permissions", func(t *testing.T) {
		f := &fakeCertFetcher{pair: pair}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.ts.net", nil)
		serveCertHandler(false, false, f, rec, req)
		if rec.Code != http.StatusForbidden {
			t.Fatalf("code=%d, want 403", rec.Code)
		}
		if f.gotDomain != "" {
			t.Fatalf("fetcher should not be called when unauthorized")
		}
	})

	t.Run("allowed_with_permit_cert_default_cert_type", func(t *testing.T) {
		f := &fakeCertFetcher{pair: pair}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.tailnet.ts.net?min_validity=24h", nil)
		serveCertHandler(false, true, f, rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("code=%d body=%q", rec.Code, rec.Body.String())
		}
		if f.gotDomain != "phone.tailnet.ts.net" || f.gotMinValidity != 24*time.Hour {
			t.Fatalf("got domain=%q minValidity=%v", f.gotDomain, f.gotMinValidity)
		}
		if rec.Body.String() != string(pair.CertPEM) {
			t.Fatalf("body=%q, want CertPEM", rec.Body.String())
		}
	})

	t.Run("pair_returns_key_then_cert", func(t *testing.T) {
		f := &fakeCertFetcher{pair: pair}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.tailnet.ts.net?type=pair", nil)
		serveCertHandler(true, false, f, rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("code=%d", rec.Code)
		}
		want := string(pair.KeyPEM) + string(pair.CertPEM)
		if rec.Body.String() != want {
			t.Fatalf("pair body=%q, want key+cert", rec.Body.String())
		}
	})

	t.Run("key_returns_only_key", func(t *testing.T) {
		f := &fakeCertFetcher{pair: pair}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.tailnet.ts.net?type=key", nil)
		serveCertHandler(true, false, f, rec, req)
		if rec.Code != http.StatusOK || rec.Body.String() != string(pair.KeyPEM) {
			t.Fatalf("code=%d body=%q", rec.Code, rec.Body.String())
		}
	})

	t.Run("invalid_min_validity_400", func(t *testing.T) {
		f := &fakeCertFetcher{pair: pair}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.tailnet.ts.net?min_validity=notaduration", nil)
		serveCertHandler(true, false, f, rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("code=%d, want 400", rec.Code)
		}
	})

	t.Run("invalid_type_400", func(t *testing.T) {
		f := &fakeCertFetcher{pair: pair}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.tailnet.ts.net?type=bogus", nil)
		serveCertHandler(true, false, f, rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("code=%d, want 400", rec.Code)
		}
	})

	t.Run("wrong_path_prefix_500", func(t *testing.T) {
		f := &fakeCertFetcher{pair: pair}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/wrong/phone.ts.net", nil)
		serveCertHandler(true, false, f, rec, req)
		if rec.Code != http.StatusInternalServerError {
			t.Fatalf("code=%d, want 500", rec.Code)
		}
	})

	t.Run("propagates_tsweb_http_statuser_and_headers", func(t *testing.T) {
		hdr := http.Header{"Retry-After": []string{"60"}}
		f := &fakeCertFetcher{err: fakeHTTPStatusErr{resp: tsweb.HTTPError{Code: http.StatusTooManyRequests, Msg: "rate limited", Header: hdr}}}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.ts.net?type=pair", nil)
		serveCertHandler(true, false, f, rec, req)
		if rec.Code != http.StatusTooManyRequests {
			t.Fatalf("code=%d, want 429", rec.Code)
		}
		if rec.Header().Get("Retry-After") != "60" {
			t.Fatalf("Retry-After=%q, want 60", rec.Header().Get("Retry-After"))
		}
	})

	t.Run("generic_error_returns_500", func(t *testing.T) {
		f := &fakeCertFetcher{err: errors.New("acme failed")}
		rec := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/localapi/v0/cert/phone.ts.net", nil)
		serveCertHandler(true, false, f, rec, req)
		if rec.Code != http.StatusInternalServerError || !strings.Contains(rec.Body.String(), "acme failed") {
			t.Fatalf("code=%d body=%q", rec.Code, rec.Body.String())
		}
	})
}

// ── Section A.3: active cert prefetch verification ──────────────────────────

type fakeCertPairClient struct {
	certPEM []byte
	keyPEM  []byte
	err     error
}

func (f *fakeCertPairClient) CertPair(ctx context.Context, domain string) ([]byte, []byte, error) {
	return f.certPEM, f.keyPEM, f.err
}

func generateTestCertPEM(t *testing.T) (certPEM, keyPEM []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "phone.tailnet.ts.net"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		DNSNames:     []string{"phone.tailnet.ts.net"},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	return certPEM, keyPEM
}

func TestFetchAndVerifyFunnelCert(t *testing.T) {
	certPEM, keyPEM := generateTestCertPEM(t)

	if err := fetchAndVerifyFunnelCert(context.Background(), &fakeCertPairClient{certPEM: certPEM, keyPEM: keyPEM}, "phone.tailnet.ts.net."); err != nil {
		t.Fatalf("valid cert failed: %v", err)
	}
	if err := fetchAndVerifyFunnelCert(context.Background(), &fakeCertPairClient{}, ""); err == nil {
		t.Fatalf("expected error for empty domain")
	}
	if err := fetchAndVerifyFunnelCert(context.Background(), &fakeCertPairClient{err: errors.New("404")}, "phone.tailnet.ts.net"); err == nil {
		t.Fatalf("expected error propagation")
	}
	if err := fetchAndVerifyFunnelCert(context.Background(), &fakeCertPairClient{certPEM: []byte("bad"), keyPEM: []byte("bad")}, "phone.tailnet.ts.net"); err == nil {
		t.Fatalf("expected invalid PEM error")
	}
}

// ── Section B1: funnel-check evaluation tests ───────────────────────────────

type fakeFunnelCheckClient struct {
	st        *ipnstate.Status
	stErr     error
	features  map[string]*tailcfg.QueryFeatureResponse
	featErrs  map[string]error
}

func (f *fakeFunnelCheckClient) Status(ctx context.Context) (*ipnstate.Status, error) {
	return f.st, f.stErr
}

func (f *fakeFunnelCheckClient) QueryFeature(ctx context.Context, feature string) (*tailcfg.QueryFeatureResponse, error) {
	if err := f.featErrs[feature]; err != nil {
		return nil, err
	}
	return f.features[feature], nil
}

func TestEvaluateFunnelCheck(t *testing.T) {
	t.Run("headscale_rejected", func(t *testing.T) {
		c := &fakeFunnelCheckClient{
			st: &ipnstate.Status{
				BackendState: "Running",
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
				Self:         &ipnstate.PeerStatus{DNSName: "phone.hs.example."},
			},
		}
		r := evaluateFunnelCheck(context.Background(), c, "https://hs.example.com", "1")
		if r.Type != "funnel_check" || r.Seq != "1" || r.Official || !r.LoggedIn {
			t.Fatalf("unexpected reply: %+v", r)
		}
	})

	t.Run("all_satisfied", func(t *testing.T) {
		caps := tailcfg.NodeCapMap{
			nodecap.HTTPS:                                                 nil,
			nodecap.Funnel:                                                nil,
			nodecap.Cap("https://tailscale.com/cap/funnel-ports?ports=443,8443,10000"): nil,
		}
		c := &fakeFunnelCheckClient{
			st: &ipnstate.Status{
				BackendState: "Running",
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
				CertDomains:  []string{"phone.tailnet.ts.net"},
				Self:         &ipnstate.PeerStatus{DNSName: "phone.tailnet.ts.net.", CapMap: caps},
			},
			features: map[string]*tailcfg.QueryFeatureResponse{
				"serve":  {Complete: true},
				"funnel": {Complete: true},
			},
		}
		r := evaluateFunnelCheck(context.Background(), c, "", "2")
		if !r.Official || !r.LoggedIn || !r.HTTPS.OK || !r.Funnel.OK || !r.Port443.OK {
			t.Fatalf("expected all OK: %+v", r)
		}
		if r.DNSName != "phone.tailnet.ts.net" {
			t.Fatalf("dns=%q", r.DNSName)
		}
	})

	t.Run("missing_https_and_funnel_returns_query_feature_urls", func(t *testing.T) {
		c := &fakeFunnelCheckClient{
			st: &ipnstate.Status{
				BackendState: "Running",
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
				Self:         &ipnstate.PeerStatus{DNSName: "phone.tailnet.ts.net.", CapMap: tailcfg.NodeCapMap{}},
			},
			features: map[string]*tailcfg.QueryFeatureResponse{
				"serve":  {Complete: false, Text: "Enable HTTPS", URL: "https://login.tailscale.com/f/serve?node=abc", ShouldWait: true},
				"funnel": {Complete: false, Text: "Enable Funnel", URL: "https://login.tailscale.com/f/funnel?node=abc", ShouldWait: true},
			},
		}
		r := evaluateFunnelCheck(context.Background(), c, "", "3")
		if r.HTTPS.OK || r.HTTPS.URL != "https://login.tailscale.com/f/serve?node=abc" || !r.HTTPS.ShouldWait {
			t.Fatalf("https=%+v", r.HTTPS)
		}
		if r.Funnel.OK || r.Funnel.URL != "https://login.tailscale.com/f/funnel?node=abc" || !r.Funnel.ShouldWait {
			t.Fatalf("funnel=%+v", r.Funnel)
		}
		if r.Port443.OK || !strings.Contains(r.Port443.Text, "443") {
			t.Fatalf("port443=%+v", r.Port443)
		}
	})

	t.Run("query_feature_error_falls_back_to_caps", func(t *testing.T) {
		caps := tailcfg.NodeCapMap{
			nodecap.HTTPS:                                               nil,
			nodecap.Funnel:                                              nil,
			nodecap.Cap("https://tailscale.com/cap/funnel-ports?ports=8443"): nil,
		}
		c := &fakeFunnelCheckClient{
			st: &ipnstate.Status{
				BackendState: "Running",
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
				Self:         &ipnstate.PeerStatus{DNSName: "phone.tailnet.ts.net.", CapMap: caps},
			},
			featErrs: map[string]error{
				"serve":  errors.New("offline"),
				"funnel": errors.New("offline"),
			},
		}
		r := evaluateFunnelCheck(context.Background(), c, "", "4")
		if !r.HTTPS.OK || !r.Funnel.OK {
			t.Fatalf("caps should mark https and funnel ok even if QueryFeature errors: %+v", r)
		}
		if r.Port443.OK || !strings.Contains(r.Port443.Text, "port 443 is not allowed") {
			t.Fatalf("expected port 443 disallowed: %+v", r.Port443)
		}
	})
}

// ── Section I: FunnelConn source extraction and spoofed header rewriting ────

type dummyAddr string

func (a dummyAddr) Network() string { return "tcp" }
func (a dummyAddr) String() string  { return string(a) }

type dummyConn struct {
	net.Conn
	remote net.Addr
}

func (d *dummyConn) RemoteAddr() net.Addr { return d.remote }

type wrappedTLSConn struct {
	net.Conn
	inner net.Conn
}

func (w *wrappedTLSConn) NetConn() net.Conn { return w.inner }

func TestExtractPublicSourceAndProxyHeaderOverwrite(t *testing.T) {
	// 1) FunnelConn wrapped inside TLS conn: must use FunnelConn.Src (not relay RemoteAddr).
	fc := &ipn.FunnelConn{
		Conn: &dummyConn{remote: dummyAddr("100.64.99.99:443")},
		Src:  netip.MustParseAddrPort("203.0.113.42:54321"),
	}
	wrapped := &wrappedTLSConn{Conn: fc, inner: fc}
	if got := extractPublicSource(wrapped); got != "203.0.113.42" {
		t.Fatalf("got %q, want 203.0.113.42", got)
	}

	// 2) IPv6 FunnelConn.
	fc6 := &ipn.FunnelConn{
		Conn: &dummyConn{remote: dummyAddr("100.64.99.99:443")},
		Src:  netip.MustParseAddrPort("[2001:db8:abcd:1234::7]:51234"),
	}
	if got := extractPublicSource(&wrappedTLSConn{Conn: fc6, inner: fc6}); got != "2001:db8:abcd:1234::7" {
		t.Fatalf("got %q, want 2001:db8:abcd:1234::7", got)
	}

	// 3) Direct in-tailnet connection (not FunnelConn) -> "tailnet:<ip>".
	direct := &dummyConn{remote: dummyAddr("100.75.1.2:41234")}
	if got := extractPublicSource(&wrappedTLSConn{Conn: direct, inner: direct}); got != "tailnet:100.75.1.2" {
		t.Fatalf("got %q, want tailnet:100.75.1.2", got)
	}

	// 4) preparePublicProxyRequest strips spoofed X-AVS-Public-Src and writes verified ConnContext source.
	target, _ := url.Parse("http://127.0.0.1:18785")
	rp := httputil.NewSingleHostReverseProxy(target)
	ctx := context.WithValue(context.Background(), publicSrcKey{}, "203.0.113.42")
	req, err := http.NewRequestWithContext(ctx, "POST", "https://phone.tailnet.ts.net/mcp", strings.NewReader(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Host = "phone.tailnet.ts.net"
	req.Header.Set("X-AVS-Public-Src", "198.51.100.99")
	req.Header.Add("x-avs-public-src", "192.0.2.1")
	req.Header.Set("X-VScreen-Tailnet", "spoof")
	req.Header.Set("X-Forwarded-For", "198.51.100.99")

	preparePublicProxyRequest(req, rp.Director, "bridge-sec")

	if got := req.Header.Values("X-AVS-Public-Src"); len(got) != 1 || got[0] != "203.0.113.42" {
		t.Fatalf("X-AVS-Public-Src = %v, want [203.0.113.42]", got)
	}
	if got := req.Header.Get("X-AVS-Public"); got != "bridge-sec" {
		t.Fatalf("X-AVS-Public = %q, want bridge-sec", got)
	}
	if got := req.Header.Get("X-VScreen-Tailnet"); got != "" {
		t.Fatalf("X-VScreen-Tailnet must be stripped, got %q", got)
	}
}
