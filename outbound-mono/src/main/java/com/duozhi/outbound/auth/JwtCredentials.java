package com.duozhi.outbound.auth;
import org.apache.shiro.authc.AuthenticationToken;
public final class JwtCredentials implements AuthenticationToken {
    public final String authorization, apiKey, method, path; public Long expiresAt;
    public JwtCredentials(String authorization,String apiKey,String method,String path){this.authorization=authorization;this.apiKey=apiKey;this.method=method;this.path=path;}
    public Object getPrincipal(){return authorization!=null?"jwt":"api-key";}
    public Object getCredentials(){return authorization!=null?authorization:apiKey;}
}
