package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.ScenarioVersion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScenarioVersionMapper {
    int insert(@Param("row") ScenarioVersion row);
    int insertExplicitId(@Param("row") ScenarioVersion row);
    ScenarioVersion findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") ScenarioVersion row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<ScenarioVersion> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.ScenarioVersion> sourceCallLifecycle68_6(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ScenarioVersion> sourceConversationPolicy23_0(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ScenarioVersion> sourceDialService62_1(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ScenarioVersion> policyHistory(java.util.Map<String,Object> parameters);
}
