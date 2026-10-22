package com.duozhi.outbound.script.dao;

import com.duozhi.outbound.script.domain.ScriptFlowVersion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScriptFlowVersionMapper {
    int insert(@Param("row") ScriptFlowVersion row);

    int insertExplicitId(@Param("row") ScriptFlowVersion row);

    ScriptFlowVersion findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") ScriptFlowVersion row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<ScriptFlowVersion> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.script.domain.ScriptFlowVersion> templateVersions(
            java.util.Map<String, Object> parameters);

    long maxTemplateVersion(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.script.domain.ScriptFlowVersion> sourceCampaigns64_4(
            java.util.Map<String, Object> parameters);
}
