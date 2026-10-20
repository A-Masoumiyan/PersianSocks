//go:build windows

package main

import (
	"encoding/binary"
	"fmt"
	"golang.org/x/sys/windows"
	"syscall"
	"unsafe"
)

// GetIpForwardTable supplies the effective IPv4 route+interface metric on Vista+.
// Discover by active default route, rather than the adapter's Physical flag.
func physicalNetwork() (Physical, error) {
	var size uint32
	proc := syscall.NewLazyDLL("iphlpapi.dll").NewProc("GetIpForwardTable")
	ret, _, _ := proc.Call(0, uintptr(unsafe.Pointer(&size)), 0)
	if ret != 122 || size < 4 || size > 16<<20 {
		return Physical{}, fmt.Errorf("خواندن مسیرهای شبکه: Windows %d", ret)
	}
	var routes []byte
	for attempt := 0; attempt < 3; attempt++ {
		routes = make([]byte, size)
		ret, _, _ = proc.Call(uintptr(unsafe.Pointer(&routes[0])), uintptr(unsafe.Pointer(&size)), 0)
		if ret == 0 {
			break
		}
		if ret != 122 || size > 16<<20 {
			return Physical{}, fmt.Errorf("خواندن مسیرهای شبکه: Windows %d", ret)
		}
	}
	if ret != 0 {
		return Physical{}, fmt.Errorf("جدول مسیرهای شبکه در حال تغییر است: Windows %d", ret)
	}
	metrics := map[uint32]uint32{}
	count := int(binary.LittleEndian.Uint32(routes))
	if count > (len(routes)-4)/56 {
		return Physical{}, fmt.Errorf("جدول مسیرهای شبکه ناقص است")
	}
	for i := 0; i < count; i++ {
		r := routes[4+i*56 : 4+(i+1)*56]
		if binary.LittleEndian.Uint32(r) != 0 || binary.LittleEndian.Uint32(r[4:]) != 0 || binary.LittleEndian.Uint32(r[20:]) == 2 {
			continue
		}
		idx := binary.LittleEndian.Uint32(r[16:])
		m := binary.LittleEndian.Uint32(r[36:])
		if old, ok := metrics[idx]; !ok || m < old {
			metrics[idx] = m
		}
	}
	size = 15000
	var adapters []byte
	for attempt := 0; attempt < 3; attempt++ {
		adapters = make([]byte, size)
		err := windows.GetAdaptersAddresses(syscall.AF_INET, 0, 0, (*windows.IpAdapterAddresses)(unsafe.Pointer(&adapters[0])), &size)
		if err == nil {
			break
		}
		if err != windows.ERROR_BUFFER_OVERFLOW || size > 16<<20 || attempt == 2 {
			return Physical{}, fmt.Errorf("خواندن آداپتورهای شبکه: %w", err)
		}
	}
	candidates := []networkCandidate{}
	for a := (*windows.IpAdapterAddresses)(unsafe.Pointer(&adapters[0])); a != nil; a = a.Next {
		metric, ok := metrics[a.IfIndex]
		if !ok {
			continue
		}
		c := networkCandidate{Physical: Physical{Index: int(a.IfIndex), Name: windows.UTF16PtrToString(a.FriendlyName)}, Metric: metric, Up: a.OperStatus == windows.IfOperStatusUp, Kind: a.IfType, Description: windows.UTF16PtrToString(a.Description)}
		for d := a.FirstDnsServerAddress; d != nil; d = d.Next {
			if ip := d.Address.IP(); ip != nil {
				c.DNS = append(c.DNS, ip.String())
			}
		}
		for u := a.FirstUnicastAddress; u != nil; u = u.Next {
			ip := u.Address.IP()
			if ip != nil && ip.To4() != nil && !ip.IsLinkLocalUnicast() {
				c.IP = ip.String()
				candidates = append(candidates, c)
				break
			}
		}
	}
	return selectNetwork(candidates)
}
func physicalReady(p Physical) bool {
	ip := validNetworkIPv4(p.IP)
	return p.Index > 0 && p.Name != "" && ip
}
func netIPv4(s string) bool { return validNetworkIPv4(s) }
func flushDNS()             { syscall.NewLazyDLL("dnsapi.dll").NewProc("DnsFlushResolverCache").Call() }
