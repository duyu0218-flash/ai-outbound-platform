package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.CallUsage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallUsageMapper {
    int insert(@Param("row") CallUsage row);
    int insertExplicitId(@Param("row") CallUsage row);
    CallUsage findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") CallUsage row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<CallUsage> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.CallUsage> sourceReports125_3(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.CallUsage> scopedCallLifecycle80(java.util.Map<String,Object> parameters);
    java.util.List<java.util.Map<String,Object>> rawProductOperations61_1(java.util.Map<String,Object> parameters);
}
