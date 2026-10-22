package com.duozhi.outbound.campaign.dao;

import com.duozhi.outbound.campaign.domain.CampaignContact;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CampaignContactMapper {
    int insert(@Param("row")
    CampaignContact row);

    int insertExplicitId(@Param("row")
    CampaignContact row);

    CampaignContact findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    CampaignContact row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<CampaignContact> list(
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

    List<CampaignContact> scopedCampaigns40(Map<String, Object> parameters);

    int replaceCampaignContacts(Map<String, Object> parameters);
}
