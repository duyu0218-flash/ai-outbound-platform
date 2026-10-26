package com.duozhi.outbound.voiceai.dao;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface VoiceAiBenchmarkMapper {
    @Insert(
            "INSERT INTO aivoicetestset(id,tenant_id,name,script_snapshot,script_sha,samples_json) VALUES(#{id},#{tenant},#{name},#{script},#{sha},#{samples})")
    void createSet(Map<String, Object> values);

    @Select(
            "SELECT id,name,script_snapshot,script_sha,created_at FROM aivoicetestset WHERE tenant_id=#{tenant} ORDER BY created_at DESC LIMIT 50")
    List<Map<String, Object>> sets(long tenant);

    @Select("SELECT * FROM aivoicetestset WHERE tenant_id=#{tenant} AND id=#{id}")
    Map<String, Object> set(@Param("tenant")
    long tenant, @Param("id")
    String id);

    @Insert(
            "INSERT INTO aivoicetestrun(id,tenant_id,test_set_id,profile_id,client_key,profile_json,status,results_json,audio_json) VALUES(#{id},#{tenant},#{set},#{profile},#{key},#{snapshot},'running','[]','{}')")
    void createRun(Map<String, Object> values);

    @Select(
            "SELECT id,tenant_id,test_set_id,profile_id,client_key,profile_json,status,results_json,created_at,updated_at FROM aivoicetestrun WHERE tenant_id=#{tenant} AND client_key=#{key}")
    Map<String, Object> request(@Param("tenant")
    long tenant, @Param("key")
    String key);

    @Select(
            "SELECT id,test_set_id,profile_id,profile_json,status,results_json,created_at,updated_at FROM aivoicetestrun WHERE tenant_id=#{tenant} AND test_set_id=#{set} ORDER BY created_at DESC LIMIT 100")
    List<Map<String, Object>> runs(@Param("tenant")
    long tenant, @Param("set")
    String set);

    @Select(
            """
            SELECT JSON_UNQUOTE(JSON_EXTRACT(audio_json, CONCAT('$."',#{sample},'"')))
            FROM aivoicetestrun WHERE tenant_id=#{tenant} AND id=#{id}
            """)
    String audio(
            @Param("tenant")
            long tenant, @Param("id")
            String id, @Param("sample")
            String sample);

    @Select(
            "SELECT attempt,policy_json FROM conversationstate WHERE tenant_id=#{tenant} AND call_id=#{call} ORDER BY attempt DESC LIMIT 20")
    List<Map<String, Object>> callProfiles(
            @Param("tenant")
            long tenant, @Param("call")
            String call);

    @Update(
            "UPDATE aivoicetestrun SET status=#{status},results_json=#{results},audio_json=#{audios} WHERE tenant_id=#{tenant} AND id=#{id} AND status='running'")
    void finish(Map<String, Object> values);
}
