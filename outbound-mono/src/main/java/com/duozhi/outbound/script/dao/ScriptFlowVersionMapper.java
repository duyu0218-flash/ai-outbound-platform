package com.duozhi.outbound.script.dao;

import com.duozhi.outbound.script.domain.ScriptFlowVersion;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScriptFlowVersionMapper {
    int insert(@Param("row")
    ScriptFlowVersion row);

    int insertExplicitId(@Param("row")
    ScriptFlowVersion row);

    ScriptFlowVersion findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    ScriptFlowVersion row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<ScriptFlowVersion> list(
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

    List<ScriptFlowVersion> templateVersions(Map<String, Object> parameters);

    long maxTemplateVersion(Map<String, Object> parameters);

    List<ScriptFlowVersion> sourceCampaigns64_4(Map<String, Object> parameters);
}
