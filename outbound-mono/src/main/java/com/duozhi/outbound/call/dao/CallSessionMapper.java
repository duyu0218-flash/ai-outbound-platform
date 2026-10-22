package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.CallSession;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallSessionMapper {
    int insert(@Param("row") CallSession row);

    CallSession findById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") CallSession row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);

    List<CallSession> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    long countContactReferences(java.util.Map<String, Object> parameters);

    long dailyAttempts(java.util.Map<String, Object> parameters);

    com.duozhi.outbound.call.domain.CallSession latestAttempt(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> expiredSensitiveCalls(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceAgentMedia40_1(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceAiRuntime230_7(
            java.util.Map<String, Object> parameters);

    long sourceCallLifecycle59_3(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceCampaigns141_7(
            java.util.Map<String, Object> parameters);

    long sourceCampaigns192_8(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceCampaigns208_9(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceCampaigns281_11(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceCampaigns284_12(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceCampaigns295_13(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceConversationPolicy68_6(
            java.util.Map<String, Object> parameters);

    long sourceDashboard29_4(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceHandoffs73_6(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceIntegrationTasks23_0(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceProductOperations55_6(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> sourceReports31_0(
            java.util.Map<String, Object> parameters);

    long sourceSystemOverview35_1(java.util.Map<String, Object> parameters);

    com.duozhi.outbound.call.domain.CallSession lockById(java.util.Map<String, Object> parameters);

    long scopedCallLifecycle57(java.util.Map<String, Object> parameters);

    long scopedCallLifecycle110(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> scopedCallReconciliation30(
            java.util.Map<String, Object> parameters);

    long scopedConversationPolicy87(java.util.Map<String, Object> parameters);

    long scopedGatewayCluster54(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> scopedTaskWorker70(
            java.util.Map<String, Object> parameters);

    long rawDialService88_0(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> searchCalls(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallSession> exportCalls(
            java.util.Map<String, Object> parameters);

    long activeDialCount(java.util.Map<String, Object> parameters);

    java.util.Map<String, Object> dashboardSummary(java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> dashboardPerformance(
            java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> systemCounts(
            java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> metricsGroupstatus(
            java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> metricsGroupvoice_ai_pipeline(
            java.util.Map<String, Object> parameters);

    CallSession currentAiRead(java.util.Map<String, Object> parameters);

    CallSession currentAiLock(java.util.Map<String, Object> parameters);
}
