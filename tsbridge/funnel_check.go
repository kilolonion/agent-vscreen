package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tailcfg/nodecap"
)

// ── Section B1: structured Funnel prerequisite check (`funnel-check`) ───────

type funnelCheckClient interface {
	Status(ctx context.Context) (*ipnstate.Status, error)
	QueryFeature(ctx context.Context, feature string) (*tailcfg.QueryFeatureResponse, error)
}

type funnelFeatureCheck struct {
	OK         bool   `json:"ok"`
	Text       string `json:"text,omitempty"`
	URL        string `json:"url,omitempty"`
	ShouldWait bool   `json:"should_wait,omitempty"`
	Error      string `json:"error,omitempty"`
}

type funnelCheckReply struct {
	Type     string             `json:"type"`
	Seq      string             `json:"seq,omitempty"`
	Official bool               `json:"official"`
	LoggedIn bool               `json:"logged_in"`
	DNSName  string             `json:"dns_name,omitempty"`
	HTTPS    funnelFeatureCheck `json:"https"`
	Funnel   funnelFeatureCheck `json:"funnel"`
	Port443  funnelFeatureCheck `json:"port443"`
	Error    string             `json:"error,omitempty"`
}

func evaluateFunnelCheck(ctx context.Context, lc funnelCheckClient, controlURL, seq string) funnelCheckReply {
	reply := funnelCheckReply{
		Type:     "funnel_check",
		Seq:      seq,
		Official: strings.TrimSpace(controlURL) == "",
	}
	st, err := lc.Status(ctx)
	if err != nil {
		reply.Error = err.Error()
		return reply
	}
	if st == nil {
		reply.Error = "nil tailscale status"
		return reply
	}
	if st.Self != nil {
		reply.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
	}
	reply.LoggedIn = st.BackendState == "Running" && len(st.TailscaleIPs) > 0 && st.Self != nil
	if !reply.Official || !reply.LoggedIn {
		return reply
	}

	// 1) HTTPS certificates: QueryFeature("serve") + Status.CertDomains / nodecap.HTTPS
	httpsCap := len(st.CertDomains) > 0 || st.Self.HasCap(nodecap.HTTPS)
	if qf, qerr := lc.QueryFeature(ctx, "serve"); qerr == nil && qf != nil {
		reply.HTTPS.OK = qf.Complete || httpsCap
		if !reply.HTTPS.OK {
			reply.HTTPS.Text = strings.TrimSpace(qf.Text)
			reply.HTTPS.URL = strings.TrimSpace(qf.URL)
			reply.HTTPS.ShouldWait = qf.ShouldWait
		}
	} else {
		reply.HTTPS.OK = httpsCap
		if !reply.HTTPS.OK {
			reply.HTTPS.Text = "HTTPS certificates must be enabled in Tailscale Admin."
			if qerr != nil {
				reply.HTTPS.Error = qerr.Error()
			}
		}
	}

	// 2) Funnel ACL nodeAttr: QueryFeature("funnel") + nodecap.Funnel
	funnelCap := st.Self.HasCap(nodecap.Funnel)
	if qf, qerr := lc.QueryFeature(ctx, "funnel"); qerr == nil && qf != nil {
		reply.Funnel.OK = funnelCap || qf.Complete
		if !reply.Funnel.OK {
			reply.Funnel.Text = strings.TrimSpace(qf.Text)
			reply.Funnel.URL = strings.TrimSpace(qf.URL)
			reply.Funnel.ShouldWait = qf.ShouldWait
		}
	} else {
		reply.Funnel.OK = funnelCap
		if !reply.Funnel.OK {
			if nerr := ipn.NodeCanFunnel(st.Self); nerr != nil {
				reply.Funnel.Text = nerr.Error()
			} else {
				reply.Funnel.Text = `Funnel not available; "funnel" node attribute not set.`
			}
			if qerr != nil {
				reply.Funnel.Error = qerr.Error()
			}
		}
	}

	// 3) Port 443 allowed for Funnel: ipn.CheckFunnelPort(443, st.Self)
	if perr := ipn.CheckFunnelPort(443, st.Self); perr == nil {
		reply.Port443.OK = true
	} else {
		reply.Port443.OK = false
		reply.Port443.Text = perr.Error()
		reply.Port443.Error = perr.Error()
	}

	return reply
}

// ── Section A.3: active TLS certificate prefetch before reporting funnel="on" ─

type certPairClient interface {
	CertPair(ctx context.Context, domain string) (certPEM, keyPEM []byte, err error)
}

func fetchAndVerifyFunnelCert(ctx context.Context, lc certPairClient, domain string) error {
	domain = strings.TrimSuffix(strings.TrimSpace(domain), ".")
	if domain == "" {
		return errors.New("tailscale DNS name is empty; cannot obtain HTTPS certificate")
	}
	certPEM, keyPEM, err := lc.CertPair(ctx, domain)
	if err != nil {
		return err
	}
	if _, err := tls.X509KeyPair(certPEM, keyPEM); err != nil {
		return fmt.Errorf("invalid TLS certificate pair for %s: %w", domain, err)
	}
	return nil
}

// ── Section I: real public client source extraction from FunnelConn ─────────

type publicSrcKey struct{}

type netConnUnwrapper interface {
	NetConn() net.Conn
}

// extractPublicSource inspects the accepted connection (unwrapping *tls.Conn or wrappers)
// to recover the true public client IP from *ipn.FunnelConn.Src. Connections that did not
// arrive via Tailscale Funnel (e.g. direct in-tailnet :443 access) are tagged "tailnet:<ip>".
func extractPublicSource(c net.Conn) string {
	cur := c
	for i := 0; i < 8 && cur != nil; i++ {
		if fc, ok := cur.(*ipn.FunnelConn); ok {
			if fc.Src.IsValid() {
				return fc.Src.Addr().Unmap().String()
			}
			break
		}
		u, ok := cur.(netConnUnwrapper)
		if !ok {
			break
		}
		next := u.NetConn()
		if next == nil || next == cur {
			break
		}
		cur = next
	}
	return "tailnet:" + remoteAddrIP(c)
}

func remoteAddrIP(c net.Conn) string {
	if c == nil || c.RemoteAddr() == nil {
		return "unknown"
	}
	raw := strings.TrimSpace(c.RemoteAddr().String())
	if raw == "" {
		return "unknown"
	}
	if ap, err := netip.ParseAddrPort(raw); err == nil {
		return ap.Addr().Unmap().String()
	}
	if host, _, err := net.SplitHostPort(raw); err == nil {
		if a, aerr := netip.ParseAddr(host); aerr == nil {
			return a.Unmap().String()
		}
		if host != "" {
			return host
		}
	}
	if a, err := netip.ParseAddr(raw); err == nil {
		return a.Unmap().String()
	}
	return raw
}
