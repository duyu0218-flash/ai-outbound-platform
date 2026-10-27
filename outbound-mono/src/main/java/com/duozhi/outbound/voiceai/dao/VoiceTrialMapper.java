package com.duozhi.outbound.voiceai.dao;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface VoiceTrialMapper {
    @Insert(
            "INSERT INTO aivoicetrial(id,tenant_id,user_id,contact_id,client_key,request_hash,status,script_snapshot,profile_json,events_json,result_json,published_json) VALUES(#{id},#{tenant_id},#{user_id},#{contact_id},#{client_key},#{request_hash},'created',#{script_snapshot},#{profile_json},'[]','{}','{}')")
    void create(Map<String, Object> row);

    @Select("SELECT * FROM aivoicetrial WHERE tenant_id=#{tenant} AND id=#{id}")
    Map<String, Object> get(@Param("tenant")
    long tenant, @Param("id")
    String id);

    @Select("SELECT * FROM aivoicetrial WHERE tenant_id=#{tenant} AND client_key=#{key}")
    Map<String, Object> byKey(@Param("tenant")
    long tenant, @Param("key")
    String key);

    @Select(
            "SELECT id,contact_id,status,created_at,updated_at FROM aivoicetrial WHERE tenant_id=#{tenant} ORDER BY created_at DESC LIMIT 100")
    List<Map<String, Object>> list(long tenant);

    @Update(
            "UPDATE aivoicetrial SET status=#{status},events_json=#{events},result_json=#{result} WHERE tenant_id=#{tenant} AND id=#{id}")
    void save(
            @Param("tenant")
            long tenant,
            @Param("id")
            String id,
            @Param("status")
            String status,
            @Param("events")
            String events,
            @Param("result")
            String result);

    @Update(
            "UPDATE aivoicetrial SET published_json=#{result} WHERE tenant_id=#{tenant} AND id=#{id}")
    void published(
            @Param("tenant")
            long tenant, @Param("id")
            String id, @Param("result")
            String result);

    @Update("UPDATE aivoicetrial SET status='interrupted' WHERE status IN ('created','active')")
    void recover();

    @Insert(
            "INSERT INTO aivoicetrialaudio(trial_id,tenant_id,audio_id,direction,sample_rate,pcm) VALUES(#{id},#{tenant},#{audio},#{direction},#{rate},#{pcm})")
    void audio(Map<String, Object> row);

    @Select(
            "SELECT audio_id,direction,sample_rate,OCTET_LENGTH(pcm) AS bytes FROM aivoicetrialaudio WHERE tenant_id=#{tenant} AND trial_id=#{id} ORDER BY audio_id")
    List<Map<String, Object>> audios(@Param("tenant")
    long tenant, @Param("id")
    String id);

    @Select(
            "SELECT sample_rate,pcm FROM aivoicetrialaudio WHERE tenant_id=#{tenant} AND trial_id=#{id} AND audio_id=#{audio}")
    Map<String, Object> audioGet(
            @Param("tenant")
            long tenant, @Param("id")
            String id, @Param("audio")
            String audio);
}
