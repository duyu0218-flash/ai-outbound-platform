package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.CallEvent;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallEventMapper {
    int insert(@Param("row")
    CallEvent row);

    int insertExplicitId(@Param("row")
    CallEvent row);

    CallEvent findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    CallEvent row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<CallEvent> list(
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

    long dailyAttemptEvents(Map<String, Object> parameters);

    int redactCallEvidence(Map<String, Object> parameters);

    List<CallEvent> archivableEvidence(Map<String, Object> parameters);

    int deleteArchivedEvidence(Map<String, Object> parameters);

    List<CallEvent> scopedCalls71(Map<String, Object> parameters);

    long rawDialService89_1(Map<String, Object> parameters);
}
