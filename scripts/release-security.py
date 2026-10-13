"""Release input checks shared by APK and bundle builders. Never creates a signing key."""
import hashlib
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit

NATIVE_SHA256 = '15ec8ed121663b562c99caa5bb602d1009f24e5b09e733438b81988f12feaaab'
CERT_SHA256 = '01093c8c0026d46c2d4dd45914c1bb5c5667a5d703286b612911582d202673dd'

def validate_privacy_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None or
            parsed.password is not None or any(ord(c) < 33 for c in value)):
        raise ValueError('Release requires a public HTTPS privacy policy URL without credentials')
    return value

def verify_native(root):
    aar = root / 'app/libs/hev-socks5-tunnel.aar'
    if not aar.is_file() or hashlib.sha256(aar.read_bytes()).hexdigest() != NATIVE_SHA256:
        raise RuntimeError('Native AAR missing or differs from the pinned upstream 2.18.0 artifact')

def verify_signing(root, jdk):
    key, password = root / 'signing/PersianSocks-release.p12', root / 'signing/password.txt'
    if not key.is_file() or not password.is_file():
        raise RuntimeError('Original release key and password file required; refusing to generate a replacement key')
    keytool = jdk / ('bin/keytool.exe' if os.name == 'nt' else 'bin/keytool')
    # Binary certificate only. Password contents never enter command arguments or logs.
    result = subprocess.run([str(keytool), '-exportcert', '-keystore', str(key), '-alias',
        'persiansocks', '-storepass:file', str(password)], capture_output=True)
    if result.returncode != 0:
        raise RuntimeError('Cannot read release signing certificate with the supplied original key')
    if hashlib.sha256(result.stdout).hexdigest() != CERT_SHA256:
        raise RuntimeError('Signing certificate differs from the pinned official release certificate')
    return key, password

def verify_play_permission(profile, approved):
    if profile == 'play' and not approved:
        raise RuntimeError('Play release requires confirmed QUERY_ALL_PACKAGES approval from Google')

def verify_inputs(root, jdk, policy_url, profile='personal', play_permission_approved=False):
    verify_play_permission(profile, play_permission_approved)
    verify_signing(root, jdk)
    validate_privacy_url(policy_url)
    verify_native(root)
