package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.CallSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallSessionMapper {
    int insert(@Param("row")
    CallSession row);

    CallSession findById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    CallSession row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    List<CallSession> list(
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

    long countContactReferences(Map<String, Object> parameters);

    long dailyAttempts(Map<String, Object> parameters);

    CallSession latestAttempt(Map<String, Object> parameters);

    List<CallSession> expiredSensitiveCalls(Map<String, Object> parameters);

    List<CallSession> sourceAgentMedia40_1(Map<String, Object> parameters);

    List<CallSession> sourceAiRuntime230_7(Map<String, Object> parameters);

    long sourceCallLifecycle59_3(Map<String, Object> parameters);

    List<CallSession> sourceCampaigns141_7(Map<String, Object> parameters);

    long sourceCampaigns192_8(Map<String, Object> parameters);

    List<CallSession> sourceCampaigns208_9(Map<String, Object> parameters);

    List<CallSession> sourceCampaigns281_11(Map<String, Object> parameters);

    List<CallSession> sourceCampaigns284_12(Map<String, Object> parameters);

    List<CallSession> sourceCampaigns295_13(Map<String, Object> parameters);

    List<CallSession> sourceConversationPolicy68_6(Map<String, Object> parameters);

    long sourceDashboard29_4(Map<String, Object> parameters);

    List<CallSession> sourceHandoffs73_6(Map<String, Object> parameters);

    List<CallSession> sourceIntegrationTasks23_0(Map<String, Object> parameters);

    List<CallSession> sourceProductOperations55_6(Map<String, Object> parameters);

    List<CallSession> sourceReports31_0(Map<String, Object> parameters);

    long sourceSystemOverview35_1(Map<String, Object> parameters);

    CallSession lockById(Map<String, Object> parameters);

    long scopedCallLifecycle57(Map<String, Object> parameters);

    long scopedCallLifecycle110(Map<String, Object> parameters);

    List<CallSession> scopedCallReconciliation30(Map<String, Object> parameters);

    long scopedConversationPolicy87(Map<String, Object> parameters);

    long scopedGatewayCluster54(Map<String, Object> parameters);

    List<CallSession> scopedTaskWorker70(Map<String, Object> parameters);

    long rawDialService88_0(Map<String, Object> parameters);

    List<CallSession> searchCalls(Map<String, Object> parameters);

    List<CallSession> exportCalls(Map<String, Object> parameters);

    long activeDialCount(Map<String, Object> parameters);

    Map<String, Object> dashboardSummary(Map<String, Object> parameters);

    List<Map<String, Object>> dashboardPerformance(Map<String, Object> parameters);

    List<Map<String, Object>> systemCounts(Map<String, Object> parameters);

    List<Map<String, Object>> metricsGroupstatus(Map<String, Object> parameters);

    List<Map<String, Object>> metricsGroupvoice_ai_pipeline(Map<String, Object> parameters);

    CallSession currentAiRead(Map<String, Object> parameters);

    CallSession currentAiLock(Map<String, Object> parameters);

    List<CallSession> reportPage(Map<String, Object> parameters);

    List<Map<String, Object>> reportAggregate(Map<String, Object> parameters);
}
