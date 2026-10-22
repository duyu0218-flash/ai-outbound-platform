package com.duozhi.outbound.operations.dao;

import com.duozhi.outbound.operations.domain.CallbackAppointment;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackAppointmentMapper {
    int insert(@Param("row")
    CallbackAppointment row);

    CallbackAppointment findById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    CallbackAppointment row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    List<CallbackAppointment> list(
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

    List<CallbackAppointment> sourceConversationPolicy77_7(Map<String, Object> parameters);

    List<CallbackAppointment> sourceProductOperations44_3(Map<String, Object> parameters);

    List<CallbackAppointment> scopedAiRuntime228(Map<String, Object> parameters);

    List<CallbackAppointment> scopedConversationPolicy182(Map<String, Object> parameters);
}
