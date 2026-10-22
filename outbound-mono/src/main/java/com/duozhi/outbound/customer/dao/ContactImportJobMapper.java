package com.duozhi.outbound.customer.dao;

import com.duozhi.outbound.customer.domain.ContactImportJob;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContactImportJobMapper {
    int insert(@Param("row")
    ContactImportJob row);

    ContactImportJob findById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    ContactImportJob row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    UUID id, @Param("tenantId")
    Integer tenantId);

    List<ContactImportJob> list(
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

    ContactImportJob findByRequestKey(Map<String, Object> parameters);
}
