package zeddns

import (
    "context"
    "io"
    "log"
    "net"
    "net/http"
    "net/http/httptest"
    "strings"
    "testing"
    "time"
)

// The upstream IP-address branch skips certificate validation. A numeric DoT
// resolver with an untrusted certificate must fail before any DNS query is sent.
func TestWhiteVpnDoTRejectsUntrustedIPAddress(t *testing.T) {
    server := httptest.NewUnstartedServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
    server.Config.ErrorLog = log.New(io.Discard, "", 0)
    server.StartTLS()
    defer server.Close()
    c := newStreamConn(upstream{kind: transportDoT, addr: strings.TrimPrefix(server.URL, "https://")},
        poolConfig{dial: (&net.Dialer{Timeout: 3*time.Second}).DialContext}).(*streamConn)
    ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
    defer cancel()
    conn, err := c.dial(ctx)
    if conn != nil { conn.Close() }
    if err == nil { t.Fatal("DoT accepted an untrusted IP-address certificate") }
}
