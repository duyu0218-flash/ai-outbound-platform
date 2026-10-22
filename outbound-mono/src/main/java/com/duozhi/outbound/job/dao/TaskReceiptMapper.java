package com.duozhi.outbound.job.dao;

import com.duozhi.outbound.job.domain.TaskReceipt;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskReceiptMapper {
    int insert(@Param("row")
    TaskReceipt row);

    TaskReceipt findById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    TaskReceipt row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    List<TaskReceipt> list(
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

    TaskReceipt findByIdempotencyKey(Map<String, Object> parameters);
}
