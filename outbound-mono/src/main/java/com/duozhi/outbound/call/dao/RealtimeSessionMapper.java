package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.RealtimeSession;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RealtimeSessionMapper {
    int insert(@Param("row") RealtimeSession row);

    int insertExplicitId(@Param("row") RealtimeSession row);

    RealtimeSession findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") RealtimeSession row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<RealtimeSession> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.call.domain.RealtimeSession> sourceCallEvidence47_1(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.RealtimeSession> scopedAiRuntime21(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.RealtimeSession> scopedAiRuntime243(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.RealtimeSession> scopedConversationPolicy144(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.RealtimeSession> scopedVoiceCallbacks39(
            java.util.Map<String, Object> parameters);
}
