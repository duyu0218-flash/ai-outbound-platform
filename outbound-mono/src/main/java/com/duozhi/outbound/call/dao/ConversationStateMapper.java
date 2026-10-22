package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.ConversationState;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationStateMapper {
    int insert(@Param("row")
    ConversationState row);

    int insertExplicitId(@Param("row")
    ConversationState row);

    ConversationState findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    ConversationState row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<ConversationState> list(
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

    List<ConversationState> sourceProductOperations58_7(Map<String, Object> parameters);

    List<ConversationState> scopedAiRuntime22(Map<String, Object> parameters);

    List<ConversationState> scopedCallAnalysis33(Map<String, Object> parameters);

    List<ConversationState> scopedConversationPolicy30(Map<String, Object> parameters);

    List<ConversationState> scopedHandoffs78(Map<String, Object> parameters);

    List<ConversationState> scopedVoiceCallbacks72(Map<String, Object> parameters);

    List<Map<String, Object>> deliveryFunnel(Map<String, Object> parameters);
}
