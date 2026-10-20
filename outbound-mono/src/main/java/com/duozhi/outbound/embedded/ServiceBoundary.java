package com.duozhi.outbound.embedded;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
/** Original auxiliary-service header and body contract, scoped to its retained paths. */
@org.springframework.stereotype.Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+9)
public class ServiceBoundary extends org.springframework.web.filter.OncePerRequestFilter {
    private static final Set<String> SINGLE=Set.of("authorization","host","content-length","x-voice-timestamp","x-voice-nonce","x-voice-signature");
    public static boolean applies(HttpServletRequest request){String path=request.getRequestURI();return path.startsWith("/v1/")||"POST".equals(request.getMethod())&&Set.of("/agent/start","/agent/turn").contains(path);}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !applies(request);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","no-store");response.setHeader("X-Frame-Options","DENY");
        for(String name:SINGLE){List<String> values=Collections.list(request.getHeaders(name));if(values.size()>1||values.stream().anyMatch(value->value.length()>4096||!value.chars().allMatch(c->c>=32&&c<=126))){reject(response,400,"invalid request headers");return;}}
        if(request.getContentLengthLong()>1048576){reject(response,413,"request too large");return;}
        if(!Set.of("POST","PUT","PATCH").contains(request.getMethod())){chain.doFilter(request,response);return;}
        byte[] body=request.getInputStream().readNBytes(1048577);if(body.length>1048576){reject(response,413,"request too large");return;}
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){private boolean listening;public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public int read(){return input.read();}public int read(byte[] bytes,int offset,int length){return input.read(bytes,offset,length);}public void setReadListener(ReadListener listener){Objects.requireNonNull(listener);if(listening||!request.isAsyncStarted())throw new IllegalStateException("nonblocking read requires an active async request and one listener");listening=true;request.getAsyncContext().start(()->{try{if(input.available()>0)listener.onDataAvailable();if(input.available()==0)listener.onAllDataRead();}catch(Exception error){listener.onError(error);}});}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },response);
    }
    private void reject(HttpServletResponse response,int status,String detail)throws IOException{response.setStatus(status);response.setContentType("application/json");response.getOutputStream().write(Support.json(Map.of("detail",detail)));}
}
