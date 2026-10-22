package com.duozhi.outbound.system.dao;

import com.duozhi.outbound.system.domain.SmsLog;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SmsLogMapper {
    int insert(@Param("row") SmsLog row);

    int insertExplicitId(@Param("row") SmsLog row);

    SmsLog findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") SmsLog row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<SmsLog> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.system.domain.SmsLog> expiredPayloads(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.system.domain.SmsLog> scopedVoiceCallbacks148(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.system.domain.SmsLog> scopedVoiceCallbacks149(
            java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> rawReports127_0(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.system.domain.SmsLog> searchSms(
            java.util.Map<String, Object> parameters);
}
