package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

public final class Identity {
    private final long tenant;
    private final Map<String,Object> user;
    public Identity(long tenant,Map<String,Object> user){this.tenant=tenant;this.user=user;}
    public long tenant(){return tenant;}
    public Map<String,Object> user(){return user;}
    public static Identity from(HttpServletRequest request) {
        if (request.getAttribute("identity") instanceof Identity) return (Identity)request.getAttribute("identity");
        throw new ApiError(401, "authentication required");
    }
    public void admin() { if (user != null && !"admin".equals(user.get("role"))) throw new ApiError(403, "admin permission required"); }
    public Map<String,Object> requireUser() { if (user == null) throw new ApiError(401, "Bearer token required"); return user; }
    public String actor() { return user == null ? "tenant-api" : user.get("username").toString(); }
    public Object userId() { return user == null ? null : user.get("id"); }
}
