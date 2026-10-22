package com.duozhi.outbound.operations.dao;

import com.duozhi.outbound.operations.domain.ProductWorkItem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductWorkItemMapper {
    int insert(@Param("row")
    ProductWorkItem row);

    ProductWorkItem findById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    ProductWorkItem row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    List<ProductWorkItem> list(
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

    int redactCallEvidence(Map<String, Object> parameters);

    List<ProductWorkItem> expiredOrphans(Map<String, Object> parameters);

    List<ProductWorkItem> sourceProductOperations51_4(Map<String, Object> parameters);

    long scopedConversationPolicy62(Map<String, Object> parameters);

    List<ProductWorkItem> scopedProductOperations54(Map<String, Object> parameters);
}
