package com.duozhi.outbound;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class AdminIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Store store;
    private String login(String name,String password)throws Exception{
        String response=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(mapper.writeValueAsString(Values.map("username",name,"password",password)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer "+mapper.readTree(response).get("access_token").asText();
    }
    @Test void accountLifecycleRevokesExistingSessions()throws Exception{
        String admin=login("admin","12345678");
        String response=mvc.perform(post("/api/v1/admin/users").header("Authorization",admin).contentType("application/json").content("{\"username\":\"lifecycle-agent\",\"password\":\"first-pass\",\"full_name\":\"Lifecycle\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.password_hash").doesNotExist()).andReturn().getResponse().getContentAsString();
        long id=mapper.readTree(response).get("id").asLong();String old=login("lifecycle-agent","first-pass");
        mvc.perform(post("/api/v1/admin/users/"+id+"/reset-password").header("Authorization",admin).contentType("application/json").content("{\"password\":\"second-pass\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization",old)).andExpect(status().isUnauthorized());
        String current=login("lifecycle-agent","second-pass");
        mvc.perform(delete("/api/v1/admin/users/"+id).header("Authorization",admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization",current)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"lifecycle-agent\",\"password\":\"second-pass\"}")).andExpect(status().isUnauthorized());
        Map<String,Object> administrator=store.one("user","WHERE username=?","admin");
        mvc.perform(delete("/api/v1/admin/users/"+administrator.get("id")).header("Authorization",admin)).andExpect(status().isConflict());
    }
    @Test void settingsRoundTripAndDestinationRestrictions()throws Exception{
        String admin=login("admin","12345678");
        mvc.perform(put("/api/v1/admin/settings/compliance").header("Authorization",admin).contentType("application/json").content("{\"data\":{\"recording_notice_text\":\"Java 8 保存回显测试\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.recording_notice_text").value("Java 8 保存回显测试"));
        mvc.perform(get("/api/v1/admin/settings/compliance").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.recording_notice_text").value("Java 8 保存回显测试"));
        mvc.perform(put("/api/v1/admin/settings/ai").header("Authorization",admin).contentType("application/json").content("{\"data\":{\"agent_url\":\"https://unapproved.example.com\"}}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/admin/settings/integration").header("Authorization",admin).contentType("application/json").content("{\"data\":{\"webhook_base_url\":\"http://127.0.0.1/private\"}}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/admin/settings/capacity").header("Authorization",admin).contentType("application/json").content("{\"data\":{\"max_concurrent_calls\":21}}")).andExpect(status().isUnprocessableEntity());
    }
    @Test void mockLineSaveAndRealLineApproval()throws Exception{
        String admin=login("admin","12345678");
        mvc.perform(post("/api/v1/admin/lines").header("Authorization",admin).contentType("application/json").content("{\"name\":\"isolated-mock-line\",\"provider\":\"mock\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("mock"));
        mvc.perform(post("/api/v1/admin/lines").header("Authorization",admin).contentType("application/json").content("{\"name\":\"unapproved-line\",\"provider\":\"http\",\"gateway_url\":\"https://gateway.example.com\"}"))
            .andExpect(status().isForbidden());
        assertEquals(0,store.count("telephonyline","WHERE name=?","unapproved-line"));
    }
}
