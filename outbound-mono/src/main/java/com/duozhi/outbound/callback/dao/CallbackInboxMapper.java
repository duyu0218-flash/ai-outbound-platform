package com.duozhi.outbound.callback.dao;

import com.duozhi.outbound.callback.domain.CallbackInbox;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackInboxMapper {
    int insert(@Param("row")
    CallbackInbox row);

    int insertExplicitId(@Param("row")
    CallbackInbox row);

    CallbackInbox findById(@Param("id")
    Long id);

    int updateById(@Param("row")
    CallbackInbox row);

    int deleteById(@Param("id")
    Long id);

    List<CallbackInbox> list(@Param("offset")
    int offset, @Param("limit")
    int limit);

    int patchFields(
            @Param("id")
            Object id,
            @Param("tenantId")
            long tenantId,
            @Param("changes")
            Map<String, Object> changes);

    List<CallbackInbox> sourceCallbackInbox28_0(Map<String, Object> parameters);

    long sourceCallbackInbox30_1(Map<String, Object> parameters);

    List<CallbackInbox> sourceCallbackInbox58_5(Map<String, Object> parameters);

    List<CallbackInbox> sourceCallbackInbox100_7(Map<String, Object> parameters);

    List<CallbackInbox> sourceCallbackInbox115_8(Map<String, Object> parameters);

    List<Map<String, Object>> rawTaskWorker192_1(Map<String, Object> parameters);

    List<CallbackInbox> expiredReceipts(Map<String, Object> parameters);

    int deleteExpiredReceipt(Map<String, Object> parameters);
}
