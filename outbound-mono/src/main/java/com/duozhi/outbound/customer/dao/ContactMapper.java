package com.duozhi.outbound.customer.dao;

import com.duozhi.outbound.customer.domain.Contact;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContactMapper {
    int insert(@Param("row") Contact row);

    int insertExplicitId(@Param("row") Contact row);

    Contact findById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    int updateById(@Param("row") Contact row, @Param("tenantId") Integer tenantId);

    int deleteById(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    List<Contact> list(
            @Param("tenantId") Integer tenantId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    int patchFields(
            @org.apache.ibatis.annotations.Param("id") Object id,
            @org.apache.ibatis.annotations.Param("tenantId") long tenantId,
            @org.apache.ibatis.annotations.Param("changes") java.util.Map<String, Object> changes);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> searchContacts(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> exportContacts(
            java.util.Map<String, Object> parameters);

    com.duozhi.outbound.customer.domain.Contact findByPhone(
            java.util.Map<String, Object> parameters);

    long countPhone(java.util.Map<String, Object> parameters);

    long campaignReferences(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> sourceCampaigns54_2(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> sourceCampaigns218_10(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> sourceConversationPolicy67_5(
            java.util.Map<String, Object> parameters);

    long sourceDashboard29_1(java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> sourceHandoffs74_7(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> sourceReports86_2(
            java.util.Map<String, Object> parameters);

    java.util.List<com.duozhi.outbound.customer.domain.Contact> sourceReports180_7(
            java.util.Map<String, Object> parameters);
}
