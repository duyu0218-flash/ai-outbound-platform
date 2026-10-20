package com.duozhi.outbound;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
/** JWT user or tenant API key. Permission codes are exact matches, without wildcards. */
public final class Identity {
    private final long tenant; private final Map<String,Object> user; private final Supplier<Set<String>> permissions;
    public Identity(long tenant, Map<String,Object> user, Supplier<Set<String>> permissions) { this.tenant=tenant;this.user=user;this.permissions=permissions; }
    public long tenant(){return tenant;} public Map<String,Object> user(){return user;}
    public Set<String> permissions(){return permissions.get();}
    public static Identity from(HttpServletRequest request){if(request.getAttribute("identity") instanceof Identity i)return i;throw new ApiError(401,"authentication required");}
    public void admin(String permission){
        if(user!=null){if(!"admin".equals(user.get("role")))throw new ApiError(403,"admin permission required");}
        else if(permission==null || permission.isBlank() || !permissions().contains(permission))throw new ApiError(403,"admin permission required");
    }
    public Map<String,Object> requireUser(){if(user==null)throw new ApiError(401,"Bearer token required");return user;}
    public String actor(){return user==null?"tenant-api":user.get("username").toString();}
    public Object userId(){return user==null?null:user.get("id");}
}
