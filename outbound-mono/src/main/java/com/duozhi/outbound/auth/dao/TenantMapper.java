package com.duozhi.outbound.auth.dao;

import com.duozhi.outbound.auth.domain.Tenant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper {
    int insert(@Param("row") Tenant row);

    int insertExplicitId(@Param("row") Tenant row);

    Tenant findById(@Param("id") Integer id);

    int updateById(@Param("row") Tenant row);

    int deleteById(@Param("id") Integer id);

    List<Tenant> list(@Param("offset") int offset, @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    com.duozhi.outbound.auth.domain.Tenant lockForTransaction(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.auth.domain.Tenant> allForMaintenance(
            java.util.Map<String, Object> parameters);
}
