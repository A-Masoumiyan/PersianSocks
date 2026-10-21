import ir.persiansocks.app.*;
import java.nio.file.*;
import java.util.*;
public final class VpnGateHarness {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static String config="client\ndev tun\nproto tcp\nremote example.org 443\ncipher AES-128-CBC\nauth SHA1\n<ca>\n-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----\n</ca>\n";
 public static void main(String[] args)throws Exception {
  VpnGateProfile p=VpnGateProfile.sanitize("8.8.8.8","jp",9,config);
  check(p.tcp&&p.port==443&&p.country.equals("JP"),"Metadata");check(p.config.contains("remote 8.8.8.8 443"),"Pin advertised IP");
  check(p.config.contains("remote-cert-tls server")&&p.config.contains("tls-version-min 1.2"),"Mandatory server TLS");
  check(p.config.contains("hand-window 60")&&p.config.contains("connect-retry-max 2")&&p.config.contains("tls-version-max 1.2")&&p.config.contains("cipher AES-128-CBC"),"SoftEther TLS compatibility and handshake budget");
  check(!p.endpoint().hasAnyService(),"Directory is not health evidence");
  check(ProxyEndpoint.parse(p.key()).type==ProxyEndpoint.Type.OPENVPN,"Stable protocol ID");
  for(String unsafe:new String[]{"up /evil","plugin /evil","management /tmp/evil unix","config /evil","http-proxy 1.2.3.4 80"}){try{VpnGateProfile.sanitize("8.8.8.8","JP",1,config+unsafe);throw new AssertionError("Accepted "+unsafe);}catch(java.io.IOException expected){}}
  try{VpnGateProfile.sanitize("127.0.0.1","JP",1,config);throw new AssertionError("Private endpoint accepted");}catch(java.io.IOException expected){}
  String csv="*vpn_servers\n#HostName,IP,Score,CountryShort,OpenVPN_ConfigData_Base64\nfixture,8.8.8.8,9,JP,"+Base64.getEncoder().encodeToString(config.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\n*\n";
  check(VpnGateProfile.parse(csv).size()==1,"CSV parse");
  for(String invalid:new String[]{"<html>Unavailable</html>","*vpn_servers\n*\n"}){try{VpnGateProfile.parse(invalid);throw new AssertionError("Accepted invalid directory");}catch(java.io.IOException expected){}}
  Path cache=Files.createTempDirectory("gate-fixture");try{VpnGateStore store=new VpnGateStore(cache.toFile());store.replace(Collections.singletonList(p));store.tested(p.key(),23,"");store.save();VpnGateStore restored=new VpnGateStore(cache.toFile());restored.load();check(restored.get(p.key()).portLatency==23,"Port result persists");check(!restored.get(p.key()).endpoint().hasAnyService(),"Port check is not VPN evidence");}finally{try(var files=Files.walk(cache)){files.sorted(Comparator.reverseOrder()).forEach(f->{try{Files.delete(f);}catch(Exception ignored){}});}}
  byte[] raw=config.getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=VpnGateProfile.sha256(raw);
  org.json.JSONObject document=new org.json.JSONObject().put("kind","vpngate-pool-config").put("schema_version",1).put("openvpn_config_sha256",hash).put("openvpn_config_bytes",raw.length).put("openvpn_config_base64",Base64.getEncoder().encodeToString(raw));
  byte[] bytes=document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  org.json.JSONObject row=new org.json.JSONObject().put("ip","8.8.8.8").put("country_code","JP").put("score",9).put("probe_reason","tcp").put("last_seen_at",java.time.Instant.now().toString()).put("probe_targets",new org.json.JSONArray().put(new org.json.JSONObject().put("ip","8.8.8.8").put("port",443))).put("openvpn_config_sha256",hash).put("openvpn_config_bytes",raw.length).put("config",new org.json.JSONObject().put("path","pool/configs/"+hash+".json").put("bytes",bytes.length).put("sha256",VpnGateProfile.sha256(bytes)));
  VpnGateProfile lazy=VpnGateProfile.candidate(row,"a".repeat(40));check(lazy.lazy()&&!lazy.endpoint().hasAnyService(),"Pool/cloud reachability is not local VPN proof");check(VpnGateProfile.fromJson(lazy.json()).lazy(),"Lazy metadata survives restart");
  check(!lazy.resolve(bytes).lazy(),"Verified configuration resolves on demand");byte[] broken=bytes.clone();broken[10]^=1;try{lazy.resolve(broken);throw new AssertionError("Corrupt config accepted");}catch(java.io.IOException expected){}
  row.getJSONObject("config").put("path","../../evil");try{VpnGateProfile.candidate(row,"a".repeat(40));throw new AssertionError("Arbitrary config URL accepted");}catch(java.io.IOException expected){}row.getJSONObject("config").put("path","pool/configs/"+hash+".json");
  row.put("probe_reason","udp");VpnGateProfile udp=VpnGateProfile.candidate(row,"a".repeat(40));check(udp.port==0&&!udp.tcp,"Do not invent a UDP port");check(VpnGateProfile.fromJson(udp.json()).port==0,"Unresolved UDP survives cache");
  Path merged=Files.createTempDirectory("gate-merged");try{VpnGateStore store=new VpnGateStore(merged.toFile());store.replace(Collections.singletonList(p));store.replace(Collections.singletonList(VpnGateProfile.sanitize("1.1.1.1","US",1,config)));check(store.snapshot().size()==2,"Partial refresh must retain other recent countries");store.vpnResult(p.key(),true,"");VpnGateStore restored=new VpnGateStore(merged.toFile());restored.load();check(restored.get(p.key()).vpnSuccess&&restored.get(p.key()).vpnTestedAt>0,"Real connection result journal survives stop/restart");check(!restored.get(p.key()).endpoint().hasAnyService(),"Only real TunnelVerifier response creates healthy endpoint");store.vpnResult(p.key(),false,"TLS failed");restored.load();check(!restored.get(p.key()).vpnSuccess&&restored.get(p.key()).vpnError.equals("TLS failed"),"Latest failure supersedes previous connection");}finally{try(var files=Files.walk(merged)){files.sorted(Comparator.reverseOrder()).forEach(f->{try{Files.delete(f);}catch(Exception ignored){}});}}
  if(args.length>0){var real=VpnGateProfile.parse(Files.readString(Path.of(args[0])));check(real.size()>0,"Actual directory compatibility");System.out.println("Compatible current VPN Gate profiles: "+real.size());}
  System.out.println("PASS: bounded VPN Gate parser, safe configuration, separate port/cache evidence");
 }
}
