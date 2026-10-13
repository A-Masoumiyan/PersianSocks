#!/usr/bin/env python3
"""Verify bilingual resource coverage and protect addresses/scan IDs from localization."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
res=root/'app/src/main/res'
fa={x.attrib['name']:x.text or '' for x in ET.parse(res/'values/ui_strings.xml').getroot()}
en={x.attrib['name']:x.text or '' for x in ET.parse(res/'values-en/ui_strings.xml').getroot()}
assert fa.keys()==en.keys(), 'Missing language keys'
assert all(v.strip('"').strip() for v in en.values()), 'Empty English copy'
assert not any(re.search('[\u0600-\u06ff]',v) for v in en.values()), 'Persian copy in English resources'
assert not any('علاقه' in v for v in fa.values()), 'Old saved-list wording'
assert 'ذخیره‌شده‌ها' in ''.join(fa.values()) and 'Saved' in ''.join(en.values())
java=root/'app/src/main/java/ir/persiansocks/app'
for p in java.glob('*.java'):
 for key in re.findall(r'R\.string\.(ui_\w+)',p.read_text()):assert key in fa, f'{p.name}: {key}'
main=(java/'MainActivity.java').read_text()
assert '"v" + AppLanguage.version(this)' in main and '0.22.0' not in main
assert 'languageBusy()' in main and 'ui.postDelayed(this::applyPendingLanguage' in main
assert 'View.LAYOUT_DIRECTION_RTL' not in main
assert 't.setGravity(AppLanguage.gravity()' in main and 't.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY)' in main
assert 'title.setGravity(AppLanguage.gravity())' in main and 'title.addView(brand,new LinearLayout.LayoutParams(-1,-2))' in main
assert 'AppLanguage.isolate(TrafficMeter.bytes(t.downloaded))' in main
assert 'AppLanguage.isolate(TrafficMeter.bytes(t.uploaded))' in main
language=(java/'AppLanguage.java').read_text()
assert 'config.setLocales' in language and 'config.setLayoutDirection' in language
assert '"fa".equals(code)&&!"en".equals(code)' in language
assert 'app-language' in language and 'getPackageInfo' in language
# Core persisted enums and protocol IDs stay stable across language changes.
results=(java/'ProxyResults.java').read_text()
assert all(x in results for x in ['FASTEST(', 'DOWNLOAD(', 'UDP_FIRST(', 'SOCKS(', 'HTTP('])
assert 'AppLanguage' not in results and 'AppLanguage' not in (java/'ProxyEndpoint.java').read_text()
assert 'if(dx<0)startProxyProbe(gestureProxy,true);else startSpeedTest(gestureProxy)' in main
assert 'row.setTranslationX(distance)' in main and 'gestureProxy.key()' in main
assert 'chooseProxy(endpoint)' in main and 'refreshPublicIp(false)' in main
assert 'savedPingButton' in main and 'savedSpeedButton' in main
print(f'PASS: {len(fa)} bilingual UI strings; resource references, saved labels, installed version, RTL/LTR and stable protocol IDs')
