package com.duozhi.outbound.auth.dao;

import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AuthIdentityMapper {
    SysUser findByUsernameForLogin(@Param("username")
    String username);

    SysUser findByIdForJwt(@Param("id")
    long id);

    Tenant findTenantById(@Param("id")
    long id);

    Integer lockTenantForUserAdmin(@Param("tenantId")
    int tenantId);

    SysUser findOwnedUser(@Param("id")
    long id, @Param("tenantId")
    int tenantId);

    List<SysUser> listUsers(
            @Param("tenantId")
            int tenantId,
            @Param("keyword")
            String keyword,
            @Param("offset")
            int offset,
            @Param("limit")
            int limit);

    long enabledAdmins(@Param("tenantId")
    int tenantId);

    int updateUser(
            @Param("id")
            long id,
            @Param("tenantId")
            int tenantId,
            @Param("changes")
            Map<String, Object> changes,
            @Param("now")
            LocalDateTime now);

    int revoke(
            @Param("id")
            long id, @Param("tenantId")
            int tenantId, @Param("now")
            LocalDateTime now);
}
