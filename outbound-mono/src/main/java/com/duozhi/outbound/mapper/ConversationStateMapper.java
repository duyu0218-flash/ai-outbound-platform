package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.ConversationState;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationStateMapper {
    int insert(@Param("row") ConversationState row);
    int insertExplicitId(@Param("row") ConversationState row);
    ConversationState findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") ConversationState row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<ConversationState> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    int redactCallEvidence(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ConversationState> sourceProductOperations58_7(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ConversationState> scopedAiRuntime22(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ConversationState> scopedCallAnalysis33(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ConversationState> scopedConversationPolicy30(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ConversationState> scopedHandoffs78(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ConversationState> scopedVoiceCallbacks72(java.util.Map<String,Object> parameters);
    java.util.List<java.util.Map<String,Object>> deliveryFunnel(java.util.Map<String,Object> parameters);
}
