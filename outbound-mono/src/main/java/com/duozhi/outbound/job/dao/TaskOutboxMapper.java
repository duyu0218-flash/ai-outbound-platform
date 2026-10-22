package com.duozhi.outbound.job.dao;

import com.duozhi.outbound.job.domain.TaskOutbox;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskOutboxMapper {
    int insert(@Param("row") TaskOutbox row);

    TaskOutbox findById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") TaskOutbox row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);

    List<TaskOutbox> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    com.duozhi.outbound.job.domain.TaskOutbox findByIdempotencyKey(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> pendingAiForAggregate(
            java.util.Map<String, Object> parameters);

    com.duozhi.outbound.job.domain.TaskOutbox lockById(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> expiredPayloads(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> archivableTasks(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> sourceAdminOperations13_0(
            java.util.Map<String, Object> parameters);

    long sourceSystemOverview46_2(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> sourceSystemOverview47_3(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> sourceVoiceCallbacks114_3(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> scopedOperationalMetrics25(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> scopedTaskWorker60(
            java.util.Map<String, Object> parameters);

    long scopedTaskWorker107(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> scopedTaskWorker151(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> abandonedLeases(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.job.domain.TaskOutbox> claimCandidate(
            java.util.Map<String, Object> parameters);

    int renewLease(java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> systemCounts(
            java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> metricsGroupstate(
            java.util.Map<String, Object> parameters);
}
