package com.duozhi.outbound.customer.dao;

import com.duozhi.outbound.customer.domain.Contact;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContactMapper {
    int insert(@Param("row")
    Contact row);

    int insertExplicitId(@Param("row")
    Contact row);

    Contact findById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    int updateById(@Param("row")
    Contact row, @Param("tenantId")
    Integer tenantId);

    int deleteById(@Param("id")
    Integer id, @Param("tenantId")
    Integer tenantId);

    List<Contact> list(
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

    List<Contact> searchContacts(Map<String, Object> parameters);

    List<Contact> exportContacts(Map<String, Object> parameters);

    Contact findByPhone(Map<String, Object> parameters);

    long countPhone(Map<String, Object> parameters);

    long campaignReferences(Map<String, Object> parameters);

    List<Contact> sourceCampaigns54_2(Map<String, Object> parameters);

    List<Contact> sourceCampaigns218_10(Map<String, Object> parameters);

    List<Contact> sourceConversationPolicy67_5(Map<String, Object> parameters);

    long sourceDashboard29_1(Map<String, Object> parameters);

    List<Contact> sourceHandoffs74_7(Map<String, Object> parameters);

    List<Contact> sourceReports86_2(Map<String, Object> parameters);

    List<Contact> sourceReports180_7(Map<String, Object> parameters);
}
