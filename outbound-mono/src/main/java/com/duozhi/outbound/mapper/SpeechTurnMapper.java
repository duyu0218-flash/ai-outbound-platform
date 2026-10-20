package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.SpeechTurn;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SpeechTurnMapper {
    int insert(@Param("row") SpeechTurn row);
    int insertExplicitId(@Param("row") SpeechTurn row);
    SpeechTurn findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") SpeechTurn row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<SpeechTurn> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.SpeechTurn> expiredTranscripts(java.util.Map<String,Object> parameters);
    long sourceReports183_8(java.util.Map<String,Object> parameters);
    long sourceReports183_9(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SpeechTurn> scopedAiRuntime120(java.util.Map<String,Object> parameters);
    long scopedAiRuntime160(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SpeechTurn> scopedCallAnalysis32(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SpeechTurn> scopedConversationPolicy154(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SpeechTurn> scopedHandoffs76(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SpeechTurn> scopedVoiceCallbacks62(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SpeechTurn> callEvidence(java.util.Map<String,Object> parameters);
}
