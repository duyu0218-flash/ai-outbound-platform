package com.duozhi.outbound.callback.dao;

import com.duozhi.outbound.callback.domain.CallbackInboxPartition;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackInboxPartitionMapper {
    int insert(@Param("row") CallbackInboxPartition row);

    int insertExplicitId(@Param("row") CallbackInboxPartition row);

    CallbackInboxPartition findById(@Param("id") Integer id);

    int updateById(@Param("row") CallbackInboxPartition row);

    int deleteById(@Param("id") Integer id);

    List<CallbackInboxPartition> list(@Param("offset") int offset, @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.callback.domain.CallbackInboxPartition>
            sourceCallbackInbox56_4(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.callback.domain.CallbackInboxPartition>
            sourceCallbackInbox80_6(java.util.Map<String, Object> parameters);

    int rawCallbackInbox67_4(java.util.Map<String, Object> parameters);

    int rawCallbackInbox106_7(java.util.Map<String, Object> parameters);

    int initializePartition(java.util.Map<String, Object> parameters);
}
