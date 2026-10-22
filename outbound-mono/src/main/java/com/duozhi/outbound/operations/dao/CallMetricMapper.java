package com.duozhi.outbound.operations.dao;

import com.duozhi.outbound.operations.domain.CallMetric;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallMetricMapper {
    int insert(@Param("row")
    CallMetric row);

    int insertExplicitId(@Param("row")
    CallMetric row);

    CallMetric findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    CallMetric row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<CallMetric> list(
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

    List<CallMetric> archivableEvidence(Map<String, Object> parameters);

    int deleteArchivedEvidence(Map<String, Object> parameters);

    List<CallMetric> scopedOperationalMetrics29(Map<String, Object> parameters);

    Double rawSystemOverview45_1(Map<String, Object> parameters);

    Map<String, Object> rawReports182_1(Map<String, Object> parameters);

    List<CallMetric> callEvidence(Map<String, Object> parameters);
}
