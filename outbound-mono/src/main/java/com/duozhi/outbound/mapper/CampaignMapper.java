package com.duozhi.outbound.mapper;

import com.duozhi.outbound.entity.Campaign;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CampaignMapper {
    int insert(@Param("row") Campaign row);
    int insertExplicitId(@Param("row") Campaign row);
    Campaign findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    int updateById(@Param("row") Campaign row, @Param("tenantId") Integer tenantId);
    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
    List<Campaign> list(@Param("tenantId") Integer tenantId, @Param("offset") int offset, @Param("limit") int limit);
    int patchFields(@org.apache.ibatis.annotations.Param("id") Object id, @org.apache.ibatis.annotations.Param("tenantId") long tenantId, @org.apache.ibatis.annotations.Param("changes") java.util.Map<String,Object> changes);
    java.util.List<com.duozhi.outbound.entity.Campaign> sourceCallLifecycle55_1(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.Campaign> sourceCallLifecycle66_5(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.Campaign> sourceCampaigns34_0(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.Campaign> sourceCampaigns120_6(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.Campaign> sourceDashboard28_0(java.util.Map<String,Object> parameters);
    long sourceDashboard29_3(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.Campaign> sourceHandoffs74_8(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.Campaign> sourceReports156_5(java.util.Map<String,Object> parameters);
    java.util.List<com.duozhi.outbound.entity.Campaign> reportLabels(java.util.Map<String,Object> parameters);
}
