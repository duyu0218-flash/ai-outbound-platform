package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.Semaphore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded process-local admission. Shared dial admission is separate. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
public class HttpBoundary extends OncePerRequestFilter {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private final Config config;private final Semaphore requests;private final Map<String,String> origins=new LinkedHashMap<>();
    private final Map<String,double[]> buckets=new LinkedHashMap<>();
    public HttpBoundary(Config config){this.config=config;requests=new Semaphore(Math.max(1,config.number("API_MAX_CONCURRENT_REQUESTS",100)));
        for(String item:config.get("CORS_ALLOW_ORIGINS","").split(",")){String origin=item.trim();if(origin.isEmpty())continue;if(!validOrigin(origin))throw new IllegalArgumentException("CORS requires explicit HTTP(S) origins without wildcards or paths");origins.put(origin,origin);}}
    private boolean hostAllowed(String host){String allowed=config.get("TRUSTED_HOSTS","");if(allowed.trim().isEmpty())return !config.production();for(String item:allowed.split(",")){item=item.trim().transform(Ascii::lower);if(item.equals("*")||item.equals(host)||item.startsWith("*.")&&host.endsWith(item.substring(1)))return true;}return false;}
    private synchronized boolean rate(String key){double now=System.nanoTime()/1e9;int burst=Math.max(1,config.number("RATE_LIMIT_BURST",200)),rps=Math.max(1,config.number("RATE_LIMIT_RPS",100));
        if(buckets.size()>10000)buckets.entrySet().removeIf(e->now-e.getValue()[1]>60);
        if(!buckets.containsKey(key)&&buckets.size()>10000)return false;
        double[] state=buckets.computeIfAbsent(key,k->new double[]{burst,now});state[0]=Math.min(burst,state[0]+Math.max(0,now-state[1])*rps);state[1]=now;if(state[0]<1)return false;state[0]--;return true;
    }
    private void reject(HttpServletResponse response,int status,String message) throws IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");JSON.writeValue(response.getOutputStream(),Map.of("detail",message));}
    private boolean validOrigin(String origin){
        try{
            java.net.URI uri=new java.net.URI(origin);
            return Values.set("http","https").contains(uri.getScheme()) && uri.getHost()!=null
                && uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null
                && (uri.getRawPath()==null || uri.getRawPath().isEmpty()) && (uri.getPort()==-1||uri.getPort()>=1&&uri.getPort()<=65535);
        }catch(java.net.URISyntaxException e){return false;}
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return com.duozhi.outbound.embedded.ServiceBoundary.applies(request);}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");response.setHeader("Referrer-Policy","same-origin");
        if(request.getRequestURI().startsWith("/api/"))response.setHeader("Cache-Control","no-store");
        try{SecurityHeaders.validate(request);}catch(ApiError error){reject(response,error.status,error.getMessage());return;}
        String id=request.getHeader("X-Request-ID");response.setHeader("X-Request-ID",id!=null&&id.matches("[A-Za-z0-9_.:-]{1,128}")?id:UUID.randomUUID().toString());
        String host=request.getServerName();if(host==null||!host.matches("[A-Za-z0-9.\\-:\\[\\]]{1,253}")||!hostAllowed(Ascii.lower(host))){reject(response,400,"Invalid host header");return;}
        String origin=request.getHeader("Origin"),allowedOrigin=origin==null?null:origins.get(origin);if(allowedOrigin!=null){
            response.setHeader("Access-Control-Allow-Origin",allowedOrigin);response.setHeader("Vary","Origin");
            if("OPTIONS".equals(request.getMethod())){response.setHeader("Access-Control-Allow-Methods","GET,POST,PUT,PATCH,DELETE,OPTIONS");response.setHeader("Access-Control-Allow-Headers","Authorization,Content-Type,X-API-Key,X-Tenant-ID,Idempotency-Key,X-Request-ID");response.setStatus(200);return;}
        }
        boolean api=request.getRequestURI().startsWith("/api/");
        if(api&&!rate(request.getRemoteAddr())){response.setHeader("Retry-After","1");reject(response,429,"rate limit exceeded");return;}
        if(api&&!requests.tryAcquire()){response.setHeader("Retry-After","1");reject(response,503,"request capacity reached");return;}
        try{chain.doFilter(request,response);}finally{if(api)requests.release();}
    }
}
