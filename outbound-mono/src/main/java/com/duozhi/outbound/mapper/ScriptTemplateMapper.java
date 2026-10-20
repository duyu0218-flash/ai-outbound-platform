package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.ScriptTemplate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScriptTemplateMapper {
    int insert(@Param("row") ScriptTemplate row);
    int insertExplicitId(@Param("row") ScriptTemplate row);
    ScriptTemplate findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") ScriptTemplate row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<ScriptTemplate> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.ScriptTemplate> searchTemplates(java.util.Map<String,Object> parameters);
    com.duozhi.outbound.entity.ScriptTemplate lockById(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ScriptTemplate> sourceCampaigns59_3(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ScriptTemplate> sourceCampaigns80_5(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ScriptTemplate> sourceConversationPolicy38_2(java.util.Map<String,Object> parameters);
    long sourceDashboard29_2(java.util.Map<String,Object> parameters);
}
