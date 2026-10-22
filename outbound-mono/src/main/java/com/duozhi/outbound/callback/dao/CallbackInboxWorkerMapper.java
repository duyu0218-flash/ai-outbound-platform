package com.duozhi.outbound.callback.dao;

import com.duozhi.outbound.callback.domain.CallbackInboxWorker;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackInboxWorkerMapper {
    int insert(@Param("row")
    CallbackInboxWorker row);

    CallbackInboxWorker findById(@Param("id")
    String id);

    int updateById(@Param("row")
    CallbackInboxWorker row);

    int deleteById(@Param("id")
    String id);

    List<CallbackInboxWorker> list(@Param("offset")
    int offset, @Param("limit")
    int limit);

    int patchFields(
            @Param("id")
            Object id,
            @Param("tenantId")
            long tenantId,
            @Param("changes")
            Map<String, Object> changes);

    long sourceCallbackInbox30_2(Map<String, Object> parameters);

    List<CallbackInboxWorker> sourceCallbackInbox36_3(Map<String, Object> parameters);

    int rawCallbackInbox40_2(Map<String, Object> parameters);

    int rawCallbackInbox108_8(Map<String, Object> parameters);
}
