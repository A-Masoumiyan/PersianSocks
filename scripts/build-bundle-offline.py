#!/usr/bin/env python3
"""Build a dependency-complete signed AAB through the pinned Gradle pipeline, offline."""
import argparse,os,subprocess,shutil,re
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
for name in ['sdk','jdk']:p.add_argument('--'+name,required=True)
for name in ['aapt2','bundletool']:p.add_argument('--'+name,help='Legacy option; Gradle provides the packager')
p.add_argument('--profile',choices=['personal','play'],default='personal')
p.add_argument('--privacy-policy-url',default='');p.add_argument('--play-query-all-packages-approved',action='store_true')
a=p.parse_args();root=Path(__file__).resolve().parents[1]
env=dict(os.environ,JAVA_HOME=str(Path(a.jdk).resolve()),ANDROID_HOME=str(Path(a.sdk).resolve()))
args=['bash',str(root/'gradlew'),'--offline','--no-daemon',':app:bundle'+a.profile.title()+'Release']
if a.privacy_policy_url:args.append('-PprivacyPolicyUrl='+a.privacy_policy_url)
if a.play_query_all_packages_approved:args.append('-PplayQueryAllPackagesApproved=true')
subprocess.run(args,cwd=root,env=env,check=True)
version=re.search(r"versionName '([^']+)'",(root/'app/build.gradle').read_text()).group(1)
source=root/('app/build/outputs/bundle/'+a.profile+'Release/app-'+a.profile+'-release.aab')
out=root/'app/build/outputs/bundle/release'/('PersianSocks-'+version+('-Play' if a.profile=='play' else '')+'.aab');out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,out);print(out)
