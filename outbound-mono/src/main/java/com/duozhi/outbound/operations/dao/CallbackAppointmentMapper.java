package com.duozhi.outbound.operations.dao;

import com.duozhi.outbound.operations.domain.CallbackAppointment;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackAppointmentMapper {
    int insert(@Param("row") CallbackAppointment row);

    CallbackAppointment findById(
            @Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") CallbackAppointment row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);

    List<CallbackAppointment> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.operations.domain.CallbackAppointment>
            sourceConversationPolicy77_7(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.operations.domain.CallbackAppointment>
            sourceProductOperations44_3(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.operations.domain.CallbackAppointment> scopedAiRuntime228(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.operations.domain.CallbackAppointment>
            scopedConversationPolicy182(java.util.Map<String, Object> parameters);
}
