package com.duozhi.outbound.system.dao;

import com.duozhi.outbound.system.domain.SmsLog;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SmsLogMapper {
    int insert(@Param("row")
    SmsLog row);

    int insertExplicitId(@Param("row")
    SmsLog row);

    SmsLog findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    SmsLog row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<SmsLog> list(
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

    List<SmsLog> expiredPayloads(Map<String, Object> parameters);

    List<SmsLog> scopedVoiceCallbacks148(Map<String, Object> parameters);

    List<SmsLog> scopedVoiceCallbacks149(Map<String, Object> parameters);

    List<Map<String, Object>> rawReports127_0(Map<String, Object> parameters);

    List<SmsLog> searchSms(Map<String, Object> parameters);

    List<Map<String, Object>> reportPageSms(Map<String, Object> parameters);
}
