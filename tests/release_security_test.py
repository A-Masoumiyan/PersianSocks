"""Exercise missing and incorrect signing identities without using the private release key."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest

parser=argparse.ArgumentParser()
parser.add_argument('--jdk',required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('release_security',root/'scripts/release-security.py')
security=importlib.util.module_from_spec(spec);spec.loader.exec_module(security)

class ReleaseSecurityTests(unittest.TestCase):
    def test_play_release_without_permission_approval_is_refused(self):
        with self.assertRaisesRegex(RuntimeError,'approval from Google'):
            security.verify_play_permission('play',False)
        security.verify_play_permission('personal',False)
    def test_missing_key_does_not_create_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError,'Original release key'):
                security.verify_signing(Path(tmp),Path(args.jdk))
            self.assertEqual(list(Path(tmp).iterdir()),[])

    def test_unrelated_real_signing_key_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            signing=Path(tmp)/'signing';signing.mkdir()
            password=signing/'password.txt';password.write_text('test-only-password\n')
            keytool=Path(args.jdk)/('bin/keytool.exe' if os.name=='nt' else 'bin/keytool')
            subprocess.run([str(keytool),'-genkeypair','-alias','persiansocks','-keyalg','RSA',
                '-storetype','PKCS12','-keystore',str(signing/'PersianSocks-release.p12'),
                '-storepass:file',str(password),'-dname','CN=Release test only','-validity','1'],
                check=True,capture_output=True)
            with self.assertRaisesRegex(RuntimeError,'differs'):
                security.verify_signing(Path(tmp),Path(args.jdk))

    def test_pin_matches_repository_public_certificate(self):
        der=ssl.PEM_cert_to_DER_cert((root/'signing/PersianSocks-public.pem').read_text())
        self.assertEqual(hashlib.sha256(der).hexdigest(),security.CERT_SHA256)

    def test_privacy_required_and_credentials_rejected(self):
        for url in ['', 'http://example.org/privacy','https://user:pass@example.org/privacy','https://example.org/\nprivacy']:
            with self.subTest(url=url),self.assertRaises(ValueError):security.validate_privacy_url(url)
        self.assertEqual(security.validate_privacy_url('https://example.org/privacy'),'https://example.org/privacy')

    def test_changed_native_artifact_rejected(self):
        security.verify_native(root)
        with tempfile.TemporaryDirectory() as tmp:
            libs=Path(tmp)/'app/libs';libs.mkdir(parents=True)
            (libs/'hev-socks5-tunnel.aar').write_bytes(b'modified')
            with self.assertRaisesRegex(RuntimeError,'differs'):security.verify_native(Path(tmp))

unittest.main(argv=['release_security_test'])
