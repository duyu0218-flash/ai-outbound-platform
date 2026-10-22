package com.duozhi.outbound.agent.dao;

import com.duozhi.outbound.agent.domain.HandoffRequest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HandoffRequestMapper {
    int insert(@Param("row")
    HandoffRequest row);

    int insertExplicitId(@Param("row")
    HandoffRequest row);

    HandoffRequest findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    HandoffRequest row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<HandoffRequest> list(
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

    List<HandoffRequest> sourceAgentMedia40_0(Map<String, Object> parameters);

    List<HandoffRequest> sourceCallLifecycle124_10(Map<String, Object> parameters);

    List<HandoffRequest> sourceHandoffs17_0(Map<String, Object> parameters);

    List<HandoffRequest> sourceHandoffs36_2(Map<String, Object> parameters);

    List<HandoffRequest> sourceHandoffs68_4(Map<String, Object> parameters);

    List<HandoffRequest> scopedCallLifecycle146(Map<String, Object> parameters);

    List<HandoffRequest> scopedConversationPolicy82(Map<String, Object> parameters);

    long scopedConversationPolicy88(Map<String, Object> parameters);

    List<HandoffRequest> handoffQueue(Map<String, Object> parameters);
}
