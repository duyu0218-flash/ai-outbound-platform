package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.GatewayNode;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GatewayNodeMapper {
    int insert(@Param("row") GatewayNode row);
    GatewayNode findById(@Param("id") String id);
    int updateById(@Param("row") GatewayNode row);
    int deleteById(@Param("id") String id);
    List<GatewayNode> list(@Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.GatewayNode> sourceDependencyHealth21_0(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.GatewayNode> sourceGatewayCluster51_0(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.GatewayNode> sourceGatewayCluster64_2(java.util.Map<String,Object> parameters);
}
