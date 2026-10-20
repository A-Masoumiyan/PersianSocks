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

// FWPM_FILTER0 uses a C union, not two sequential context fields.
func TestFilterNativeAMD64Layout(t *testing.T) {
	if unsafe.Sizeof(uintptr(0)) != 8 {
		t.Skip("64-bit release ABI")
	}
	var f fwpmFilter0
	if unsafe.Sizeof(f) != 200 || unsafe.Offsetof(f.Reserved) != 168 || unsafe.Offsetof(f.FilterID) != 176 || unsafe.Offsetof(f.EffectiveWeight) != 184 {
		t.Fatalf("FWPM_FILTER0 C ABI mismatch: size=%d reserved=%d id=%d effective=%d", unsafe.Sizeof(f), unsafe.Offsetof(f.Reserved), unsafe.Offsetof(f.FilterID), unsafe.Offsetof(f.EffectiveWeight))
	}
}
