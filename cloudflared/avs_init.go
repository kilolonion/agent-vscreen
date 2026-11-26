package main

// 2.0.0-beta11 (Agent-VScreen): added to cloudflared's main package at build time through `go build -overlay`
// (cloudflared/build.sh); the cloudflared source itself is not changed.
//
// Android has no /etc/resolv.conf, so Go's own resolver would ask 127.0.0.1:53 and fail (edge discovery would then
// fall back to DNS over TLS, and the region names would still not resolve). The app passes the phone's resolvers in
// AVS_DNS; they are asked first and public resolvers only as a last resort, as in the built-in Tailscale node
// (tsbridge/dns.go, compiled in beside this file).

import (
	"net"
	"os"
)

func init() {
	net.DefaultResolver = newResolver(dnsServerList(os.Getenv("AVS_DNS")), publicDNS)
}
