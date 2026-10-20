package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.CallbackInboxWorker;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackInboxWorkerMapper {
    int insert(@Param("row") CallbackInboxWorker row);
    CallbackInboxWorker findById(@Param("id") String id);
    int updateById(@Param("row") CallbackInboxWorker row);
    int deleteById(@Param("id") String id);
    List<CallbackInboxWorker> list(@Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    long sourceCallbackInbox30_2(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.CallbackInboxWorker> sourceCallbackInbox36_3(java.util.Map<String,Object> parameters);
    int rawCallbackInbox40_2(java.util.Map<String,Object> parameters);
    int rawCallbackInbox108_8(java.util.Map<String,Object> parameters);
}
