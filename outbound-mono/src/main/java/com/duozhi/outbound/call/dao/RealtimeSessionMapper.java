package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.RealtimeSession;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RealtimeSessionMapper {
    int insert(@Param("row")
    RealtimeSession row);

    int insertExplicitId(@Param("row")
    RealtimeSession row);

    RealtimeSession findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    RealtimeSession row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<RealtimeSession> list(
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

    List<RealtimeSession> sourceCallEvidence47_1(Map<String, Object> parameters);

    List<RealtimeSession> scopedAiRuntime21(Map<String, Object> parameters);

    List<RealtimeSession> scopedAiRuntime243(Map<String, Object> parameters);

    List<RealtimeSession> scopedConversationPolicy144(Map<String, Object> parameters);

    List<RealtimeSession> scopedVoiceCallbacks39(Map<String, Object> parameters);
}
