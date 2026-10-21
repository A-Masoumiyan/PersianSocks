#!/usr/bin/env python3
"""Package the corresponding pinned native sources and all build scripts for APK releases."""
import hashlib,io,json,tarfile,urllib.request
from pathlib import Path
sources=json.loads(Path('scripts/openvpn-source-inputs.json').read_text())
output=Path('release-source');output.mkdir(exist_ok=True)
manifest={}
for name,source in sources.items():
 url=source['url']
 target=output/name
 if not target.is_file():
  request=urllib.request.Request(url,headers={'User-Agent':'PersianSocks-source-distribution'})
  with urllib.request.urlopen(request,timeout=60) as src,target.open('wb') as dest:
   while block:=src.read(65536):dest.write(block)
 if hashlib.sha256(target.read_bytes()).hexdigest()!=source['sha256']:raise ValueError('Source checksum mismatch: '+name)
 with tarfile.open(target,'r:gz') as archive:
  for entry in archive.getmembers():
   if entry.isfile() and entry.name.rsplit('/',1)[-1] in {'COPYING','LICENSE','LICENSE.txt','LICENSE.md','LICENSE.TXT'} and entry.size<150000:
    data=archive.extractfile(entry).read();prefix=name.replace('.tar.gz','')
    if entry.name.count('/')<=2:(Path('app/src/main/assets/licenses')/(prefix+'-'+entry.name.rsplit('/',1)[-1])).write_bytes(data)
 manifest[name]={'url':url,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}
(output/'sources.json').write_text(json.dumps(manifest,indent=2)+'\n')
(output/'BUILD.txt').write_text('The archives contain the complete sources used by the bundled OpenVPN executable. The android-build archive contains the original build workflow, NDK/tool versions, CMake overlay and scripts. Set OPENVPN_TAG=v2.7.8 and OPENSSL_VERSION=3.5.9, LZO_VERSION=2.10, LZ4_VERSION=1.10.0. Follow its README with Android NDK r29 for each ABI and static dependencies. No OpenVPN source was modified by PersianSocks. Executables and their SHA-256 are pinned in scripts/openvpn-inputs.json in the PersianSocks source release.\n')
with tarfile.open('PersianSocks-OpenVPN-corresponding-source.tar.gz','w:gz') as archive:
 for target in sorted(output.iterdir()):archive.add(target,arcname='openvpn-corresponding-source/'+target.name)
print('Packaged corresponding source and dependency license notices')
