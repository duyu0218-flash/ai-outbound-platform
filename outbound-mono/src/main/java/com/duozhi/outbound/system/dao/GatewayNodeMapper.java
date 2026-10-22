package com.duozhi.outbound.system.dao;

import com.duozhi.outbound.system.domain.GatewayNode;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GatewayNodeMapper {
    int insert(@Param("row")
    GatewayNode row);

    GatewayNode findById(@Param("id")
    String id);

    int updateById(@Param("row")
    GatewayNode row);

    int deleteById(@Param("id")
    String id);

    List<GatewayNode> list(@Param("offset")
    int offset, @Param("limit")
    int limit);

    int patchFields(
            @Param("id")
            Object id,
            @Param("tenantId")
            long tenantId,
            @Param("changes")
            Map<String, Object> changes);

    List<GatewayNode> sourceDependencyHealth21_0(Map<String, Object> parameters);

    List<GatewayNode> sourceGatewayCluster51_0(Map<String, Object> parameters);

    List<GatewayNode> sourceGatewayCluster64_2(Map<String, Object> parameters);
}
