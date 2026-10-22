package com.duozhi.outbound.agent.dao;

import com.duozhi.outbound.agent.domain.HandoffRequest;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HandoffRequestMapper {
    int insert(@Param("row") HandoffRequest row);

    int insertExplicitId(@Param("row") HandoffRequest row);

    HandoffRequest findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") HandoffRequest row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<HandoffRequest> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> sourceAgentMedia40_0(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> sourceCallLifecycle124_10(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> sourceHandoffs17_0(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> sourceHandoffs36_2(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> sourceHandoffs68_4(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> scopedCallLifecycle146(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> scopedConversationPolicy82(
            java.util.Map<String, Object> parameters);

    long scopedConversationPolicy88(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.agent.domain.HandoffRequest> handoffQueue(
            java.util.Map<String, Object> parameters);
}
