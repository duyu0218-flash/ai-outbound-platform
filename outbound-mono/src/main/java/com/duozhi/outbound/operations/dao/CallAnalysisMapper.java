package com.duozhi.outbound.operations.dao;

import com.duozhi.outbound.operations.domain.CallAnalysis;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallAnalysisMapper {
    int insert(@Param("row")
    CallAnalysis row);

    int insertExplicitId(@Param("row")
    CallAnalysis row);

    CallAnalysis findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    CallAnalysis row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<CallAnalysis> list(
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

    List<CallAnalysis> sourceHandoffs75_9(Map<String, Object> parameters);

    List<CallAnalysis> sourceReports184_10(Map<String, Object> parameters);

    List<CallAnalysis> scopedCallAnalysis17(Map<String, Object> parameters);

    List<CallAnalysis> reviewQueue(Map<String, Object> parameters);
}
