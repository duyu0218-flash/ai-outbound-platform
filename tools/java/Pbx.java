import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import org.w3c.dom.*;

/** Native PBX bootstrap/ESL probe. No interpreter, shell expansion or credential logging. */
public class Pbx {
 static String secret(Path p)throws Exception {String s=Files.readString(p).strip();if(!s.matches("[A-Za-z0-9]{24,256}"))throw new IllegalArgumentException("ESL secret must be 24..256 ASCII alphanumeric characters");return s;}
 static String cidr(String value)throws Exception {
  if(value==null||!value.matches("[0-9.]+/[0-9]{1,2}"))throw new IllegalArgumentException("Explicit private IPv4 CIDR required");String[] parts=value.split("/");int prefix=Integer.parseInt(parts[1]);if(prefix<16||prefix>32)throw new IllegalArgumentException("Use /16 or narrower");String[] octets=parts[0].split("\\.",-1);if(octets.length!=4)throw new IllegalArgumentException("Invalid IPv4 address");long ip=0;for(String p:octets){int n=Integer.parseInt(p);if(n<0||n>255||!p.equals(Integer.toString(n)))throw new IllegalArgumentException("Invalid IPv4 octet");ip=(ip<<8)|n;}long mask=(0xffffffffL<<(32-prefix))&0xffffffffL;if((ip&mask)!=ip)throw new IllegalArgumentException("CIDR must be a network address");boolean priv=(ip>>>24)==10||(ip>>>20)==0xac1||(ip>>>16)==0xc0a8;if(!priv)throw new IllegalArgumentException("Only RFC1918 subnet permitted");return value;
 }
 static void render(Path input,Path output,Path credential,String network)throws Exception{
  String password=secret(credential),allowed=cidr(network);var f=DocumentBuilderFactory.newInstance();f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");f.setXIncludeAware(false);f.setExpandEntityReferences(false);var document=f.newDocumentBuilder().parse(input.toFile());int networks=0,passwords=0;
  var nodes=document.getElementsByTagName("node");for(int i=0;i<nodes.getLength();i++){Element n=(Element)nodes.item(i);if(n.getAttribute("cidr").equals("__MEDIA_CIDR__")){n.setAttribute("cidr",allowed);networks++;}}
  nodes=document.getElementsByTagName("param");for(int i=0;i<nodes.getLength();i++){Element n=(Element)nodes.item(i);if(n.getAttribute("value").equals("__ESL_PASSWORD__")){n.setAttribute("value",password);passwords++;}}
  if(networks!=1||passwords!=1)throw new IllegalArgumentException("Expected exactly one network/password placeholder");Files.createDirectories(output.toAbsolutePath().getParent());var tf=TransformerFactory.newInstance();tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");Path temp=Files.createTempFile(output.getParent(),"pbx-",".xml");try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));String source=Files.readString(input);if(source.split("__MEDIA_CIDR__",-1).length!=2||source.split("__ESL_PASSWORD__",-1).length!=2)throw new IllegalArgumentException("Ambiguous placeholder occurrence");Files.writeString(temp,source.replace("__MEDIA_CIDR__",allowed).replace("__ESL_PASSWORD__",password),StandardCharsets.UTF_8);Files.move(temp,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
 }
 static Map<String,String> frame(InputStream in)throws Exception {Map<String,String> h=new HashMap<>();int total=0;for(;;){var b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1&&c!='\n'){if(++total>65536)throw new IOException("ESL headers too large");b.write(c);}if(c==-1)throw new EOFException();String line=b.toString(StandardCharsets.UTF_8).strip();if(line.isEmpty())break;int colon=line.indexOf(':');if(colon<1)throw new IOException("Malformed ESL header");if(h.put(line.substring(0,colon),line.substring(colon+1).strip())!=null)throw new IOException("Duplicate ESL header");}int n=Integer.parseInt(h.getOrDefault("Content-Length","0"));if(n<0||n>1048576)throw new IOException("Invalid ESL content length");byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();h.put("body",new String(b,StandardCharsets.UTF_8));return h;}
 static void health(Path credential,String host,int port)throws Exception {String password=secret(credential);try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),3000);s.setSoTimeout(3000);var in=s.getInputStream();var out=s.getOutputStream();if(!frame(in).getOrDefault("Content-Type","").equals("auth/request"))throw new IOException("Missing ESL auth challenge");out.write(("auth "+password+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();if(!frame(in).getOrDefault("Reply-Text","").startsWith("+OK"))throw new IOException("ESL authentication rejected");out.write("api module_exists mod_openai_audio_stream\n\n".getBytes(StandardCharsets.UTF_8));out.flush();if(!frame(in).get("body").strip().equals("true"))throw new IOException("VoiSmart module unavailable");}}
 public static void main(String[] args)throws Exception {
  switch(args[0]){
   case "health":health(Path.of(args.length>1?args[1]:"/run/secrets/voismart_esl_password"),args.length>2?args[2]:"127.0.0.1",args.length>3?Integer.parseInt(args[3]):8021);break;
   case "render":render(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),args[4]);break;
   case "start":for(String p:List.of("conf","log","db","run"))Files.createDirectories(Path.of("/tmp/fs-"+p));render(Path.of("/opt/media-config/freeswitch.xml"),Path.of("/tmp/fs-conf/freeswitch.xml"),Path.of("/run/secrets/voismart_esl_password"),System.getenv("MEDIA_ESL_ALLOWED_CIDR"));Process child=new ProcessBuilder("freeswitch","-nf","-nonat","-conf","/tmp/fs-conf","-log","/tmp/fs-log","-db","/tmp/fs-db","-run","/tmp/fs-run").inheritIO().start();Runtime.getRuntime().addShutdownHook(new Thread(()->{child.destroy();try{if(!child.waitFor(10,TimeUnit.SECONDS))child.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));System.exit(child.waitFor());break;
   default:throw new IllegalArgumentException("Use start|render|health");
  }
 }
}
