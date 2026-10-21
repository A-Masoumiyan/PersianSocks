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
  check(!p.endpoint().hasAnyService(),"Directory is not health evidence");
  check(ProxyEndpoint.parse(p.key()).type==ProxyEndpoint.Type.OPENVPN,"Stable protocol ID");
  for(String unsafe:new String[]{"up /evil","plugin /evil","management /tmp/evil unix","config /evil","http-proxy 1.2.3.4 80"}){try{VpnGateProfile.sanitize("8.8.8.8","JP",1,config+unsafe);throw new AssertionError("Accepted "+unsafe);}catch(java.io.IOException expected){}}
  try{VpnGateProfile.sanitize("127.0.0.1","JP",1,config);throw new AssertionError("Private endpoint accepted");}catch(java.io.IOException expected){}
  String csv="*vpn_servers\n#HostName,IP,Score,CountryShort,OpenVPN_ConfigData_Base64\nfixture,8.8.8.8,9,JP,"+Base64.getEncoder().encodeToString(config.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\n*\n";
  check(VpnGateProfile.parse(csv).size()==1,"CSV parse");
  for(String invalid:new String[]{"<html>Unavailable</html>","*vpn_servers\n*\n"}){try{VpnGateProfile.parse(invalid);throw new AssertionError("Accepted invalid directory");}catch(java.io.IOException expected){}}
  Path cache=Files.createTempDirectory("gate-fixture");try{VpnGateStore store=new VpnGateStore(cache.toFile());store.replace(Collections.singletonList(p));store.tested(p.key(),23,"");store.save();VpnGateStore restored=new VpnGateStore(cache.toFile());restored.load();check(restored.get(p.key()).portLatency==23,"Port result persists");check(!restored.get(p.key()).endpoint().hasAnyService(),"Port check is not VPN evidence");}finally{try(var files=Files.walk(cache)){files.sorted(Comparator.reverseOrder()).forEach(f->{try{Files.delete(f);}catch(Exception ignored){}});}}
  if(args.length>0){var real=VpnGateProfile.parse(Files.readString(Path.of(args[0])));check(real.size()>0,"Actual directory compatibility");System.out.println("Compatible current VPN Gate profiles: "+real.size());}
  System.out.println("PASS: bounded VPN Gate parser, safe configuration, separate port/cache evidence");
 }
}
