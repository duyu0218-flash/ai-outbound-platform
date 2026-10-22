package com.duozhi.outbound.script.dao;

import com.duozhi.outbound.script.domain.ScriptTemplate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScriptTemplateMapper {
    int insert(@Param("row")
    ScriptTemplate row);

    int insertExplicitId(@Param("row")
    ScriptTemplate row);

    ScriptTemplate findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    ScriptTemplate row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<ScriptTemplate> list(
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

    List<ScriptTemplate> searchTemplates(Map<String, Object> parameters);

    ScriptTemplate lockById(Map<String, Object> parameters);

    List<ScriptTemplate> sourceCampaigns59_3(Map<String, Object> parameters);

    List<ScriptTemplate> sourceCampaigns80_5(Map<String, Object> parameters);

    List<ScriptTemplate> sourceConversationPolicy38_2(Map<String, Object> parameters);

    long sourceDashboard29_2(Map<String, Object> parameters);
}
