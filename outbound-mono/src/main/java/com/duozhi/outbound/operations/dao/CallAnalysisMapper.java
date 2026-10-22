package com.duozhi.outbound.operations.dao;

import com.duozhi.outbound.operations.domain.CallAnalysis;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallAnalysisMapper {
    int insert(@Param("row") CallAnalysis row);

    int insertExplicitId(@Param("row") CallAnalysis row);

    CallAnalysis findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") CallAnalysis row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<CallAnalysis> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    int redactCallEvidence(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.operations.domain.CallAnalysis> sourceHandoffs75_9(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.operations.domain.CallAnalysis> sourceReports184_10(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.operations.domain.CallAnalysis> scopedCallAnalysis17(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.operations.domain.CallAnalysis> reviewQueue(
            java.util.Map<String, Object> parameters);
}
