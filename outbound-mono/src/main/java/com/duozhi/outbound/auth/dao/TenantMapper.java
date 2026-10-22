package com.duozhi.outbound.auth.dao;

import com.duozhi.outbound.auth.domain.Tenant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper {
    int insert(@Param("row")
    Tenant row);

    int insertExplicitId(@Param("row")
    Tenant row);

    Tenant findById(@Param("id")
    Integer id);

    int updateById(@Param("row")
    Tenant row);

    int deleteById(@Param("id")
    Integer id);

    List<Tenant> list(@Param("offset")
    int offset, @Param("limit")
    int limit);

    int patchFields(
            @Param("id")
            Object id,
            @Param("tenantId")
            long tenantId,
            @Param("changes")
            Map<String, Object> changes);

    Tenant lockForTransaction(Map<String, Object> parameters);

    List<Tenant> allForMaintenance(Map<String, Object> parameters);
}
