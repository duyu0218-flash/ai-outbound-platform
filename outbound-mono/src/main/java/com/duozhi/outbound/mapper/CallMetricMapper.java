package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.CallMetric;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallMetricMapper {
    int insert(@Param("row") CallMetric row);
    int insertExplicitId(@Param("row") CallMetric row);
    CallMetric findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") CallMetric row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<CallMetric> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    int redactCallEvidence(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.CallMetric> archivableEvidence(java.util.Map<String,Object> parameters);
    int deleteArchivedEvidence(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.CallMetric> scopedOperationalMetrics29(java.util.Map<String,Object> parameters);
    Double rawSystemOverview45_1(java.util.Map<String,Object> parameters);
    java.util.Map<String,Object> rawReports182_1(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.CallMetric> callEvidence(java.util.Map<String,Object> parameters);
}
