#!/usr/bin/env python3
"""Verify release integrity; this is not a malware scan or Google certification."""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import re
import subprocess
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_security', ROOT/'scripts/release-security.py')
security = importlib.util.module_from_spec(spec)
spec.loader.exec_module(security)
EXPECTED_PERMISSIONS = {
    'ir.persiansocks.app.permission.INTERNAL_STATE',
    'ir.persiansocks.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
    'android.permission.POST_NOTIFICATIONS', 'android.permission.QUERY_ALL_PACKAGES',
    'android.permission.WAKE_LOCK', 'android.permission.RECEIVE_BOOT_COMPLETED',
}

def check_archive(apk):
    with apk.open('rb') as stream:
        if stream.read(4) != b'PK\x03\x04':
            raise ValueError('Nonstandard APK ZIP header')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP entry')
        if archive.testzip() is not None:
            raise ValueError('Corrupt ZIP entry')
        if not {'AndroidManifest.xml', 'resources.arsc', 'classes.dex'}.issubset(names):
            raise ValueError('Missing APK contents')
        for name in names:
            if name.startswith('/') or '..' in Path(name).parts:
                raise ValueError('Unsafe ZIP entry')
            if Path(name).suffix.lower() in {'.p12', '.pfx', '.jks', '.keystore', '.key'} or Path(name).name.lower() == 'password.txt':
                raise ValueError('Private signing material packaged')
        with zipfile.ZipFile(ROOT/'app/libs/hev-socks5-tunnel.aar') as native:
            expected = {'lib/'+n[4:]: native.read(n) for n in native.namelist() if n.startswith('jni/') and n.endswith('.so')}
        import json
        platform=json.loads((ROOT/'scripts/platform-native-sha256.json').read_text())['native']
        actual = {n for n in names if n.startswith('lib/')}
        if actual != set(expected) | set(platform):
            raise ValueError('Unexpected or missing native libraries')
        for name, data in expected.items():
            if archive.read(name) != data:
                raise ValueError('Native library differs from pinned upstream')
        for name, digest in platform.items():
            if hashlib.sha256(archive.read(name)).hexdigest()!=digest:
                raise ValueError('Platform native library differs from pinned dependency: '+name)
        for name in actual:
            data=archive.read(name)
            if data[:4]!=b'\x7fELF' or data[5]!=1:raise ValueError('Unexpected native ELF format')
            if data[4]==2:
                offset=struct.unpack_from('<Q',data,32)[0];stride,count=struct.unpack_from('<HH',data,54)
                for i in range(count):
                    ph=offset+i*stride
                    if struct.unpack_from('<I',data,ph)[0]==1 and struct.unpack_from('<Q',data,ph+48)[0]<16384:
                        raise ValueError('64-bit native ELF lacks 16 KiB load alignment: '+name)

def audit(apk, sdk, profile="personal"):
    security.verify_native(ROOT)
    check_archive(apk)
    bt = sdk/'build-tools/35.0.0'
    def run(*args):
        return subprocess.run([str(x) for x in args], check=True, capture_output=True, text=True).stdout
    signature = run(bt/'apksigner', 'verify', '--verbose', '--print-certs', apk)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', signature)
    if certs != [security.CERT_SHA256]:
        raise ValueError('Release signer differs from approved identity')
    run(bt/'zipalign', '-c', '-P', '16', '4', apk)
    permissions = set(re.findall(r"uses-permission: name='([^']+)'", run(bt/'aapt', 'dump', 'permissions', apk)))
    expected = EXPECTED_PERMISSIONS | ({"android.permission.REQUEST_INSTALL_PACKAGES"} if profile == "personal" else set())
    if permissions != expected:
        raise ValueError('Release permission inventory changed: '+str(sorted(permissions ^ expected)))
    badging = run(bt/'aapt', 'dump', 'badging', apk)
    if "package: name='ir.persiansocks.app'" not in badging or "targetSdkVersion:'36'" not in badging or 'application-debuggable' in badging:
        raise ValueError('Unexpected package, target SDK or debug APK')
    return {'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'bytes': apk.stat().st_size,
            'certificate': certs[0], 'permissions': sorted(permissions), 'status': 'release integrity checks passed; Google review not performed'}

if __name__ == '__main__':
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--profile', choices=['personal','play'], default='personal')
    args = parser.parse_args()
    print(json.dumps(audit(args.apk, args.sdk, args.profile), indent=2))
