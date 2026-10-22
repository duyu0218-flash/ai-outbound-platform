package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.CallEvent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallEventMapper {
    int insert(@Param("row") CallEvent row);

    int insertExplicitId(@Param("row") CallEvent row);

    CallEvent findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") CallEvent row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<CallEvent> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    long dailyAttemptEvents(java.util.Map<String, Object> parameters);

    int redactCallEvidence(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallEvent> archivableEvidence(
            java.util.Map<String, Object> parameters);

    int deleteArchivedEvidence(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.call.domain.CallEvent> scopedCalls71(
            java.util.Map<String, Object> parameters);

    long rawDialService89_1(java.util.Map<String, Object> parameters);
}
