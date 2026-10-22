package com.duozhi.outbound.job.dao;

import com.duozhi.outbound.job.domain.TaskOutbox;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskOutboxMapper {
    int insert(@Param("row")
    TaskOutbox row);

    TaskOutbox findById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    TaskOutbox row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    List<TaskOutbox> list(
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

    TaskOutbox findByIdempotencyKey(Map<String, Object> parameters);

    List<TaskOutbox> pendingAiForAggregate(Map<String, Object> parameters);

    TaskOutbox lockById(Map<String, Object> parameters);

    List<TaskOutbox> expiredPayloads(Map<String, Object> parameters);

    List<TaskOutbox> archivableTasks(Map<String, Object> parameters);

    List<TaskOutbox> sourceAdminOperations13_0(Map<String, Object> parameters);

    long sourceSystemOverview46_2(Map<String, Object> parameters);

    List<TaskOutbox> sourceSystemOverview47_3(Map<String, Object> parameters);

    List<TaskOutbox> sourceVoiceCallbacks114_3(Map<String, Object> parameters);

    List<TaskOutbox> scopedOperationalMetrics25(Map<String, Object> parameters);

    List<TaskOutbox> scopedTaskWorker60(Map<String, Object> parameters);

    long scopedTaskWorker107(Map<String, Object> parameters);

    List<TaskOutbox> scopedTaskWorker151(Map<String, Object> parameters);

    List<TaskOutbox> abandonedLeases(Map<String, Object> parameters);

    List<TaskOutbox> claimCandidate(Map<String, Object> parameters);

    int renewLease(Map<String, Object> parameters);

    List<Map<String, Object>> systemCounts(Map<String, Object> parameters);

    List<Map<String, Object>> metricsGroupstate(Map<String, Object> parameters);
}
