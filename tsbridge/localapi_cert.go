package main

import (
	"context"
	"errors"
	"fmt"
	"maps"
	"net/http"
	"strings"
	"time"

	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/tsweb"
)

// certFetcher abstracts LocalBackend.GetCertPEMWithValidity so the LocalAPI cert/ handler
// can be tested on Linux without needing an Android build or live ACME server.
type certFetcher interface {
	GetCertPEMWithValidity(ctx context.Context, domain string, minValidity time.Duration) (*ipnlocal.TLSCertKeyPair, error)
}

// serveCertHandler implements the LocalAPI `/localapi/v0/cert/<domain>` endpoint with the
// exact semantics of upstream `tailscale.com/ipn/localapi/cert.go` (which has build tag
// `!android` and is therefore omitted from GOOS=android builds).
func serveCertHandler(permitWrite, permitCert bool, fetcher certFetcher, w http.ResponseWriter, r *http.Request) {
	if !permitWrite && !permitCert {
		http.Error(w, "cert access denied", http.StatusForbidden)
		return
	}
	domain, ok := strings.CutPrefix(r.URL.Path, "/localapi/v0/cert/")
	if !ok {
		http.Error(w, "internal handler config wired wrong", http.StatusInternalServerError)
		return
	}
	var minValidity time.Duration
	if minValidityStr := r.URL.Query().Get("min_validity"); minValidityStr != "" {
		var err error
		minValidity, err = time.ParseDuration(minValidityStr)
		if err != nil {
			http.Error(w, fmt.Sprintf("invalid validity parameter: %v", err), http.StatusBadRequest)
			return
		}
	}
	pair, err := fetcher.GetCertPEMWithValidity(r.Context(), domain, minValidity)
	if err != nil {
		var hs tsweb.HTTPStatuser
		if errors.As(err, &hs) {
			resp := hs.HTTPStatus()
			maps.Copy(w.Header(), resp.Header)
			http.Error(w, resp.Msg, resp.Code)
			return
		}
		http.Error(w, fmt.Sprint(err), http.StatusInternalServerError)
		return
	}
	serveCertKeyPair(w, r, pair)
}

func serveCertKeyPair(w http.ResponseWriter, r *http.Request, p *ipnlocal.TLSCertKeyPair) {
	w.Header().Set("Content-Type", "text/plain")
	switch r.URL.Query().Get("type") {
	case "", "crt", "cert":
		_, _ = w.Write(p.CertPEM)
	case "key":
		_, _ = w.Write(p.KeyPEM)
	case "pair":
		_, _ = w.Write(p.KeyPEM)
		_, _ = w.Write(p.CertPEM)
	default:
		http.Error(w, `invalid type; want "cert" (default), "key", or "pair"`, http.StatusBadRequest)
	}
}
