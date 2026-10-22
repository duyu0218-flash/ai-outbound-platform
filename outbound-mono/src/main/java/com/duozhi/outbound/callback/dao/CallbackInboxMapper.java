package com.duozhi.outbound.callback.dao;

import com.duozhi.outbound.callback.domain.CallbackInbox;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallbackInboxMapper {
    int insert(@Param("row") CallbackInbox row);

    int insertExplicitId(@Param("row") CallbackInbox row);

    CallbackInbox findById(@Param("id") Long id);

    int updateById(@Param("row") CallbackInbox row);

    int deleteById(@Param("id") Long id);

    List<CallbackInbox> list(@Param("offset") int offset, @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.callback.domain.CallbackInbox> sourceCallbackInbox28_0(
            java.util.Map<String, Object> parameters);

    long sourceCallbackInbox30_1(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.callback.domain.CallbackInbox> sourceCallbackInbox58_5(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.callback.domain.CallbackInbox> sourceCallbackInbox100_7(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.callback.domain.CallbackInbox> sourceCallbackInbox115_8(
            java.util.Map<String, Object> parameters);

    java.util.List<java.util.Map<String, Object>> rawTaskWorker192_1(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.callback.domain.CallbackInbox> expiredReceipts(
            java.util.Map<String, Object> parameters);

    int deleteExpiredReceipt(java.util.Map<String, Object> parameters);
}
