package com.duozhi.outbound.recording.dao;

import com.duozhi.outbound.recording.domain.RecordingAsset;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RecordingAssetMapper {
    int insert(@Param("row")
    RecordingAsset row);

    int insertExplicitId(@Param("row")
    RecordingAsset row);

    RecordingAsset findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    RecordingAsset row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<RecordingAsset> list(
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

    int redactCallEvidence(Map<String, Object> parameters);

    List<RecordingAsset> expiredRecordings(Map<String, Object> parameters);

    List<RecordingAsset> sourceAdminOperations13_1(Map<String, Object> parameters);

    List<RecordingAsset> sourceIntegrationTasks77_1(Map<String, Object> parameters);

    List<RecordingAsset> sourceReports186_11(Map<String, Object> parameters);

    long sourceSystemOverview49_4(Map<String, Object> parameters);

    List<RecordingAsset> sourceTaskWorker159_7(Map<String, Object> parameters);

    long countDeletionFailures(Map<String, Object> parameters);

    long countIngestFailures(Map<String, Object> parameters);

    List<RecordingAsset> scopedVoiceCallbacks135(Map<String, Object> parameters);

    List<RecordingAsset> callEvidence(Map<String, Object> parameters);
}
