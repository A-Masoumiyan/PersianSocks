#!/usr/bin/env python3
"""Build a signed release APK with official SDK tools; no Gradle downloads."""
import argparse
import os
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--privacy-policy-url', default='', help='Public HTTPS privacy policy URL for the publisher')
p.add_argument('--profile', choices=['personal','play'], default='personal')
p.add_argument('--sdk', required=True)
p.add_argument('--jdk', required=True)
p.add_argument('--aapt2', help='Optional Google Maven AAPT2 binary')
a = p.parse_args()
if a.privacy_policy_url:
    from urllib.parse import urlsplit
    policy_url=urlsplit(a.privacy_policy_url)
    if policy_url.scheme!='https' or not policy_url.hostname or policy_url.username or any(ord(c)<33 for c in a.privacy_policy_url):
        raise ValueError('Privacy policy URL must be a public HTTPS URL without embedded credentials')
root = Path(__file__).resolve().parents[1]
sdk, jdk = Path(a.sdk).resolve(), Path(a.jdk).resolve()
bt = sdk / 'build-tools/35.0.0'
gradle = (root / 'app/build.gradle').read_text()
compile_sdk = re.search(r'compileSdk (\d+)',gradle).group(1)
target_sdk = re.search(r'targetSdk (\d+)',gradle).group(1)
android = sdk / ('platforms/android-' + compile_sdk + '/android.jar')
aapt = Path(a.aapt2).resolve() if a.aapt2 else bt / 'aapt2'
work = root / ('app/build/manual-release-' + a.profile)
if work.exists():
    shutil.rmtree(work)
for folder in ['generated', 'classes', 'dex']:
    (work / folder).mkdir(parents=True, exist_ok=True)
config = work / 'generated/ir/persiansocks/app/BuildConfig.java'
config.parent.mkdir(parents=True, exist_ok=True)
config.write_text('package ir.persiansocks.app; public final class BuildConfig { public static final boolean PLAY_DISTRIBUTION = ' + ('true' if a.profile == 'play' else 'false') + '; public static final String PRIVACY_POLICY_URL = ' + json.dumps(a.privacy_policy_url) + '; }\n')
(work / 'profile.json').write_text(json.dumps({'profile': a.profile}))
env = os.environ.copy()
env['JAVA_HOME'] = str(jdk)
env['PATH'] = str(jdk / 'bin') + os.pathsep + env['PATH']
env.pop('JAVA_TOOL_OPTIONS', None)

def run(*args):
    subprocess.run([str(x) for x in args], check=True, cwd=root, env=env)

gradle = (root / 'app/build.gradle').read_text()
version = re.search(r"versionName '([^']+)'", gradle).group(1)
code = re.search(r'versionCode (\d+)', gradle).group(1)
ns = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ns)
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml')
m = manifest.getroot()
m.set('package', 'ir.persiansocks.app')
m.set('{%s}versionCode' % ns, code)
m.set('{%s}versionName' % ns, version)
uses = ET.Element('uses-sdk', {'{%s}minSdkVersion' % ns: '29', '{%s}targetSdkVersion' % ns: target_sdk})
m.insert(0, uses)
m.find('application').set('{%s}extractNativeLibs' % ns, 'false')
for entry in m.iter():
    if entry.get('{%s}value' % ns) == '${playDistribution}': entry.set('{%s}value' % ns,'true' if a.profile == 'play' else 'false')
manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
run(aapt, 'compile', '--dir', root / 'app/src/main/res', '-o', work / 'resources.zip')
run(aapt, 'link', '-o', work / 'unsigned.apk', '-I', android,
    '--manifest', work / 'AndroidManifest.xml', '--java', work / 'generated',
    '-A', root / 'app/src/main/assets', work / 'resources.zip')
aar = root / 'app/libs/hev-socks5-tunnel.aar'
with zipfile.ZipFile(aar) as z:
    (work / 'hev-classes.jar').write_bytes(z.read('classes.jar'))
sources = sorted((root / 'app/src/main/java').rglob('*.java'))
sources += sorted((work / 'generated').rglob('*.java'))
run(jdk / 'bin/javac', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
    '-classpath', str(android) + os.pathsep + str(work / 'hev-classes.jar'),
    '-d', work / 'classes', *sources)
run(jdk / 'bin/jar', '--create', '--file', work / 'app-classes.jar', '-C', work / 'classes', '.')
run(jdk / 'bin/java', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8',
    '--release', '--min-api', '29', '--lib', android, '--output', work / 'dex',
    work / 'app-classes.jar', work / 'hev-classes.jar')
with zipfile.ZipFile(work / 'unsigned.apk', 'a') as out:
    if 'AndroidManifest.xml' not in out.namelist() or 'resources.arsc' not in out.namelist():
        raise RuntimeError('Resource package lost manifest or resource table; refusing to sign.')
    for dex in sorted((work / 'dex').glob('*.dex')):
        out.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(aar) as native:
        for name in native.namelist():
            if name.startswith('jni/') and name.endswith('.so'):
                out.writestr('lib/' + name[4:], native.read(name), compress_type=zipfile.ZIP_STORED)
run(bt / 'zipalign', '-P', '16', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
signing = root / 'signing'
signing.mkdir(mode=0o700, exist_ok=True)
password = signing / 'password.txt'
key = signing / 'PersianSocks-release.p12'
if not key.is_file() or not password.is_file():
    raise RuntimeError(
        'Official signing key or password missing. Retrieve the approved key '
        'from the private PersianSocks Drive backup. Do not generate a replacement.'
    )
# Protect the new signing lineage from accidental key changes.
expected_cert_sha256 = '1F:2E:E9:12:EE:98:27:77:49:E2:EB:A1:EA:BC:F6:C9:69:A2:05:05:16:1F:7D:CE:DA:9E:8B:3B:EE:67:61:25'
verification = subprocess.run(
    [str(jdk / 'bin/keytool'), '-list', '-v', '-storetype', 'PKCS12',
     '-keystore', str(key), '-storepass:file', str(password), '-alias', 'persiansocks'],
    check=True, capture_output=True, text=True, env=env
)
fingerprint = re.search(r'SHA256:\s*([0-9A-Fa-f:]+)', verification.stdout)
if not fingerprint or fingerprint.group(1).upper() != expected_cert_sha256:
    raise RuntimeError('Signing certificate does not match the approved PersianSocks 2026-10-13 key.')
output = root / 'app/build/outputs/apk/release' / ('PersianSocks-' + version + ('-Play' if a.profile == 'play' else '') + '.apk')
output.parent.mkdir(parents=True, exist_ok=True)
run(bt / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'persiansocks',
    '--ks-pass', 'file:' + str(password),
    '--out', output, work / 'aligned.apk')
run(bt / 'apksigner', 'verify', '--verbose', '--print-certs', output)
run(bt / 'zipalign', '-c', '-P', '16', '4', output)
print('APK:', output)
