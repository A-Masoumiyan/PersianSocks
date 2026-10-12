package main

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestRealCoreForwardsThroughSelectedProxy(t *testing.T) {
	bin, _ := filepath.Abs("../toolchain/sing-box-1.14.3-linux-amd64/sing-box")
	if override := os.Getenv("PERSIANSOCKS_TEST_CORE"); override != "" {
		bin = override
	}
	if _, err := os.Stat(bin); err != nil {
		t.Skip("native validation core not available")
	}
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "CORE-PROXY-ORIGIN") }))
	defer origin.Close()
	pool := x509.NewCertPool()
	pool.AddCert(origin.Certificate())
	for _, typ := range []string{"SOCKS5", "HTTP", "HTTPS"} {
		t.Run(typ, func(t *testing.T) {
			var count atomic.Int32
			endpoint, stop := proxyServer(t, typ, origin.TLS.Certificates[0], 0, fixtureOptions{Target: strings.TrimPrefix(origin.URL, "https://"), Connections: &count})
			defer stop()
			reserved, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			port := reserved.Addr().(*net.TCPAddr).Port
			reserved.Close()
			cfg := tunnelConfig(endpoint, Physical{}, port)
			inbounds := cfg["inbounds"].([]any)
			cfg["inbounds"] = inbounds[1:] // TUN kernel operations require a real target OS.
			dir := t.TempDir()
			ca := filepath.Join(dir, "ca.pem")
			os.WriteFile(ca, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: origin.Certificate().Raw}), 0600)
			// Windows does not use SSL_CERT_FILE for system roots. Trust only the local test CA explicitly.
			if typ == "HTTPS" {
				cfg["outbounds"].([]any)[0].(map[string]any)["tls"].(map[string]any)["certificate_path"] = ca
			}
			config := filepath.Join(dir, "config.json")
			data, _ := json.Marshal(cfg)
			os.WriteFile(config, data, 0600)
			log := &logTail{}
			cmd := exec.Command(bin, "run", "-c", config)
			cmd.Stdout = log
			cmd.Stderr = log
			cmd.Env = append(os.Environ(), "SSL_CERT_FILE="+ca)
			setupCore(cmd)
			if err = cmd.Start(); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { done <- cmd.Wait(); close(done) }()
			defer stopCore(cmd, done)
			deadline := time.Now().Add(4 * time.Second)
			ready := false
			for time.Now().Before(deadline) {
				select {
				case <-done:
					if strings.Contains(log.String(), "create netlink socket: operation not permitted") {
						t.Skip("core execution blocked by this environment's network-monitor permissions; not a passed runtime test")
					}
					t.Fatalf("core stopped: %s", log.String())
				default:
				}
				c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 100*time.Millisecond)
				if err == nil {
					c.Close()
					ready = true
					break
				}
				time.Sleep(30 * time.Millisecond)
			}
			if !ready {
				t.Fatalf("core did not start: %s", log.String())
			}
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancel()
			response, c, err := (Probe{Roots: pool}).request(ctx, Endpoint{Host: "127.0.0.1", Port: port, Type: "SOCKS5"}, origin.URL)
			if err != nil {
				t.Fatalf("core connection failed: %v\n%s", err, log.String())
			}
			defer c.Close()
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil || string(body) != "CORE-PROXY-ORIGIN" {
				t.Fatalf("origin exchange failed: %q %v", body, err)
			}
			if count.Load() == 0 {
				t.Fatal("verification request bypassed the selected proxy and succeeded directly")
			}
		})
	}
}
