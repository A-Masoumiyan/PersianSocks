//go:build windows

package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/windows"
	wf "persiansocks/internal/wfp"
)

func TestKillSocketChild(t *testing.T) {
	if os.Getenv("PERSIANSOCKS_KILL_CHILD") != "1" {
		return
	}
	network, address := os.Getenv("PERSIANSOCKS_KILL_NETWORK"), os.Getenv("PERSIANSOCKS_KILL_ADDRESS")
	c, err := net.DialTimeout(network, address, 800*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(800 * time.Millisecond))
	repeat := 1
	if os.Getenv("PERSIANSOCKS_KILL_STREAM") == "1" {
		repeat = 50
	}
	for i := 0; i < repeat; i++ {
		c.SetDeadline(time.Now().Add(800 * time.Millisecond))
		if _, err = c.Write([]byte("probe")); err != nil {
			t.Fatal(err)
		}
		b := make([]byte, 8)
		n, err := c.Read(b)
		if err != nil || string(b[:n]) != "ok" {
			t.Fatalf("probe response: %d %v", n, err)
		}
		if repeat > 1 {
			time.Sleep(150 * time.Millisecond)
		}
	}
	fmt.Println("KILL-PROBE-OK")
}
func newIsolatedKill(t *testing.T, scope []string, blockLoopback bool) *windowsKillFirewall {
	t.Helper()
	id, e := windows.GenerateGUID()
	if e != nil {
		t.Fatal(e)
	}
	f, e := openKillFirewall(wf.SublayerID(id), scope, blockLoopback)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		if f.session != nil {
			if e := f.Release(); e != nil {
				t.Error(e)
			}
			f.Close()
		}
	})
	return f
}
func TestWindowsKillFirewallIPv4IPv6TCPUDPAndSessionLoss(t *testing.T) {
	if os.Getenv("PERSIANSOCKS_TEST_TUN") == "" {
		t.Skip("Windows integration opt-in required")
	}
	executable, _ := os.Executable()
	bytes, e := os.ReadFile(executable)
	if e != nil {
		t.Fatal(e)
	}
	child := filepath.Join(t.TempDir(), "KillNetworkProbe.exe")
	if e = os.WriteFile(child, bytes, 0700); e != nil {
		t.Fatal(e)
	}
	type target struct {
		network, address string
		count            *atomic.Int32
	}
	targets := []target{}
	for _, family := range []string{"4", "6"} {
		host := "127.0.0.1"
		if family == "6" {
			host = "::1"
		}
		tcp, e := net.Listen("tcp"+family, net.JoinHostPort(host, "0"))
		if e != nil {
			t.Fatal(e)
		}
		defer tcp.Close()
		tc := &atomic.Int32{}
		go func() {
			for {
				c, e := tcp.Accept()
				if e != nil {
					return
				}
				go func() {
					defer c.Close()
					c.SetDeadline(time.Now().Add(time.Second))
					b := make([]byte, 8)
					for {
						c.SetDeadline(time.Now().Add(2 * time.Second))
						n, e := c.Read(b)
						if n > 0 {
							tc.Add(1)
							c.Write([]byte("ok"))
						}
						if e != nil {
							return
						}
					}
				}()
			}
		}()
		udp, e := net.ListenPacket("udp"+family, net.JoinHostPort(host, "0"))
		if e != nil {
			t.Fatal(e)
		}
		defer udp.Close()
		uc := &atomic.Int32{}
		go func() {
			b := make([]byte, 64)
			for {
				n, a, e := udp.ReadFrom(b)
				if e != nil {
					return
				}
				if n > 0 {
					uc.Add(1)
					udp.WriteTo([]byte("ok"), a)
				}
			}
		}()
		targets = append(targets, target{"tcp" + family, tcp.Addr().String(), tc}, target{"udp" + family, udp.LocalAddr().String(), uc})
	}
	probe := func(dst target, success bool) {
		t.Helper()
		before := dst.count.Load()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, child, "-test.run=^TestKillSocketChild$")
		cmd.Env = append(os.Environ(), "PERSIANSOCKS_KILL_CHILD=1", "PERSIANSOCKS_KILL_NETWORK="+dst.network, "PERSIANSOCKS_KILL_ADDRESS="+dst.address)
		hideCommand(cmd)
		out, err := cmd.CombinedOutput()
		ok := err == nil && strings.Contains(string(out), "KILL-PROBE-OK")
		if ok != success {
			t.Fatalf("%s success=%t want=%t: %v %s", dst.network, ok, success, err, out)
		}
		if !success && dst.count.Load() != before {
			t.Fatal("blocked request reached origin", dst.network)
		}
	}
	for _, dst := range targets {
		probe(dst, true)
	}
	// Establish a TCP flow before arming the firewall, then verify reauthorization.
	streamTarget := targets[0]
	streamBefore := streamTarget.count.Load()
	streamCtx, streamCancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer streamCancel()
	stream := exec.CommandContext(streamCtx, child, "-test.run=^TestKillSocketChild$")
	stream.Env = append(os.Environ(), "PERSIANSOCKS_KILL_CHILD=1", "PERSIANSOCKS_KILL_STREAM=1", "PERSIANSOCKS_KILL_NETWORK="+streamTarget.network, "PERSIANSOCKS_KILL_ADDRESS="+streamTarget.address)
	hideCommand(stream)
	if e := stream.Start(); e != nil {
		t.Fatal(e)
	}
	streamDone := make(chan error, 1)
	go func() { streamDone <- stream.Wait() }()
	until := time.Now().Add(3 * time.Second)
	for streamTarget.count.Load() < streamBefore+2 && time.Now().Before(until) {
		time.Sleep(30 * time.Millisecond)
	}
	if streamTarget.count.Load() < streamBefore+2 {
		t.Fatal("pre-existing TCP stream was not established")
	}
	f := newIsolatedKill(t, []string{child}, true)
	if e = f.Arm([]string{executable}, "missing-tun-interface"); e != nil {
		t.Fatal(e)
	}
	time.Sleep(350 * time.Millisecond)
	afterArm := streamTarget.count.Load()
	time.Sleep(600 * time.Millisecond)
	if streamTarget.count.Load() != afterArm {
		t.Error("pre-existing TCP connection continued sending after the kill switch was armed")
	}
	select {
	case <-streamDone:
	case <-time.After(2 * time.Second):
		streamCancel()
		<-streamDone
	}
	for _, dst := range targets {
		probe(dst, false)
	}
	// A non-dynamic WFP policy survives loss of the controller's session.
	id := f.sublayer
	if e = f.Close(); e != nil {
		t.Fatal(e)
	}
	f.session = nil
	for _, dst := range targets {
		probe(dst, false)
	}
	reopened, e := openKillFirewall(id, []string{child}, true)
	if e != nil {
		t.Fatal(e)
	}
	defer reopened.Close()
	defer reopened.Release()
	if e = reopened.Release(); e != nil {
		t.Fatal(e)
	}
	for _, dst := range targets {
		probe(dst, true)
	}
	t.Log("IPv4/IPv6, TCP/UDP: blocked origins received zero requests; policy survived session loss; explicit off restored direct traffic")
}
