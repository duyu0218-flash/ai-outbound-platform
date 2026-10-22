package com.duozhi.outbound.customer.dao;

import com.duozhi.outbound.customer.domain.PhoneSuppression;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PhoneSuppressionMapper {
    int insert(@Param("row") PhoneSuppression row);

    int insertExplicitId(@Param("row") PhoneSuppression row);

    PhoneSuppression findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") PhoneSuppression row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<PhoneSuppression> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    long countPhone(java.util.Map<String, Object> parameters);

    long sourceAiRuntime231_8(java.util.Map<String, Object> parameters);

    long sourceCallLifecycle65_4(java.util.Map<String, Object> parameters);

    long sourceConversationPolicy66_4(java.util.Map<String, Object> parameters);
}
