package com.duozhi.outbound.call.dao;

import com.duozhi.outbound.call.domain.CallUsage;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallUsageMapper {
    int insert(@Param("row")
    CallUsage row);

    int insertExplicitId(@Param("row")
    CallUsage row);

    CallUsage findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    CallUsage row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<CallUsage> list(
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

    List<CallUsage> sourceReports125_3(Map<String, Object> parameters);

    List<CallUsage> scopedCallLifecycle80(Map<String, Object> parameters);

    List<Map<String, Object>> rawProductOperations61_1(Map<String, Object> parameters);

    List<CallUsage> reportPageUsage(Map<String, Object> parameters);
}
