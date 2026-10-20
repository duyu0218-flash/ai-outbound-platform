package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.ContactImportJob;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContactImportJobMapper {
    int insert(@Param("row") ContactImportJob row);
    ContactImportJob findById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") ContactImportJob row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") java.util.UUID id, @Param("tenantId") Integer tenantId);
    List<ContactImportJob> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    com.duozhi.outbound.entity.ContactImportJob findByRequestKey(java.util.Map<String,Object> parameters);
}
