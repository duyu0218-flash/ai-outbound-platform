package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.ProductWorkItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductWorkItemMapper {
    int insert(@Param("row") ProductWorkItem row);
    ProductWorkItem findById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") ProductWorkItem row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    List<ProductWorkItem> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    int redactCallEvidence(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ProductWorkItem> expiredOrphans(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ProductWorkItem> sourceProductOperations51_4(java.util.Map<String,Object> parameters);
    long scopedConversationPolicy62(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.ProductWorkItem> scopedProductOperations54(java.util.Map<String,Object> parameters);
}
