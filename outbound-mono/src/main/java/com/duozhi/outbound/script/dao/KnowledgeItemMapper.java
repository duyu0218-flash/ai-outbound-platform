package com.duozhi.outbound.script.dao;

import com.duozhi.outbound.script.domain.KnowledgeItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeItemMapper {
    int insert(@Param("row") KnowledgeItem row);

    int insertExplicitId(@Param("row") KnowledgeItem row);

    KnowledgeItem findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") KnowledgeItem row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<KnowledgeItem> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.script.domain.KnowledgeItem> allKnowledge(
            java.util.Map<String, Object> parameters);

    com.duozhi.outbound.script.domain.KnowledgeItem lockById(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.script.domain.KnowledgeItem> sourceAiRuntime127_4(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.script.domain.KnowledgeItem> sourceProductOperations31_2(
            java.util.Map<String, Object> parameters);
}
