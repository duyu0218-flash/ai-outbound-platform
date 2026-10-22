package com.duozhi.outbound.callback.dao;

import com.duozhi.outbound.callback.domain.CallbackInboxPartition;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackInboxPartitionMapper {
    int insert(@Param("row")
    CallbackInboxPartition row);

    int insertExplicitId(@Param("row")
    CallbackInboxPartition row);

    CallbackInboxPartition findById(@Param("id")
    Integer id);

    int updateById(@Param("row")
    CallbackInboxPartition row);

    int deleteById(@Param("id")
    Integer id);

    List<CallbackInboxPartition> list(@Param("offset")
    int offset, @Param("limit")
    int limit);

    int patchFields(
            @Param("id")
            Object id,
            @Param("tenantId")
            long tenantId,
            @Param("changes")
            Map<String, Object> changes);

    List<CallbackInboxPartition> sourceCallbackInbox56_4(Map<String, Object> parameters);

    List<CallbackInboxPartition> sourceCallbackInbox80_6(Map<String, Object> parameters);

    int rawCallbackInbox67_4(Map<String, Object> parameters);

    int rawCallbackInbox106_7(Map<String, Object> parameters);

    int initializePartition(Map<String, Object> parameters);
}
