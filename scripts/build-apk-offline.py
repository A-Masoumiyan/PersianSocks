#!/usr/bin/env python3
"""Build the dependency-complete signed APK from a populated Gradle cache, offline."""
import argparse,os,subprocess,shutil,re
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk',required=True);p.add_argument('--jdk',required=True)
p.add_argument('--profile',choices=['personal','play'],default='personal')
p.add_argument('--privacy-policy-url',default='');p.add_argument('--play-query-all-packages-approved',action='store_true')
p.add_argument('--aapt2',help='Legacy option; Gradle selects its pinned AAPT2')
a=p.parse_args();root=Path(__file__).resolve().parents[1]
env=dict(os.environ,JAVA_HOME=str(Path(a.jdk).resolve()),ANDROID_HOME=str(Path(a.sdk).resolve()))
task=':app:assemble'+a.profile.title()+'Release'
args=['bash',str(root/'gradlew'),'--offline','--no-daemon',task]
if a.privacy_policy_url:args.append('-PprivacyPolicyUrl='+a.privacy_policy_url)
if a.play_query_all_packages_approved:args.append('-PplayQueryAllPackagesApproved=true')
subprocess.run(args,cwd=root,env=env,check=True)
version=re.search(r"versionName '([^']+)'",(root/'app/build.gradle').read_text()).group(1)
source=root/('app/build/outputs/apk/'+a.profile+'/release/app-'+a.profile+'-release.apk')
out=root/'app/build/outputs/apk/release'/('PersianSocks-'+version+('-Play' if a.profile=='play' else '')+'.apk');out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,out)
subprocess.run(['python3',str(root/'scripts/audit-release-apk.py'),str(out),'--sdk',a.sdk,'--profile',a.profile],cwd=root,env=env,check=True)
print(out)
