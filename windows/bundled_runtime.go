package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// The complete runtime is embedded as a compressed ZIP; no download is required.
func prepareBundledRuntime(dir string) (string, error) {
	raw, err := bundle.ReadFile("assets/windows-runtime.zip")
	if err != nil {
		return "", errors.New("موتور داخلی در فایل برنامه موجود نیست")
	}
	z, err := zip.NewReader(bytes.NewReader(raw), int64(len(raw)))
	if err != nil {
		return "", err
	}
	var manifest map[string]string
	for _, f := range z.File {
		if f.Name == "manifest.json" {
			r, err := f.Open()
			if err != nil {
				return "", err
			}
			b, err := io.ReadAll(io.LimitReader(r, 32768))
			r.Close()
			if err != nil {
				return "", err
			}
			if err = json.Unmarshal(b, &manifest); err != nil {
				return "", err
			}
		}
	}
	if manifest["sing-box.exe"] != coreSHA || manifest["wintun.dll"] != wintunSHA {
		return "", errors.New("هویت موتور داخلی معتبر نیست")
	}
	if err = os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	for _, f := range z.File {
		if f.Name == "manifest.json" {
			continue
		}
		expected, ok := manifest[f.Name]
		if !ok || filepath.Base(f.Name) != f.Name || len(expected) != 64 || f.UncompressedSize64 > 120_000_000 {
			return "", errors.New("فایل موتور داخلی معتبر نیست")
		}
		dst := filepath.Join(dir, f.Name)
		if verifyFile(dst, expected) == nil {
			continue
		}
		reader, err := f.Open()
		if err != nil {
			return "", err
		}
		tmp, err := os.CreateTemp(dir, "runtime-*.tmp")
		if err != nil {
			reader.Close()
			return "", err
		}
		hasher := sha256.New()
		_, err = io.Copy(io.MultiWriter(tmp, hasher), io.LimitReader(reader, 120_000_001))
		reader.Close()
		closeErr := tmp.Close()
		if err == nil {
			err = closeErr
		}
		if err == nil && hex.EncodeToString(hasher.Sum(nil)) != expected {
			err = fmt.Errorf("فایل %s کامل نیست", f.Name)
		}
		if err == nil {
			err = os.Rename(tmp.Name(), dst)
		}
		if err != nil {
			os.Remove(tmp.Name())
			return "", err
		}
	}
	bin := filepath.Join(dir, "sing-box.exe")
	if err = verifyFile(bin, coreSHA); err != nil {
		return "", err
	}
	if err = verifyFile(filepath.Join(dir, "wintun.dll"), wintunSHA); err != nil {
		return "", err
	}
	return bin, nil
}
