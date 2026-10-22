package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.SpeechTurn;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SpeechTurnMapper {
    int insert(@Param("row")
    SpeechTurn row);

    int insertExplicitId(@Param("row")
    SpeechTurn row);

    SpeechTurn findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    SpeechTurn row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<SpeechTurn> list(
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

    List<SpeechTurn> expiredTranscripts(Map<String, Object> parameters);

    long sourceReports183_8(Map<String, Object> parameters);

    long sourceReports183_9(Map<String, Object> parameters);

    List<SpeechTurn> scopedAiRuntime120(Map<String, Object> parameters);

    long scopedAiRuntime160(Map<String, Object> parameters);

    List<SpeechTurn> scopedCallAnalysis32(Map<String, Object> parameters);

    List<SpeechTurn> scopedConversationPolicy154(Map<String, Object> parameters);

    List<SpeechTurn> scopedHandoffs76(Map<String, Object> parameters);

    List<SpeechTurn> scopedVoiceCallbacks62(Map<String, Object> parameters);

    List<SpeechTurn> callEvidence(Map<String, Object> parameters);
}
