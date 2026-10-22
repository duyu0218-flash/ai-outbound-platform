package com.duozhi.outbound;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class ManagementIntegrationTest {
    @Autowired
    MockMvc mvc;@Autowired
    ObjectMapper mapper;@Autowired
    Store store;
    String token()throws Exception{
        MvcResult result=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"12345678\"}")).andExpect(status().isOk()).andReturn();
        return "Bearer "+mapper.readTree(result.getResponse().getContentAsString()).get("access_token").asText();
    }
    @Test
    void contactPersistenceNormalizationTenantAndAudit()throws Exception{
        String auth=token();
        MvcResult response=mvc.perform(post("/api/v1/contacts").header("Authorization",auth).contentType("application/json").content("{\"phone\":\"+86 130-0000-0001\",\"name\":\"测试客户\",\"consent_state\":\"consented\",\"tenant_id\":999}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("8613000000001")).andExpect(jsonPath("$.tenant_id").value(1)).andExpect(jsonPath("$.consented_by").value("admin")).andReturn();
        long id=mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/v1/contacts/"+id).header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("测试客户"));
        mvc.perform(patch("/api/v1/contacts/"+id).header("Authorization",auth).contentType("application/json").content("{\"consent_state\":\"revoked\",\"dnc\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.consented_at").isEmpty()).andExpect(jsonPath("$.dnc").value(true));
        mvc.perform(post("/api/v1/contacts").header("Authorization",auth).contentType("application/json").content("{\"phone\":\"8613000000001\"}")).andExpect(status().isConflict());
        assertTrue(store.count("auditlog","WHERE tenant_id=? AND resource_type=?",1,"contact")>=2);
        mvc.perform(delete("/api/v1/contacts/"+id).header("Authorization",auth)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/contacts/"+id).header("Authorization",auth)).andExpect(status().isNotFound());
    }
    @Test
    void invalidContactDoesNotWrite()throws Exception{
        String auth=token();long before=store.count("contact","");
        for(String body:new String[]{"{\"phone\":\"abcdefg\"}","{\"phone\":\"13000000002\",\"timezone\":\"Not/AZone\"}","{\"phone\":\"13000000002\",\"consent_state\":\"bad\"}"}){
            int status=mvc.perform(post("/api/v1/contacts").header("Authorization",auth).contentType("application/json").content(body)).andReturn().getResponse().getStatus();assertTrue(status==400||status==422);
        }
        assertEquals(before,store.count("contact",""));
        mvc.perform(get("/api/v1/contacts?page=0").header("Authorization",auth)).andExpect(status().isBadRequest());
    }
    @Test
    void referencedContactsCannotBeDeleted()throws Exception{
        String auth=token();Map<String,Object> row=store.insert("contact",Values.map("tenant_id",1,"phone","13000000003"));
        store.insert("callsession",Values.map("tenant_id",1,"contact_id",row.get("id"),"phone",row.get("phone"),"mode","ai_only"));
        mvc.perform(delete("/api/v1/contacts/"+row.get("id")).header("Authorization",auth)).andExpect(status().isConflict());
    }
    @Test
    void templateVersionAndSoftDelete()throws Exception{
        String auth=token();MvcResult response=mvc.perform(post("/api/v1/script-templates").header("Authorization",auth).contentType("application/json").content("{\"name\":\"开场模板\",\"content\":\"您好\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andReturn();
        long id=mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(put("/api/v1/script-templates/"+id).header("Authorization",auth).contentType("application/json").content("{\"content\":\"您好，有时间吗？\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(delete("/api/v1/script-templates/"+id).header("Authorization",auth)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/script-templates/"+id).header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.is_active").value(false));
    }
}
