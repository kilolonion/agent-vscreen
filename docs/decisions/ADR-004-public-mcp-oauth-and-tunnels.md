# ADR-004: Public MCP Isolation, OAuth 2.1 + CIMD, Pairing Codes, and Tunnel Boundaries

- **Status**: Accepted
- **Scope**: `api/PublicServer.kt`, `api/PublicOAuth.kt`, `api/CimdResolver.kt`, `host/PublicAccess.kt`, `host/Cloudflared.kt`, `host/Ngrok.kt`, `host/Tailnet.kt`

## Context
Cloud MCP clients cannot join a user's private Tailscale tailnet, yet exposing local phone automation to the public internet introduces unsolicited popup spam, token theft, SSRF via client metadata URLs, and 24-hour idle timer bypass from internet scanners.

## Decision
1. **Strict Listener & Header Isolation (`PublicServer`)**: Public traffic binds exclusively to `127.0.0.1:18785`, sets `viaPublic = true` unconditionally before routing, verifies a per-start rotated `X-AVS-Public` bridge secret in constant time, rejects local tokens and `/v2/pair/exchange`, strips forged client headers, and sanitizes all HTTP 500/502 errors.
2. **OAuth 2.1 + PKCE S256 + RFC 9207 `iss` + CIMD**:
   - Supports Authorization Code (PKCE S256, RFC 8707 `resource` binding, RFC 9207 `iss`), RFC 8628 Device Code, DCR (`application_type` checked), and CIMD (`client_id_metadata_document_supported: true`).
   - `CimdResolver` blocks SSRF by validating strict `https://` URL syntax (non-empty path, default port 443, no IP literals/userinfo/query/fragment), resolving all DNS IPs and denying loopback/RFC1918/link-local/CGNAT-Tailscale (`100.64.0.0/10`)/ULA (`fc00::/7`)/multicast, disabling redirects, capping body at `64 KB`, rate-limiting cache misses to `10/h` per source, and caching up to 64 entries (`<= 24 h`).
   - Pairing cards clearly distinguish CIMD clients (green verified domain badge) from DCR clients (gray unverified self-reported notice).
3. **Immediate Pairing Card with a Bounded Queue**: Remote authorize/device requests show the confirmation card on the phone at once (notification, and the card itself when the app is in front), with the source address. At most 3 cards are open at a time; further requests queue (FIFO cap 32) and can be opened by their pairing code, so a flood from many addresses cannot lock a real client out. An optional 3-minute strict pairing window is available.
4. **Refresh Rotation Grace & Idle Auto-Off**: Refresh rotation keeps a 30-second in-memory single-retry grace for flaky mobile networks; reuse beyond the grace or second replay immediately revokes all tokens for that client. The 24-hour idle auto-off timer advances ONLY on authenticated `POST /mcp` (valid Bearer) and HTTP 200 `/oauth/token` — never on unauthenticated probes.

## Consequences
- Public MCP remains off by default, resets off on app version updates, never grants `system` scope, and resists internet-scale scanning and pairing floods.
