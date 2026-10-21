#!/usr/bin/env python3
"""Host tests, with an isolated TLS fixture that never enters the application APK."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

p=argparse.ArgumentParser()
p.add_argument('--jdk',required=True)
p.add_argument('--json',required=True,help='org.json JVM jar, used only by tests')
p.add_argument('--long-stream',action='store_true',help='Also run simultaneous real upload/download for over five minutes')
a=p.parse_args()
root=Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,str(root/'tests/check-localization.py')],check=True)
jdk=Path(a.jdk).resolve();json=Path(a.json).resolve()
names=['OpenVpnReachability','VpnGateScanPlan','VpnGateProfile','VpnGateStore','DomainRoutes','SourceDocuments','ConnectionFastPath','ScanCapacityGovernor','QualityStats','AdaptiveScanPolicy','UdpRoundTrip','PhysicalRouteTracker','ConnectionPreflight','IdleStopPolicy','ScanProbePolicy','TestDownloadPolicy','IconRetryPolicy','DnsMessage','IconPolicy','UpdateRelease','ProbeFailurePolicy','PublicIpEvidence','ProxyEndpoint','ProxyTransport','ProxyResults','ServiceTargets','CountryLookup','Socks5Probe','ProxyScanner',
    'SourceCatalog','HProxySource','ProtectedSocksGateway','TunnelVerifier','AutoScanSelection','FailoverPolicy','QuickControlState','TrafficMeter','DownloadSpeedTest','DistributionPolicy','SingleProxyTest','ProxyHttpConnection','ProxyTestResult','ScanCheckpoint','ScanFiles','PreparedScanner','ProxyIndex','ProxyCacheFile','SplitTunnelPolicy','FilteredSpeedBatch']
with tempfile.TemporaryDirectory(prefix='persiansocks-tests-') as tmp:
    work=Path(tmp);classes=work/'classes';classes.mkdir();tls=work/'tls.p12'
    def run(*args):subprocess.run([str(x) for x in args],check=True)
    run(jdk/'bin/keytool','-genkeypair','-alias','tests','-keyalg','RSA','-keysize','2048','-validity','2',
        '-storetype','PKCS12','-keystore',tls,'-storepass','test-only-password','-keypass','test-only-password',
        '-dname','CN=web.telegram.org','-ext','SAN=dns:web.telegram.org,dns:www.youtube.com,dns:www.instagram.com,dns:www.facebook.com,dns:speed.cloudflare.com,dns:localhost,ip:127.0.0.1')
    flag=work/'BuildConfig.java'
    flag.write_text('package ir.persiansocks.app; public final class BuildConfig { public static final boolean PLAY_DISTRIBUTION = false; }')
    src=[root/'app/src/main/java/ir/persiansocks/app'/(name+'.java') for name in names]+list((root/'tests').glob('*.java'))+[flag]
    run(jdk/'bin/javac','-encoding','UTF-8','-cp',json,'-d',classes,*src)
    for name,extra in [('VpnGateHarness',[]),('ir.persiansocks.app.GateReachabilityHarness',[]),('AndroidParityHarness',[root/'app/src/main/assets/ip-country.bin']),('ScanGovernorHarness',[]),('ir.persiansocks.app.UdpQualityHarness',[]),('ir.persiansocks.app.AdaptiveScanHarness',[]),('ir.persiansocks.app.ScanModesHarness',[]),('HProxySourceHarness',[]),('StreamRecoveryHarness',[tls]),('ScanHarness',[tls]),('FeatureHarness',[root/'app/src/main/assets/ip-country.bin']),
            ('ReconnectPriorityHarness',[tls]),('ConnectionScopeHarness',[tls]),('ConfiguredTargetsHarness',[tls]),('UpdateReleaseHarness',[]),('DnsIconHarness',[]),('ScanPressureHarness',[]),('ScanJournalHarness',[]),('ScanConcurrencyHarness',[]),('ControlsHarness',[]),('ResultsHarness',[]),('DownloadRankingHarness',[]),('PingFilterHarness',[]),('SplitTunnelHarness',[]),('FilteredSpeedHarness',[]),('MaxScanHarness',[]),('ProtocolTrafficHarness',[tls]),('OptionalTlsHarness',[tls]),('SingleProxyHarness',[tls]),('ResultDisplayHarness',[]),('ScanResumeHarness',[tls]),('LargeCacheHarness',[]),('ScanRouteHarness',[tls]),('BatchCompactionHarness',[]),('RetestEvidenceHarness',[]),('AvailabilityHarness',[]),('GatewaySecurityHarness',[]),('DownloadFallbackHarness',[])]:
        run(jdk/'bin/java',*(['-Xmx128m'] if name in ['LargeCacheHarness','ScanPressureHarness'] else []),'-cp',str(classes)+os.pathsep+str(json),name,*extra)
    # Compile the whole transport stack again for Play; javac inlines the immutable flag.
    if a.long_stream:run(jdk/'bin/java','-cp',str(classes)+os.pathsep+str(json),'StreamRecoveryHarness',tls,'--long')
    play=work/'play-classes';play.mkdir()
    flag.write_text('package ir.persiansocks.app; public final class BuildConfig { public static final boolean PLAY_DISTRIBUTION = true; }')
    run(jdk/'bin/javac','-encoding','UTF-8','-cp',json,'-d',play,*src)
    run(jdk/'bin/java','-cp',str(play)+os.pathsep+str(json),'PlaySecurityHarness',tls)
