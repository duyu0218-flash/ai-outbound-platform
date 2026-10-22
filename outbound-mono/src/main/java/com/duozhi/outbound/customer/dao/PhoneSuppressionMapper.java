package com.duozhi.outbound.customer.dao;

import com.duozhi.outbound.customer.domain.PhoneSuppression;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PhoneSuppressionMapper {
    int insert(@Param("row")
    PhoneSuppression row);

    int insertExplicitId(@Param("row")
    PhoneSuppression row);

    PhoneSuppression findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    PhoneSuppression row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<PhoneSuppression> list(
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

    long countPhone(Map<String, Object> parameters);

    long sourceAiRuntime231_8(Map<String, Object> parameters);

    long sourceCallLifecycle65_4(Map<String, Object> parameters);

    long sourceConversationPolicy66_4(Map<String, Object> parameters);
}
