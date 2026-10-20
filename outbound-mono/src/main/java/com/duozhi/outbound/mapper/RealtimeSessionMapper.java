package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.RealtimeSession;
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
    List<RealtimeSession> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.RealtimeSession> sourceCallEvidence47_1(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.RealtimeSession> scopedAiRuntime21(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.RealtimeSession> scopedAiRuntime243(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.RealtimeSession> scopedConversationPolicy144(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.RealtimeSession> scopedVoiceCallbacks39(java.util.Map<String,Object> parameters);
}
