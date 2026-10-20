package com.duozhi.outbound.auth;
import com.duozhi.outbound.*;
import com.duozhi.outbound.mapper.AuthIdentityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.Map;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.web.filter.AccessControlFilter;
public final class JwtFilter extends AccessControlFilter {
    private final AuthIdentityMapper identities;private final ObjectMapper json;
    public JwtFilter(AuthIdentityMapper identities,ObjectMapper json){this.identities=identities;this.json=json;}
    @Override protected boolean isAccessAllowed(ServletRequest request,ServletResponse response,Object mapped){return false;}
    @Override protected boolean onAccessDenied(ServletRequest rawRequest,ServletResponse rawResponse)throws Exception{
        var request=(HttpServletRequest)rawRequest;var response=(HttpServletResponse)rawResponse;
        response.setHeader("Cache-Control","no-store");
        try{
            var token=new JwtCredentials(request.getHeader("Authorization"),request.getHeader("x-api-key"),request.getMethod(),request.getRequestURI());
            getSubject(request,response).login(token);
            Identity identity=(Identity)getSubject(request,response).getPrincipal();String requested=request.getHeader("x-tenant-id");
            if(requested!=null&&!requested.equals(Long.toString(identity.tenant())))throw new ApiError(403,"tenant scope mismatch");
            var tenant=identities.findTenantById(identity.tenant());if(tenant==null||!Boolean.TRUE.equals(tenant.getEnabled()))throw new ApiError(403,"tenant disabled");
            if(request.getRequestURI().startsWith("/api/v1/admin/")&&identity.user()!=null)identity.admin("admin:users");
            request.setAttribute("identity",identity);if(token.expiresAt!=null)request.setAttribute("token_expires_at",token.expiresAt);return true;
        }catch(ApiError error){reject(response,error);return false;}
        catch(AuthenticationException error){Throwable cause=error;while(cause.getCause()!=null&&!(cause instanceof ApiError))cause=cause.getCause();reject(response,cause instanceof ApiError api?api:new ApiError(401,"Invalid token"));return false;}
    }
    private void reject(HttpServletResponse response,ApiError error)throws Exception{response.setStatus(error.status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),Map.of("detail",error.getMessage()));}
}
