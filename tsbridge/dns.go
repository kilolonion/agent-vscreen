package main

// 1.4.1-beta1: DNS that survives a proxy VPN.
//
// Android has no /etc/resolv.conf, so the bridge brings its own resolvers: the ones the app read from the phone's
// networks, with public defaults for standard control when Android supplies none. Before, they were tried one after another by "dialing" each: a UDP dial never fails
// (nothing is sent), so the first server was the only one ever asked. With a proxy VPN such as FlClash, the active
// network's DNS is the VPN's own (172.19.0.2 and the like); when Agent-VScreen is bypassed in the proxy, its
// packets never reach that address, every lookup timed out, and the custom control server could not be resolved
// ("fetch control key … context canceled", then "no DNS fallback candidates remain").
//
// Phone resolvers are queried concurrently. A positive answer can supersede a fast negative from another network.
// Custom control servers use the phone's DNS configuration without appending hard-coded public resolvers.

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func dnsServerList(fromApp string, customControl bool) []string {
	seen := map[string]bool{}
	var out []string
	add := func(s string) {
		s = strings.TrimSpace(strings.Trim(strings.TrimSpace(s), "[]"))
		if _, err := netip.ParseAddr(s); err != nil {
			return
		}
		hp := net.JoinHostPort(s, "53")
		if !seen[hp] {
			seen[hp] = true
			out = append(out, hp)
		}
	}
	for _, s := range strings.Split(fromApp, ",") {
		add(s)
	}
	if len(out) > 0 || customControl {
		return out
	}
	for _, s := range []string{"223.5.5.5", "1.1.1.1", "8.8.8.8"} {
		add(s)
	}
	return out
}

func newResolver(servers []string) *net.Resolver {
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			if len(servers) == 0 {
				return nil, errors.New("no DNS servers supplied by Android for the custom control server")
			}
			if strings.HasPrefix(network, "udp") {
				return newFanoutConn(servers)
			}
			d := net.Dialer{Timeout: 3 * time.Second}
			var err error
			for _, s := range servers {
				var c net.Conn
				if c, err = d.DialContext(ctx, network, s); err == nil {
					return c, nil
				}
			}
			return nil, err
		},
	}
}

// fanoutConn looks like one connected UDP socket to Go's resolver: Write sends the query to every server, Read
// favors a matching positive reply when the networks disagree.
type fanoutConn struct {
	pc           net.PacketConn
	targets      []*net.UDPAddr
	from         map[string]bool
	mu           sync.Mutex
	grace        time.Time
	queryID      uint16
	question     dnsmessage.Question
	readDeadline time.Time
}

func newFanoutConn(servers []string) (net.Conn, error) {
	var targets []*net.UDPAddr
	from := map[string]bool{}
	for _, s := range servers {
		if a, err := net.ResolveUDPAddr("udp", s); err == nil {
			targets = append(targets, a)
			from[a.String()] = true
		}
	}
	if len(targets) == 0 {
		return nil, errors.New("no DNS servers")
	}
	pc, err := net.ListenPacket("udp", ":0")
	if err != nil {
		return nil, err
	}
	return &fanoutConn{pc: pc, targets: targets, from: from}, nil
}

func (c *fanoutConn) Write(b []byte) (int, error) {
	var p dnsmessage.Parser
	h, err := p.Start(b)
	if err != nil {
		return 0, err
	}
	q, err := p.Question()
	if err != nil {
		return 0, err
	}
	c.mu.Lock()
	c.queryID, c.question = h.ID, q
	c.mu.Unlock()
	sent := 0
	var last error
	for _, t := range c.targets {
		if _, err := c.pc.WriteTo(b, t); err == nil {
			sent++
		} else {
			last = err // e.g. no IPv6 route: the others still go
		}
	}
	if sent == 0 {
		return 0, last
	}
	return len(b), nil
}

func (c *fanoutConn) Read(b []byte) (int, error) {
	c.mu.Lock()
	id, question := c.queryID, c.question
	c.mu.Unlock()
	var fallback []byte
	var replied map[string]bool
	for {
		n, a, err := c.pc.ReadFrom(b)
		if err != nil {
			if timeout, ok := err.(net.Error); ok && timeout.Timeout() && fallback != nil {
				return copy(b, fallback), nil
			}
			return n, err
		}
		if !c.from[a.String()] {
			continue
		}
		var p dnsmessage.Parser
		h, err := p.Start(b[:n])
		if err != nil || !h.Response || h.ID != id {
			continue
		}
		q, err := p.Question()
		if err != nil || q != question {
			continue
		}
		// Truncation tells Go to retry over TCP. An empty or negative response on one
		// network still leaves time for a split-DNS answer from another.
		if h.Truncated || (h.RCode == dnsmessage.RCodeSuccess && binary.BigEndian.Uint16(b[6:8]) > 0) || len(c.from) == 1 {
			return n, nil
		}
		if fallback == nil {
			fallback = append([]byte(nil), b[:n]...)
			replied = make(map[string]bool)
			if err := c.setGrace(time.Now().Add(400 * time.Millisecond)); err != nil {
				return 0, err
			}
			defer c.setGrace(time.Time{})
		}
		replied[a.String()] = true
		if len(replied) == len(c.from) {
			return copy(b, fallback), nil
		}
	}
}

// The resolver checks for net.PacketConn to use datagram framing; these route to the same fan-out.
func (c *fanoutConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, err := c.Read(b)
	return n, c.RemoteAddr(), err
}
func (c *fanoutConn) WriteTo(b []byte, _ net.Addr) (int, error) { return c.Write(b) }

func (c *fanoutConn) Close() error         { return c.pc.Close() }
func (c *fanoutConn) LocalAddr() net.Addr  { return c.pc.LocalAddr() }
func (c *fanoutConn) RemoteAddr() net.Addr { return c.targets[0] }
func (c *fanoutConn) SetDeadline(t time.Time) error {
	if err := c.SetWriteDeadline(t); err != nil {
		return err
	}
	return c.SetReadDeadline(t)
}

func (c *fanoutConn) SetReadDeadline(t time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.readDeadline = t
	return c.applyReadDeadline()
}

func (c *fanoutConn) setGrace(t time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.grace = t
	return c.applyReadDeadline()
}

// Caller holds mu. A new deadline or a cancellation can still shorten the grace period.
func (c *fanoutConn) applyReadDeadline() error {
	end := c.readDeadline
	if !c.grace.IsZero() && (end.IsZero() || c.grace.Before(end)) {
		end = c.grace
	}
	return c.pc.SetReadDeadline(end)
}

func (c *fanoutConn) SetWriteDeadline(t time.Time) error { return c.pc.SetWriteDeadline(t) }
