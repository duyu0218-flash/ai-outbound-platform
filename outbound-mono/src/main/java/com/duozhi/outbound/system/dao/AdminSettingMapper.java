package com.duozhi.outbound.system.dao;

import com.duozhi.outbound.system.domain.AdminSetting;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminSettingMapper {
    int insert(@Param("row")
    AdminSetting row);

    int insertExplicitId(@Param("row")
    AdminSetting row);

    AdminSetting findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    AdminSetting row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<AdminSetting> list(
            @Param("tenantId")
            Integer tenantId,
            @Param("offset")
            int offset,
            @Param("limit")
            int limit);

    int patchFields(
            @Param("id")
            Object id,
            @Param("tenantId")
            long tenantId,
            @Param("changes")
            Map<String, Object> changes);

    AdminSetting findBySection(Map<String, Object> parameters);
}
