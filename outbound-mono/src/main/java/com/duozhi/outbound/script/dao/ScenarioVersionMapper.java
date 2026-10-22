package com.duozhi.outbound.script.dao;

import com.duozhi.outbound.script.domain.ScenarioVersion;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScenarioVersionMapper {
    int insert(@Param("row")
    ScenarioVersion row);

    int insertExplicitId(@Param("row")
    ScenarioVersion row);

    ScenarioVersion findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    ScenarioVersion row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<ScenarioVersion> list(
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

    List<ScenarioVersion> sourceCallLifecycle68_6(Map<String, Object> parameters);

    List<ScenarioVersion> sourceConversationPolicy23_0(Map<String, Object> parameters);

    List<ScenarioVersion> sourceDialService62_1(Map<String, Object> parameters);

    List<ScenarioVersion> policyHistory(Map<String, Object> parameters);
}
