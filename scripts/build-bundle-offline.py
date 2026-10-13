#!/usr/bin/env python3
"""Package a signed AAB using the validated APK build's code and Google Maven AAPT2."""
import argparse
import importlib.util
from pathlib import Path
import json
import os
import re
import subprocess
import zipfile

p=argparse.ArgumentParser()
for name in ['sdk','jdk','aapt2','bundletool']:p.add_argument('--'+name,required=True)
p.add_argument('--profile',choices=['personal','play'],default='personal')
a=p.parse_args()
root=Path(__file__).resolve().parents[1]
sdk,jdk,aapt,bundle=map(lambda x:Path(x).resolve(),[a.sdk,a.jdk,a.aapt2,a.bundletool])
apkwork=root/('app/build/manual-release-'+a.profile)
if json.loads((apkwork/'profile.json').read_text())['profile']!=a.profile:raise RuntimeError('Wrong APK compilation profile')
profile=json.loads((apkwork/"profile.json").read_text())
spec=importlib.util.spec_from_file_location("release_security",root/"scripts/release-security.py")
security=importlib.util.module_from_spec(spec);spec.loader.exec_module(security)
security.verify_inputs(root,jdk,profile.get("privacyPolicyUrl",""),a.profile,profile.get("playPermissionApproved",False))
work=root/('app/build/manual-bundle-'+a.profile);work.mkdir(parents=True,exist_ok=True)
gradle=(root/'app/build.gradle').read_text()
version=re.search(r"versionName '([^']+)'",gradle).group(1)
compile_sdk=re.search(r'compileSdk (\d+)',gradle).group(1)
android=sdk/('platforms/android-'+compile_sdk+'/android.jar')
env=os.environ.copy();env.pop('JAVA_TOOL_OPTIONS',None)
def run(*args):subprocess.run([str(x) for x in args],check=True,env=env,cwd=root)
run(aapt,'compile','--dir',root/'app/src/main/res','-o',work/'resources.zip')
run(aapt,'link','--proto-format','-o',work/'proto.apk','-I',android,
    '--manifest',apkwork/'AndroidManifest.xml','--java',work/'generated',
    '-A',root/'app/src/main/assets',work/'resources.zip')
original=(apkwork/'generated/ir/persiansocks/app/R.java').read_text()
generated=(work/'generated/ir/persiansocks/app/R.java').read_text()
if original!=generated:raise RuntimeError('Bundle resource identifiers differ from compiled application code')
with zipfile.ZipFile(work/'base.zip','w',zipfile.ZIP_DEFLATED) as out:
    with zipfile.ZipFile(work/'proto.apk') as src:
        for name in src.namelist():
            if name=='AndroidManifest.xml':out.writestr('manifest/'+name,src.read(name))
            elif name=='resources.pb' or name.startswith(('res/','assets/')):out.writestr(name,src.read(name))
    for dex in (apkwork/'dex').glob('*.dex'):out.write(dex,'dex/'+dex.name)
    with zipfile.ZipFile(root/'app/libs/hev-socks5-tunnel.aar') as src:
        for name in src.namelist():
            if name.startswith('jni/') and name.endswith('.so'):out.writestr('lib/'+name[4:],src.read(name))
config=work/'BundleConfig.json'
config.write_text(json.dumps({'optimizations':{'uncompressNativeLibraries':{'enabled':True,'alignment':'PAGE_ALIGNMENT_16K'}}}))
output=root/'app/build/outputs/bundle/release'/('PersianSocks-'+version+('-Play' if a.profile=='play' else '')+'.aab')
output.parent.mkdir(parents=True,exist_ok=True)
output.unlink(missing_ok=True)
java=jdk/'bin/java'
run(java,'-jar',bundle,'build-bundle','--modules='+str(work/'base.zip'),'--output='+str(output),'--config='+str(config))
signing=root/'signing';key=signing/'PersianSocks-release.p12';password=signing/'password.txt'
run(jdk/'bin/jarsigner','-keystore',key,'-storepass:file',password,'-keypass:file',password,
    '-sigalg','SHA256withRSA','-digestalg','SHA-256',output,'persiansocks')
run(jdk/'bin/jarsigner','-verify',output)
run(java,'-jar',bundle,'validate','--bundle='+str(output))
run(java,'-jar',bundle,'build-apks','--bundle='+str(output),'--output='+str(work/'universal.apks'),
    '--mode=universal','--ks='+str(key),'--ks-key-alias=persiansocks','--ks-pass=file:'+str(password),
    '--key-pass=file:'+str(password),'--aapt2='+str(aapt),'--overwrite')
with zipfile.ZipFile(work/'universal.apks') as src:(work/'universal.apk').write_bytes(src.read('universal.apk'))
run(sdk/'build-tools/35.0.0/apksigner','verify','--print-certs',work/'universal.apk')
run(sdk/'build-tools/35.0.0/zipalign','-c','-P','16','4',work/'universal.apk')
print('Validated bundle:',output)
