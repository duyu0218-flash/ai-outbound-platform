package com.duozhi.outbound.recording.dao;

import com.duozhi.outbound.recording.domain.RecordingAsset;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecordingAssetMapper {
    int insert(@Param("row") RecordingAsset row);

    int insertExplicitId(@Param("row") RecordingAsset row);

    RecordingAsset findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") RecordingAsset row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<RecordingAsset> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    int redactCallEvidence(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.recording.domain.RecordingAsset> expiredRecordings(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.recording.domain.RecordingAsset> sourceAdminOperations13_1(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.recording.domain.RecordingAsset> sourceIntegrationTasks77_1(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.recording.domain.RecordingAsset> sourceReports186_11(
            java.util.Map<String, Object> parameters);

    long sourceSystemOverview49_4(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.recording.domain.RecordingAsset> sourceTaskWorker159_7(
            java.util.Map<String, Object> parameters);

    long countDeletionFailures(java.util.Map<String, Object> parameters);

    long countIngestFailures(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.recording.domain.RecordingAsset> scopedVoiceCallbacks135(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.recording.domain.RecordingAsset> callEvidence(
            java.util.Map<String, Object> parameters);
}
