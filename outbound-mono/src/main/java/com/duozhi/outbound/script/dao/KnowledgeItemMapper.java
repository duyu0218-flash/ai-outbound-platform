package com.duozhi.outbound.script.dao;

import com.duozhi.outbound.script.domain.KnowledgeItem;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeItemMapper {
    int insert(@Param("row")
    KnowledgeItem row);

    int insertExplicitId(@Param("row")
    KnowledgeItem row);

    KnowledgeItem findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    KnowledgeItem row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<KnowledgeItem> list(
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

    List<KnowledgeItem> allKnowledge(Map<String, Object> parameters);

    KnowledgeItem lockById(Map<String, Object> parameters);

    List<KnowledgeItem> sourceAiRuntime127_4(Map<String, Object> parameters);

    List<KnowledgeItem> sourceProductOperations31_2(Map<String, Object> parameters);
}
