package com.duozhi.outbound.callback.dao;

import com.duozhi.outbound.callback.domain.WebhookEventIngest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WebhookEventIngestMapper {
    int insert(@Param("row")
    WebhookEventIngest row);

    int insertExplicitId(@Param("row")
    WebhookEventIngest row);

    WebhookEventIngest findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    WebhookEventIngest row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<WebhookEventIngest> list(
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

    List<WebhookEventIngest> scopedCallLifecycle43(Map<String, Object> parameters);

    List<WebhookEventIngest> scopedCalls79(Map<String, Object> parameters);

    long scopedCalls89(Map<String, Object> parameters);

    List<Map<String, Object>> eventBuckets(Map<String, Object> parameters);

    long duplicateCount(Map<String, Object> parameters);
}
