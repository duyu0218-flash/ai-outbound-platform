package com.duozhi.outbound.campaign.dao;

import com.duozhi.outbound.campaign.domain.Campaign;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CampaignMapper {
    int insert(@Param("row")
    Campaign row);

    int insertExplicitId(@Param("row")
    Campaign row);

    Campaign findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    Campaign row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<Campaign> list(
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

    List<Campaign> sourceCallLifecycle55_1(Map<String, Object> parameters);

    List<Campaign> sourceCallLifecycle66_5(Map<String, Object> parameters);

    List<Campaign> sourceCampaigns34_0(Map<String, Object> parameters);

    List<Campaign> sourceCampaigns120_6(Map<String, Object> parameters);

    List<Campaign> sourceDashboard28_0(Map<String, Object> parameters);

    long sourceDashboard29_3(Map<String, Object> parameters);

    List<Campaign> sourceHandoffs74_8(Map<String, Object> parameters);

    List<Campaign> sourceReports156_5(Map<String, Object> parameters);

    List<Campaign> reportLabels(Map<String, Object> parameters);
}
