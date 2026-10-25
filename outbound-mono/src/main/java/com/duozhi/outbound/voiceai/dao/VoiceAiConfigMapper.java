package com.duozhi.outbound.voiceai.dao;

import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface VoiceAiConfigMapper {
    @Select("SELECT id FROM tenant WHERE id=#{tenant} FOR UPDATE")
    Long lock(long tenant);

    @Select(
            "SELECT revision,payload FROM aivoiceconfig WHERE tenant_id=#{tenant} ORDER BY revision DESC LIMIT 1")
    Map<String, Object> latest(long tenant);

    @Select(
            "SELECT revision,payload FROM aivoiceconfig WHERE tenant_id=#{tenant} AND revision=#{revision}")
    Map<String, Object> version(@Param("tenant")
    long tenant, @Param("revision")
    long revision);

    @Insert(
            "INSERT INTO aivoiceconfig(tenant_id,revision,payload) VALUES(#{tenant},#{revision},#{payload})")
    void insert(
            @Param("tenant")
            long tenant,
            @Param("revision")
            long revision,
            @Param("payload")
            String payload);
}
