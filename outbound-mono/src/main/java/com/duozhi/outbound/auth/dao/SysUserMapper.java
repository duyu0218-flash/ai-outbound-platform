package com.duozhi.outbound.auth.dao;

import com.duozhi.outbound.auth.domain.SysUser;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysUserMapper {
    long activeAgentCalls(Map<String, Object> parameters);

    int insert(@Param("row")
    SysUser row);

    int insertExplicitId(@Param("row")
    SysUser row);

    SysUser findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    SysUser row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<SysUser> list(
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

    List<SysUser> sourceCallLifecycle109_8(Map<String, Object> parameters);

    List<SysUser> sourceConversationPolicy85_9(Map<String, Object> parameters);

    List<SysUser> sourceHandoffs39_3(Map<String, Object> parameters);

    List<SysUser> sourceProductOperations30_1(Map<String, Object> parameters);

    long sourceSystemOverview52_5(Map<String, Object> parameters);

    long sourceSystemOverview52_6(Map<String, Object> parameters);

    long scopedOperationalMetrics33(Map<String, Object> parameters);

    List<SysUser> readyHandoffAgent(Map<String, Object> parameters);

    List<SysUser> reportLabels(Map<String, Object> parameters);
}
