//go:build windows

package wf

import (
	"testing"
	"unsafe"
)

func TestArenaStructAlignmentAfterVariableBlobs(t *testing.T) {
	var a arena
	defer a.Dispose()
	for n := uintptr(1); n < 150; n++ {
		a.Alloc(n)
		p := a.Alloc(unsafe.Sizeof(fwpByteBlob{}))
		if uintptr(p)%unsafe.Alignof(fwpByteBlob{}) != 0 {
			t.Fatal("unaligned WFP blob after", n)
		}
		blob := (*fwpByteBlob)(p)
		blob.Size = uint32(n)
		q := a.Alloc(unsafe.Sizeof(fwpmFilter0{}))
		if uintptr(q)%unsafe.Alignof(fwpmFilter0{}) != 0 {
			t.Fatal("unaligned WFP filter after", n)
		}
		filter := (*fwpmFilter0)(q)
		filter.NumFilterConditions = 1
	}
}
