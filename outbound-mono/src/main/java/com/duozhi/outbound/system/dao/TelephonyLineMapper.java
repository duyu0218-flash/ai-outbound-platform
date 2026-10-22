package com.duozhi.outbound.system.dao;

import com.duozhi.outbound.system.domain.TelephonyLine;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TelephonyLineMapper {
    int insert(@Param("row")
    TelephonyLine row);

    int insertExplicitId(@Param("row")
    TelephonyLine row);

    TelephonyLine findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    TelephonyLine row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<TelephonyLine> list(
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

    long countRealEnabled(Map<String, Object> parameters);

    List<TelephonyLine> sourceAdminLines15_0(Map<String, Object> parameters);

    List<TelephonyLine> sourceDependencyHealth25_1(Map<String, Object> parameters);

    List<TelephonyLine> sourceDialService94_3(Map<String, Object> parameters);

    List<TelephonyLine> sourceSystemOverview32_0(Map<String, Object> parameters);

    long sourceSystemOverview53_7(Map<String, Object> parameters);

    List<TelephonyLine> sourceTelephonyClient30_0(Map<String, Object> parameters);

    List<TelephonyLine> reportLabels(Map<String, Object> parameters);
}
