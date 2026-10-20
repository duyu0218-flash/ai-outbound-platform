package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.TelephonyLine;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TelephonyLineMapper {
    int insert(@Param("row") TelephonyLine row);
    int insertExplicitId(@Param("row") TelephonyLine row);
    TelephonyLine findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") TelephonyLine row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<TelephonyLine> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    long countRealEnabled(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TelephonyLine> sourceAdminLines15_0(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TelephonyLine> sourceDependencyHealth25_1(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TelephonyLine> sourceDialService94_3(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TelephonyLine> sourceSystemOverview32_0(java.util.Map<String,Object> parameters);
    long sourceSystemOverview53_7(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TelephonyLine> sourceTelephonyClient30_0(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.TelephonyLine> reportLabels(java.util.Map<String,Object> parameters);
}
