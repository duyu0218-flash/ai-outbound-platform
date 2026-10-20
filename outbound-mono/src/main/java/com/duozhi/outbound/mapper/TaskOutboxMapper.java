package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.TaskOutbox;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskOutboxMapper {
    int insert(@Param("row") TaskOutbox row);
    TaskOutbox findById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") TaskOutbox row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    List<TaskOutbox> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    com.duozhi.outbound.entity.TaskOutbox findByIdempotencyKey(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> pendingAiForAggregate(java.util.Map<String,Object> parameters);
    com.duozhi.outbound.entity.TaskOutbox lockById(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> expiredPayloads(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> archivableTasks(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> sourceAdminOperations13_0(java.util.Map<String,Object> parameters);
    long sourceSystemOverview46_2(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> sourceSystemOverview47_3(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> sourceVoiceCallbacks114_3(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> scopedOperationalMetrics25(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> scopedTaskWorker60(java.util.Map<String,Object> parameters);
    long scopedTaskWorker107(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> scopedTaskWorker151(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> abandonedLeases(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TaskOutbox> claimCandidate(java.util.Map<String,Object> parameters);
    int renewLease(java.util.Map<String,Object> parameters);
    java.util.List<java.util.Map<String,Object>> systemCounts(java.util.Map<String,Object> parameters);
    java.util.List<java.util.Map<String,Object>> metricsGroupstate(java.util.Map<String,Object> parameters);
}
