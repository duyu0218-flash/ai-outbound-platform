package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.TaskReceipt;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskReceiptMapper {
    int insert(@Param("row") TaskReceipt row);
    TaskReceipt findById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") TaskReceipt row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    List<TaskReceipt> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    com.duozhi.outbound.entity.TaskReceipt findByIdempotencyKey(java.util.Map<String,Object> parameters);
}
