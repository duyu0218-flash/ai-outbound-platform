package com.duozhi.outbound;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Redis-compatible media keys; development-only in-process fallback. */
@Service
public class ExpiringValues {
    private final Config config;
    private final SSLSocketFactory tlsFactory;
    private final Map<String,Entry> memory=new ConcurrentHashMap<>();
    private static class Entry{final String value;final long expires;Entry(String value,int ttl){this.value=value;this.expires=System.currentTimeMillis()+1000L*ttl;}}
    @Autowired
    public ExpiringValues(Config config){this(config,(SSLSocketFactory)SSLSocketFactory.getDefault());}
    ExpiringValues(Config config,SSLSocketFactory tlsFactory){this.config=config;this.tlsFactory=tlsFactory;}
    public String get(String key){String endpoint=config.get("REDIS_URL","");if(!endpoint.isEmpty())try{return (String)command(endpoint,"GET",key);}catch(RuntimeException e){if(config.production())throw new ApiError(503,"Redis is required for WebRTC media sessions");}else if(config.production())throw new ApiError(503,"Redis is required for WebRTC media sessions");Entry e=memory.get(key);if(e==null)return null;if(e.expires<=System.currentTimeMillis()){memory.remove(key,e);return null;}return e.value;}
    public void set(String key,String value,int ttl){String endpoint=config.get("REDIS_URL","");if(!endpoint.isEmpty())try{command(endpoint,"SET",key,value,"EX",""+Math.max(1,ttl));memory.remove(key);return;}catch(RuntimeException e){if(config.production())throw new ApiError(503,"Redis is required for WebRTC media sessions");}else if(config.production())throw new ApiError(503,"Redis is required for WebRTC media sessions");if(memory.size()>10000)memory.entrySet().removeIf(e->e.getValue().expires<=System.currentTimeMillis());memory.put(key,new Entry(value,Math.max(1,ttl)));}
    public void delete(String key){String endpoint=config.get("REDIS_URL","");if(!endpoint.isEmpty())try{command(endpoint,"DEL",key);memory.remove(key);return;}catch(RuntimeException e){if(config.production())throw new ApiError(503,"Redis is required for WebRTC media sessions");}memory.remove(key);}
    public Object shared(String... args) {
        String endpoint=config.get("REDIS_URL","");
        if(endpoint.isEmpty())throw new ApiError(503,"shared Redis is not configured");
        return command(endpoint,args);
    }
    private Object command(String endpoint,String... args){
        URI uri=URI.create(endpoint);if(!Values.set("redis","rediss").contains(uri.getScheme())||uri.getHost()==null
            ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort()>65535
            ||!Objects.toString(uri.getPath(),"").matches("(?:/[0-9]{1,9})?"))throw new ApiError(503,"invalid Redis URL");
        if(config.production()&&!"rediss".equals(uri.getScheme()))throw new ApiError(503,"production Redis requires TLS");
        int port=uri.getPort()<0?6379:uri.getPort();Socket socket=null;
        try{socket=new Socket();socket.connect(new InetSocketAddress(uri.getHost(),port),500);socket.setSoTimeout(500);
            if("rediss".equals(uri.getScheme())){SSLSocket tls=(SSLSocket)tlsFactory.createSocket(socket,uri.getHost(),port,true);socket=tls;SSLParameters p=tls.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(p);tls.setSoTimeout(500);tls.startHandshake();}
            InputStream in=new BufferedInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
            if(uri.getRawUserInfo()!=null){String[] credentials=uri.getRawUserInfo().split(":",2);String user=URLDecoder.decode(credentials[0],"UTF-8"),password=URLDecoder.decode(credentials.length==2?credentials[1]:credentials[0],"UTF-8");write(out,user.isEmpty()||credentials.length==1?new String[]{"AUTH",password}:new String[]{"AUTH",user,password});read(in);}
            String db=Objects.toString(uri.getPath(),"").replaceFirst("^/","");if(!db.isEmpty()){if(!db.matches("[0-9]+"))throw new IOException();write(out,new String[]{"SELECT",db});read(in);}
            write(out,args);return read(in);
        }catch(IOException e){throw new ApiError(503,"Redis is unavailable");}finally{if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    }
    private void write(OutputStream out,String[] args)throws IOException{out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));for(String arg:args){byte[] bytes=arg.getBytes(StandardCharsets.UTF_8);out.write(("$"+bytes.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.write(new byte[]{13,10});}out.flush();}
    private String line(InputStream in)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int i=0;i<8192;i++){int c=in.read();if(c<0)throw new EOFException();if(c==13){if(in.read()!=10)throw new IOException();return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}bytes.write(c);}throw new IOException("Redis header too large");}
    private Object read(InputStream in)throws IOException{return read(in,new int[]{1048576},0);}
    private Object read(InputStream in,int[] budget,int depth)throws IOException{
        if(depth>16||budget[0]<=0)throw new IOException("Redis response exceeds limit");
        int type=in.read();String value=line(in);budget[0]-=value.length()+3;if(budget[0]<0)throw new IOException("Redis response exceeds limit");
        if(type=='*'){int count=Integer.parseInt(value);if(count<0||count>4096||count>budget[0]/3)throw new IOException("invalid Redis array");List<Object> items=new ArrayList<>();for(int i=0;i<count;i++)items.add(read(in,budget,depth+1));return items;}
        if(type=='+')return value;if(type==':')return value;if(type=='-')throw new IOException("Redis command rejected");if(type!='$')throw new IOException("invalid Redis reply");
        int size=Integer.parseInt(value);if(size==-1)return null;if(size<0||size>budget[0]-2)throw new IOException("Redis value too large");budget[0]-=size+2;
        byte[] bytes=new byte[size];for(int n=0;n<size;){int read=in.read(bytes,n,size-n);if(read<0)throw new EOFException();n+=read;}if(in.read()!=13||in.read()!=10)throw new IOException();return new String(bytes,StandardCharsets.UTF_8);
    }
}
