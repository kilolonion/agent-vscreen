//go:build android && !ts_omit_acme

package main

import (
	"net/http"

	"tailscale.com/ipn/localapi"
)

// Upstream tailscale.com/ipn/localapi/cert.go carries `//go:build !ios && !android && !js && !ts_omit_acme`,
// so GOOS=android builds omit `/localapi/v0/cert/` even though `feature/acme` and
// `LocalBackend.GetCertPEMWithValidity` are compiled in. Register the route here so
// `tsnet.ListenFunnel` can obtain TLS certificates via LocalAPI on Android.
func init() {
	localapi.Register("cert/", func(h *localapi.Handler, w http.ResponseWriter, r *http.Request) {
		serveCertHandler(h.PermitWrite, h.PermitCert, h.LocalBackend(), w, r)
	})
}
