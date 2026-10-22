package com.duozhi.outbound.campaign.dao;

import com.duozhi.outbound.campaign.domain.CampaignContact;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CampaignContactMapper {
    int insert(@Param("row") CampaignContact row);

    int insertExplicitId(@Param("row") CampaignContact row);

    CampaignContact findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") CampaignContact row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<CampaignContact> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.campaign.domain.CampaignContact> scopedCampaigns40(
            java.util.Map<String, Object> parameters);

    int replaceCampaignContacts(java.util.Map<String, Object> parameters);
}
