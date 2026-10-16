package com.duozhi.outbound;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class SecurityAttackIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired Store store;
    private String token() throws Exception {
        String body=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"12345678\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer "+mapper.readTree(body).path("access_token").asText();
    }
    @Test void duplicateCredentialsCannotSelectTheFirstValidIdentity() throws Exception {
        String auth=token();
        mvc.perform(get("/api/v1/auth/me").header("Authorization",auth,"Bearer forged")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/auth/me").header("Authorization",auth).header("X-Tenant-ID","1","2")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/auth/me").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
    }
    @Test void ambiguousWebhookHeadersCannotWriteEvents() throws Exception {
        long before=store.count("callevent","");
        mvc.perform(post("/api/v1/webhooks/telephony/status").header("X-Webhook-Token","valid-looking","forged")
            .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        assertEquals(before,store.count("callevent",""));
    }
    @Test void credentialAliasAndMalformedGatewayCannotBePersisted() throws Exception {
        String auth=token();long before=store.count("telephonyline","");
        for(String extra:new String[]{"\"credential_ref\":\"tenant/key\"","\"gateway_url\":\"http://user:pass@gateway.test\"","\"gateway_url\":\"http://gateway.test/#fragment\""})
            mvc.perform(post("/api/v1/admin/lines").header("Authorization",auth).contentType("application/json")
                .content("{\"name\":\"synthetic security regression\",\"provider\":\"mock\","+extra+"}")).andExpect(status().isBadRequest());
        assertEquals(before,store.count("telephonyline",""));
    }
    @Test void sqlPayloadIsDataAndNeverBroadensContactSearch() throws Exception {
        mvc.perform(get("/api/v1/contacts").header("Authorization",token()).param("keyword","' OR tenant_id<>1 --"))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void administratorCannotEnableRealGatewayWithoutIndependentApproval() throws Exception {
        long before=store.count("telephonyline","");
        mvc.perform(post("/api/v1/admin/lines").header("Authorization",token()).header("X-Security-Approval","forged")
            .contentType("application/json").content("{\"name\":\"synthetic denied gateway\",\"provider\":\"http\",\"gateway_url\":\"http://127.0.0.1:9\"}"))
            .andExpect(status().isForbidden());
        assertEquals(before,store.count("telephonyline",""));
    }
}
