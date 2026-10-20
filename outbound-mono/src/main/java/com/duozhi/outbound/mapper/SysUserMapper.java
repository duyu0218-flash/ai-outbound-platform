package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.SysUser;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysUserMapper {
    long activeAgentCalls(java.util.Map<String,Object> parameters);
    int insert(@Param("row") SysUser row);
    int insertExplicitId(@Param("row") SysUser row);
    SysUser findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") SysUser row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<SysUser> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.SysUser> sourceCallLifecycle109_8(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SysUser> sourceConversationPolicy85_9(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SysUser> sourceHandoffs39_3(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SysUser> sourceProductOperations30_1(java.util.Map<String,Object> parameters);
    long sourceSystemOverview52_5(java.util.Map<String,Object> parameters);
    long sourceSystemOverview52_6(java.util.Map<String,Object> parameters);
    long scopedOperationalMetrics33(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SysUser> readyHandoffAgent(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.SysUser> reportLabels(java.util.Map<String,Object> parameters);
}
