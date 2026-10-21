package ir.persiansocks.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Bounded VPN Gate CSV ingestion. Directory configuration is data, never executable options. */
public final class VpnGateProfile {
 public static final int MAX_DIRECTORY_BYTES=8*1024*1024,MAX_PROFILES=1200,MAX_CONFIG=96*1024;
 public final String ip,country,config;public final int port;public final boolean tcp;public final long score;
 public long portLatency=-1,portTestedAt;public String lastError="";
 private VpnGateProfile(String ip,String country,int port,boolean tcp,long score,String config){this.ip=ip;this.country=country;this.port=port;this.tcp=tcp;this.score=score;this.config=config;}
 public String key(){return "openvpn://"+ip+":"+port;}
 public ProxyEndpoint endpoint(){return new ProxyEndpoint(ip,port,portLatency,false,0,0,0,Collections.emptySet(),Collections.emptySet(),country,ProxyEndpoint.Type.OPENVPN).withLatency(portLatency,"tcp-port");}
 public JSONObject json(){JSONObject o=new JSONObject();try{o.put("ip",ip).put("country",country).put("port",port).put("tcp",tcp).put("score",score).put("config",config).put("portLatency",portLatency).put("portTestedAt",portTestedAt).put("error",lastError);}catch(Exception ignored){}return o;}
 public static VpnGateProfile fromJson(JSONObject o)throws IOException{try{VpnGateProfile p=sanitize(o.getString("ip"),o.optString("country"),o.optLong("score"),o.getString("config"));p.portLatency=o.optLong("portLatency",-1);p.portTestedAt=o.optLong("portTestedAt");p.lastError=o.optString("error");return p;}catch(Exception e){throw new IOException("Invalid cached profile",e);}}
 public static List<VpnGateProfile> parse(String csv)throws IOException{
  if(csv.length()>MAX_DIRECTORY_BYTES||!csv.contains("OpenVPN_ConfigData_Base64"))throw new IOException("VPN Gate directory is missing or invalid");
  List<List<String>> rows=csv(csv);Map<String,VpnGateProfile> result=new LinkedHashMap<>();Map<String,Integer> fields=null;
  for(List<String> row:rows){if(row.isEmpty())continue;String first=row.get(0).replace("\ufeff","");if(first.startsWith("*"))continue;
   if(first.startsWith("#HostName")){fields=new HashMap<>();for(int i=0;i<row.size();i++)fields.put(row.get(i).replaceFirst("^#",""),i);continue;}
   if(fields==null)continue;
   try{String data=get(row,fields,"OpenVPN_ConfigData_Base64");if(data.length()>MAX_CONFIG*2)continue;String text=new String(Base64.getDecoder().decode(data),StandardCharsets.UTF_8);VpnGateProfile p=sanitize(get(row,fields,"IP"),get(row,fields,"CountryShort"),Long.parseLong(get(row,fields,"Score")),text);result.put(p.key(),p);}catch(Exception invalid){/* A bad volunteer row cannot discard the other rows. */}
   if(result.size()>=MAX_PROFILES)break;
  }
  if(result.isEmpty())throw new IOException("VPN Gate returned no compatible OpenVPN profiles");return new ArrayList<>(result.values());
 }
 private static String get(List<String> row,Map<String,Integer> h,String key){Integer i=h.get(key);if(i==null||i>=row.size())throw new IllegalArgumentException("Missing field");return row.get(i);}
 static List<List<String>> csv(String s)throws IOException{
  List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false;
  for(int i=0;i<=s.length();i++){char c=i==s.length()?'\n':s.charAt(i);if(c=='"'){if(quoted&&i+1<s.length()&&s.charAt(i+1)=='"'){field.append('"');i++;}else quoted=!quoted;}
   else if(!quoted&&(c==','||c=='\n'||c=='\r')){row.add(field.toString());field.setLength(0);if(row.size()>32)throw new IOException("Too many CSV columns");if(c!=','){if(c=='\r'&&i+1<s.length()&&s.charAt(i+1)=='\n')i++;rows.add(row);row=new ArrayList<>();if(rows.size()>MAX_PROFILES+20)break;}}
   else field.append(c);if(field.length()>MAX_CONFIG*2)throw new IOException("CSV field is too large");
  }if(quoted)throw new IOException("Unterminated CSV field");return rows;
 }
 public static VpnGateProfile sanitize(String ip,String country,long score,String raw)throws IOException{
  ProxyEndpoint address=ProxyEndpoint.parse(ip+":1");if(address==null||!address.isPublicIpv4()||raw.length()>MAX_CONFIG||raw.indexOf('\0')>=0)throw new IOException("Invalid VPN Gate endpoint");
  String proto="udp",cipher="AES-128-CBC",auth="SHA1",ca="",clientCert="",clientKey="",authKey="",cryptKey="",direction="";List<String[]> remotes=new ArrayList<>();String[] lines=raw.replace("\r","").split("\n");
  for(int i=0;i<lines.length;i++){String line=lines[i].trim();if(line.isEmpty()||line.startsWith("#")||line.startsWith(";"))continue;
   if(line.startsWith("<")){String tag=line.toLowerCase(Locale.ROOT);if(!tag.equals("<ca>")&&!tag.equals("<tls-auth>")&&!tag.equals("<tls-crypt>")&&!tag.equals("<cert>")&&!tag.equals("<key>"))throw new IOException("Unsupported inline block");String end=tag.replace("<","</");StringBuilder block=new StringBuilder();while(++i<lines.length&&!lines[i].trim().equalsIgnoreCase(end)){block.append(lines[i]).append('\n');if(block.length()>64*1024)throw new IOException("Certificate block too large");}if(i==lines.length)throw new IOException("Incomplete certificate block");if(tag.equals("<ca>"))ca=block.toString();else if(tag.equals("<tls-auth>"))authKey=block.toString();else if(tag.equals("<cert>"))clientCert=block.toString();else if(tag.equals("<key>"))clientKey=block.toString();else cryptKey=block.toString();continue;}
   String[] parts=line.split("\\s+");String option=parts[0].toLowerCase(Locale.ROOT);if(option.equals("proto")&&parts.length>1)proto=parts[1];if(option.equals("remote")&&parts.length>2)remotes.add(parts);
   if((option.equals("cipher")||option.equals("data-ciphers-fallback"))&&parts.length>1)cipher=parts[1];if(option.equals("auth")&&parts.length>1)auth=parts[1];if(option.equals("key-direction")&&parts.length>1)direction=parts[1];
   if(option.equals("dev")&&parts.length>1&&!parts[1].equals("tun"))throw new IOException("Only TUN profiles are supported");
   if(Arrays.asList("script-security","up","down","route-up","plugin","tls-verify","management","http-proxy","socks-proxy","iproute","config","cd","chroot","daemon","remote-cert-ku","tls-cert-profile").contains(option))throw new IOException("Unsafe profile option: "+option);
  }
  if(!ca.contains("-----BEGIN CERTIFICATE-----")||!ca.contains("-----END CERTIFICATE-----")||remotes.isEmpty())throw new IOException("Missing server CA or endpoint");
  if(!Arrays.asList("AES-128-CBC","AES-256-CBC","AES-128-GCM","AES-256-GCM","CHACHA20-POLY1305").contains(cipher))throw new IOException("Unsupported legacy cipher");
  if(!Arrays.asList("SHA1","SHA256","SHA384","SHA512").contains(auth))throw new IOException("Unsupported authentication digest");
  String[] chosen=remotes.get(0);String transport=chosen.length>3?chosen[3]:proto;boolean tcp=transport.startsWith("tcp");if(!tcp&&!transport.startsWith("udp"))throw new IOException("Unsupported OpenVPN transport");int port;try{port=Integer.parseInt(chosen[2]);}catch(Exception e){throw new IOException("Invalid OpenVPN port");}if(port<1||port>65535)throw new IOException("Invalid OpenVPN port");
  String text="client\ndev tun\nproto "+(tcp?"tcp-client":"udp")+"\nremote "+ip+" "+port+"\nnobind\nauth-user-pass\nauth-nocache\nremote-cert-tls server\ntls-version-min 1.2\nresolv-retry 0\nconnect-retry-max 1\nconnect-timeout 10\nserver-poll-timeout 12\nhand-window 20\nping 10\nping-restart 30\nverb 3\ndata-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC:AES-256-CBC\ndata-ciphers-fallback "+cipher+"\nauth "+auth+"\nallow-compression asym\n<ca>\n"+ca+"</ca>\n";
  if(!clientCert.isEmpty()&&!clientKey.isEmpty())text+="<cert>\n"+clientCert+"</cert>\n<key>\n"+clientKey+"</key>\n";
  if(!authKey.isEmpty()){if(!direction.equals("0")&&!direction.equals("1"))throw new IOException("Missing TLS key direction");text+="key-direction "+direction+"\n<tls-auth>\n"+authKey+"</tls-auth>\n";}if(!cryptKey.isEmpty())text+="<tls-crypt>\n"+cryptKey+"</tls-crypt>\n";
  return new VpnGateProfile(ip,country.matches("[A-Za-z]{2}")?country.toUpperCase(Locale.ROOT):"",port,tcp,Math.max(0,score),text);
 }
}
