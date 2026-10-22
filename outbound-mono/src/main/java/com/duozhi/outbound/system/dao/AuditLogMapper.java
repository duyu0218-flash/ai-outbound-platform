package com.duozhi.outbound.system.dao;

import com.duozhi.outbound.system.domain.AuditLog;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditLogMapper {
    int insert(@Param("row") AuditLog row);

    int insertExplicitId(@Param("row") AuditLog row);

    AuditLog findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") AuditLog row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<AuditLog> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.system.domain.AuditLog> sourceAdminOperations19_3(
            java.util.Map<String, Object> parameters);
}
