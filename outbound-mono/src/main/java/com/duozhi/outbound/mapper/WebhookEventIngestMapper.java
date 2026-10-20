package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.WebhookEventIngest;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WebhookEventIngestMapper {
    int insert(@Param("row") WebhookEventIngest row);
    int insertExplicitId(@Param("row") WebhookEventIngest row);
    WebhookEventIngest findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") WebhookEventIngest row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<WebhookEventIngest> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.WebhookEventIngest> scopedCallLifecycle43(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.WebhookEventIngest> scopedCalls79(java.util.Map<String,Object> parameters);
    long scopedCalls89(java.util.Map<String,Object> parameters);
    java.util.List<java.util.Map<String,Object>> eventBuckets(java.util.Map<String,Object> parameters);
    long duplicateCount(java.util.Map<String,Object> parameters);
}
