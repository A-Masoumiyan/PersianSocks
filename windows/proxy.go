package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Endpoint struct {
	Host     string   `json:"host"`
	Port     int      `json:"port"`
	Type     string   `json:"type"`
	Source   string   `json:"source,omitempty"`
	Country  string   `json:"country,omitempty"`
	Ping     int64    `json:"ping,omitempty"`
	Speed    float64  `json:"speed,omitempty"`
	Services []string `json:"services,omitempty"`
	Updated  int64    `json:"updated,omitempty"`
	Note     string   `json:"note,omitempty"`
	UDP      bool     `json:"udp"`
	Checked  []string `json:"checked,omitempty"`
}

func (e Endpoint) key() string {
	return e.Type + "://" + net.JoinHostPort(e.Host, strconv.Itoa(e.Port))
}

var forbidden = []string{"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3", "::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8", "2001:db8::/32"}

func parseEndpoint(line, typ, source string) (Endpoint, bool) {
	line = strings.TrimSpace(line)
	if line == "" || strings.HasPrefix(line, "#") {
		return Endpoint{}, false
	}
	if !strings.Contains(line, "://") {
		line = strings.ToLower(typ) + "://" + line
	}
	u, err := url.Parse(line)
	if err != nil || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return Endpoint{}, false
	}
	t := strings.ToUpper(u.Scheme)
	if t != "SOCKS5" && t != "HTTP" && t != "HTTPS" {
		return Endpoint{}, false
	}
	ip, err := netip.ParseAddr(u.Hostname())
	if err != nil {
		if t == "HTTPS" && validPublicHostname(u.Hostname()) {
			port, err := strconv.Atoi(u.Port())
			if err == nil && port > 0 && port <= 65535 {
				return Endpoint{Host: strings.ToLower(u.Hostname()), Port: port, Type: t, Source: source}, true
			}
		}
		return Endpoint{}, false
	}
	ip = ip.Unmap()
	if !ip.Is4() {
		return Endpoint{}, false
	}
	if !ip.IsGlobalUnicast() {
		return Endpoint{}, false
	}
	for _, s := range forbidden {
		if netip.MustParsePrefix(s).Contains(ip) {
			return Endpoint{}, false
		}
	}
	p, err := strconv.Atoi(u.Port())
	if err != nil || p < 1 || p > 65535 {
		return Endpoint{}, false
	}
	return Endpoint{Host: ip.String(), Port: p, Type: t, Source: source}, true
}

type Physical struct {
	Index int      `json:"index"`
	Name  string   `json:"name"`
	IP    string   `json:"ip"`
	DNS   []string `json:"dns"`
}

func (p Physical) dial(ctx context.Context, network, address string) (net.Conn, error) {
	d := net.Dialer{Timeout: 6 * time.Second, KeepAlive: 30 * time.Second}
	if p.IP != "" {
		if strings.HasPrefix(network, "udp") {
			d.LocalAddr = &net.UDPAddr{IP: net.ParseIP(p.IP)}
		} else {
			d.LocalAddr = &net.TCPAddr{IP: net.ParseIP(p.IP)}
		}
	}
	d.Control = socketControl(p.Index)
	d.Resolver = &net.Resolver{PreferGo: true, Dial: func(c context.Context, n, a string) (net.Conn, error) {
		// Snapshot DNS before the TUN exists; never switch to its DNS server.
		if len(p.DNS) > 0 {
			a = net.JoinHostPort(p.DNS[0], "53")
		}
		dd := net.Dialer{Timeout: 3 * time.Second, Control: socketControl(p.Index)}
		if p.IP != "" {
			if strings.HasPrefix(n, "udp") {
				dd.LocalAddr = &net.UDPAddr{IP: net.ParseIP(p.IP)}
			} else {
				dd.LocalAddr = &net.TCPAddr{IP: net.ParseIP(p.IP)}
			}
		}
		return dd.DialContext(c, n, a)
	}}
	return d.DialContext(ctx, network, address)
}

type Probe struct {
	Physical Physical
	Roots    *x509.CertPool
}

func exact(c net.Conn, n int) ([]byte, error) {
	b := make([]byte, n)
	_, e := io.ReadFull(c, b)
	return b, e
}
func (p Probe) tunnel(ctx context.Context, e Endpoint, host string, port int) (net.Conn, error) {
	network := "tcp4"
	if strings.Contains(e.Host, ":") {
		network = "tcp6"
	}
	c, err := p.Physical.dial(ctx, network, net.JoinHostPort(e.Host, strconv.Itoa(e.Port)))
	if err != nil {
		return nil, err
	}
	ok := false
	defer func() {
		if !ok {
			c.Close()
		}
	}()
	deadline, has := ctx.Deadline()
	if has {
		c.SetDeadline(deadline)
	}
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()
	if e.Type == "HTTPS" {
		tc := tls.Client(c, &tls.Config{ServerName: e.Host, MinVersion: tls.VersionTLS12, RootCAs: p.Roots})
		if err = tc.HandshakeContext(ctx); err != nil {
			return nil, err
		}
		c = tc
	}
	if e.Type == "SOCKS5" {
		if _, err = c.Write([]byte{5, 1, 0}); err != nil {
			return nil, err
		}
		b, err := exact(c, 2)
		if err != nil {
			return nil, err
		}
		if b[0] != 5 || b[1] != 0 {
			return nil, errors.New("SOCKS: پاسخ یا احراز هویت نامعتبر")
		}
		if len(host) > 255 {
			return nil, errors.New("نام مقصد بلند است")
		}
		req := append([]byte{5, 1, 0, 3, byte(len(host))}, []byte(host)...)
		req = append(req, byte(port>>8), byte(port))
		if _, err = c.Write(req); err != nil {
			return nil, err
		}
		b, err = exact(c, 4)
		if err != nil {
			return nil, err
		}
		if b[0] != 5 || b[1] != 0 || b[2] != 0 {
			return nil, errors.New("SOCKS: مقصد رد شد")
		}
		n := 0
		switch b[3] {
		case 1:
			n = 4
		case 4:
			n = 16
		case 3:
			b, err = exact(c, 1)
			if err != nil {
				return nil, err
			}
			n = int(b[0])
		default:
			return nil, errors.New("SOCKS: نوع آدرس نامعتبر")
		}
		if n < 1 {
			return nil, errors.New("SOCKS: آدرس نامعتبر")
		}
		if _, err = exact(c, n+2); err != nil {
			return nil, err
		}
	} else {
		target := net.JoinHostPort(host, strconv.Itoa(port))
		if _, err = fmt.Fprintf(c, "CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Connection: Keep-Alive\r\n\r\n", target, target); err != nil {
			return nil, err
		}
		// Read exactly the header so a buffered reader cannot swallow TLS data.
		header := make([]byte, 0, 1024)
		one := make([]byte, 1)
		for len(header) < 32768 {
			if _, err = io.ReadFull(c, one); err != nil {
				return nil, err
			}
			header = append(header, one[0])
			if strings.HasSuffix(string(header), "\r\n\r\n") {
				break
			}
		}
		if !strings.HasSuffix(string(header), "\r\n\r\n") {
			return nil, errors.New("HTTP: سرآیند بیش از حد بزرگ")
		}
		first := strings.SplitN(string(header), "\r\n", 2)[0]
		parts := strings.Fields(first)
		if len(parts) < 2 || !strings.HasPrefix(parts[0], "HTTP/1.") || parts[1] != "200" {
			return nil, errors.New("HTTP: تونل CONNECT پذیرفته نشد")
		}
	}
	ok = true
	return c, nil
}
func (p Probe) request(ctx context.Context, e Endpoint, u string) (*http.Response, net.Conn, error) {
	target, err := url.Parse(u)
	if err != nil {
		return nil, nil, err
	}
	port := 443
	if target.Port() != "" {
		port, _ = strconv.Atoi(target.Port())
	}
	c, err := p.tunnel(ctx, e, target.Hostname(), port)
	if err != nil {
		return nil, nil, err
	}
	stop := context.AfterFunc(ctx, func() { c.Close() })
	tc := tls.Client(c, &tls.Config{ServerName: target.Hostname(), MinVersion: tls.VersionTLS12, RootCAs: p.Roots})
	if err = tc.HandshakeContext(ctx); err != nil {
		stop()
		c.Close()
		return nil, nil, err
	}
	req, _ := http.NewRequestWithContext(ctx, "GET", u, nil)
	req.Header.Set("User-Agent", "PersianSocks-Windows/"+version)
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("Cache-Control", "no-cache")
	req.Close = true
	if err = req.Write(tc); err != nil {
		stop()
		tc.Close()
		return nil, nil, err
	}
	resp, err := http.ReadResponse(bufio.NewReaderSize(tc, 32768), req)
	if err != nil {
		stop()
		tc.Close()
		return nil, nil, err
	}
	return resp, &stoppableConn{Conn: tc, stop: stop}, nil
}

type stoppableConn struct {
	net.Conn
	stop func() bool
}

func (c *stoppableConn) Close() error { c.stop(); return c.Conn.Close() }

type Target struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	URL  string `json:"url"`
}

var targets = []Target{{"telegram", "تلگرام", "https://web.telegram.org/k/"}, {"youtube", "یوتیوب", "https://www.youtube.com/"}, {"instagram", "اینستاگرام", "https://www.instagram.com/robots.txt"}}

func (p Probe) inspect(ctx context.Context, e Endpoint, timeout int, ts []Target) (Endpoint, error) {
	e.Services = nil
	e.Checked = nil
	e.Ping = 0
	var last error
	for _, t := range ts {
		e.Checked = append(e.Checked, t.ID)
		if ctx.Err() != nil {
			return e, ctx.Err()
		}
		c, cancel := context.WithTimeout(ctx, time.Duration(timeout)*time.Second)
		start := time.Now()
		r, conn, err := p.request(c, e, t.URL)
		if err == nil {
			if r.StatusCode >= 200 && r.StatusCode < 400 {
				ms := time.Since(start).Milliseconds()
				if ms < 1 {
					ms = 1
				}
				if e.Ping == 0 || ms < e.Ping {
					e.Ping = ms
				}
				e.Services = append(e.Services, t.ID)
			} else {
				err = fmt.Errorf("HTTPS %d", r.StatusCode)
			}
			r.Body.Close()
			conn.Close()
		}
		cancel()
		last = err
		var op *net.OpError
		if errors.As(err, &op) && op.Op == "dial" && len(e.Services) == 0 {
			break
		}
	}
	if ctx.Err() != nil {
		return e, ctx.Err()
	}
	if len(e.Services) == 0 {
		if last == nil {
			last = errors.New("پاسخ معتبر HTTPS دریافت نشد")
		}
		return e, last
	}
	e.Updated = time.Now().UnixMilli()
	e.Note = ""
	if e.Type == "SOCKS5" {
		e.UDP = p.udpCapability(ctx, e)
	}
	return e, nil
}
func (p Probe) speed(ctx context.Context, e Endpoint, progress ...func(float64, int64)) (float64, int64, error) {
	c, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	r, conn, err := p.request(c, e, "https://speed.cloudflare.com/__down?bytes=20000000&ts="+strconv.FormatInt(time.Now().UnixNano(), 10))
	if err != nil {
		return 0, 0, err
	}
	defer conn.Close()
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return 0, 0, fmt.Errorf("سرور سرعت: HTTP %d", r.StatusCode)
	}
	if enc := r.Header.Get("Content-Encoding"); enc != "" && enc != "identity" {
		return 0, 0, errors.New("پاسخ فشردهٔ تست سرعت پذیرفته نشد")
	}
	start := time.Now()
	var n int64
	buf := make([]byte, 32768)
	last := start
	for n < 20_000_000 {
		max := int64(len(buf))
		if 20_000_000-n < max {
			max = 20_000_000 - n
		}
		var m int
		m, err = r.Body.Read(buf[:max])
		n += int64(m)
		now := time.Now()
		if len(progress) > 0 && now.Sub(last) > 300*time.Millisecond {
			progress[0](float64(n)/1e6/now.Sub(start).Seconds(), n)
			last = now
		}
		if err == io.EOF {
			err = nil
			break
		}
		if err != nil || now.Sub(start) > 12*time.Second {
			break
		}
	}
	if ctx.Err() != nil {
		err = ctx.Err()
	}
	if err != nil {
		return 0, n, err
	}
	if n == 0 {
		return 0, 0, errors.New("داده‌ای دریافت نشد")
	}
	return float64(n) / 1e6 / time.Since(start).Seconds(), n, nil
}
func country(host string) string {
	ip := net.ParseIP(host).To4()
	if ip == nil || len(geo) < 8 {
		return ""
	}
	x := binary.BigEndian.Uint32(ip)
	count := int(binary.BigEndian.Uint32(geo[4:8]))
	lo, hi := 0, count-1
	for lo <= hi {
		mid := (lo + hi) / 2
		off := 8 + mid*10
		if off+10 > len(geo) {
			return ""
		}
		a, b := binary.BigEndian.Uint32(geo[off:]), binary.BigEndian.Uint32(geo[off+4:])
		if x < a {
			hi = mid - 1
		} else if x > b {
			lo = mid + 1
		} else {
			return string(geo[off+8 : off+10])
		}
	}
	return ""
}

// UDP ASSOCIATE acceptance describes server capability; it does not guarantee end-to-end UDP delivery.
func (p Probe) udpCapability(ctx context.Context, e Endpoint) bool {
	cctx, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()
	c, err := p.Physical.dial(cctx, "tcp4", net.JoinHostPort(e.Host, strconv.Itoa(e.Port)))
	if err != nil {
		return false
	}
	defer c.Close()
	deadline, _ := cctx.Deadline()
	c.SetDeadline(deadline)
	stop := context.AfterFunc(cctx, func() { c.Close() })
	defer stop()
	if _, err = c.Write([]byte{5, 1, 0}); err != nil {
		return false
	}
	b, err := exact(c, 2)
	if err != nil || b[0] != 5 || b[1] != 0 {
		return false
	}
	if _, err = c.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return false
	}
	b, err = exact(c, 4)
	return err == nil && b[0] == 5 && b[1] == 0 && b[2] == 0
}
