package com.duozhi.outbound;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class StaticSecurityBoundaryTest {
    @TempDir Path files;
    @Test void unicodeCaseMappingCannotBypassTrustedHosts() throws Exception {
        HttpBoundary filter=new HttpBoundary(new Config(new MockEnvironment().withProperty("TRUSTED_HOSTS","key.test")));
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/v1/runtime");request.setServerName("\u212Aey.test");
        MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->{});
        assertEquals(400,response.getStatus());
    }
    @Test void duplicateSecurityHeadersAreRejectedBeforeAuthentication() throws Exception {
        for(String header:new String[]{"Authorization","X-API-Key","X-Tenant-ID","X-Security-Approval","X-Webhook-Token","X-Webhook-Timestamp","X-Webhook-Signature","Origin"}){
            HttpBoundary filter=new HttpBoundary(new Config(new MockEnvironment()));
            MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/v1/auth/me");request.addHeader(header,"first");request.addHeader(header,"second");
            MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->{});
            assertEquals(400,response.getStatus(),header);
        }
    }
    @Test void credentialedCorsCannotUseWildcardEvenInDevelopment() {
        assertThrows(IllegalArgumentException.class,()->new HttpBoundary(new Config(new MockEnvironment().withProperty("CORS_ALLOW_ORIGINS","*"))));
    }
    @Test void corsAllowsOnlyExplicitOriginsWithoutAmbientCookieCredentials() throws Exception {
        HttpBoundary filter=new HttpBoundary(new Config(new MockEnvironment().withProperty("CORS_ALLOW_ORIGINS","https://portal.test")));
        for(String origin:List.of("https://portal.test","https://portal.test.evil","null")) {
            MockHttpServletRequest request=new MockHttpServletRequest("OPTIONS","/api/v1/contacts");request.addHeader("Origin",origin);
            MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->{});
            assertEquals(origin.equals("https://portal.test")?origin:null,response.getHeader("Access-Control-Allow-Origin"));
            assertNull(response.getHeader("Access-Control-Allow-Credentials"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        }
    }
    @Test void productionRejectsPlaintextRedisConfiguration() {
        Config config=new Config(new MockEnvironment().withProperty("ENV","production")
            .withProperty("REDIS_URL","redis://:synthetic-long-redis-password@redis:6379/0"));
        assertTrue(assertThrows(IllegalStateException.class,()->ProductionConfiguration.validate(config)).getMessage().contains("REDIS_URL"));
    }
    @Test void protocolCaseConversionPreservesUnicodeAndIsLocaleIndependent() {
        Locale previous=Locale.getDefault();
        try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("http",Ascii.lower("HTTP"));assertEquals("IN_AI",Ascii.upper("in_ai"));
            assertEquals("\u212Aey",Ascii.lower("\u212Aey"));assertEquals("straße 中文",Ascii.lower("Straße 中文"));assertEquals("STRAßE 中文",Ascii.upper("Straße 中文"));
        }finally{Locale.setDefault(previous);}
    }
    @Test void outboundHeaderInjectionAndFramingOverridesAreRejectedBeforeConnecting() {
        IntegrationTasks tasks=new IntegrationTasks(null,null,new Config(new MockEnvironment()),null,null,null);
        for(Map<String,String> headers:List.of(Map.of("X-Test\r\nInjected","yes"),Map.of("X-Test","yes\r\nInjected: yes"),
                Map.of("Host","evil.test"),Map.of("Content-Length","0"),Map.of("Transfer-Encoding","chunked"),
                Map.of("Authorization","one","authorization","two"),Map.of("X-Test","\u212A"))) {
            ApiError error=assertThrows(ApiError.class,()->tasks.pinnedPost("http://127.0.0.1:1",new byte[0],headers,100));
            assertEquals(422,error.status);assertTrue(error.getMessage().contains("header"));
        }
    }
    @Test void productionCallbackTransportRejectsHttpEvenIfValidationWasBypassed() {
        IntegrationTasks tasks=new IntegrationTasks(null,null,new Config(new MockEnvironment().withProperty("ENV","production")),null,null,null);
        ApiError error=assertThrows(ApiError.class,()->tasks.pinnedPost("http://127.0.0.1:1",new byte[0],Map.of(),100));
        assertEquals(422,error.status);assertTrue(error.getMessage().contains("HTTPS"));
    }
    @Test void malformedCredentialReferencesCannotAliasOtherPlatformSecrets() {
        Store store=mock(Store.class);
        when(store.one(eq("telephonyline"),anyString(),any(Object[].class))).thenReturn(JsonSupport.map("provider","mock","credential_ref","tenant/key","gateway_url",""));
        Config config=new Config(new MockEnvironment().withProperty("TELEPHONY_SECRET_TENANT_KEY","synthetic-secret"));
        TelephonyClient client=new TelephonyClient(store,config,new JsonSupport(new tools.jackson.databind.ObjectMapper()));
        ApiError error=assertThrows(ApiError.class,()->client.command(JsonSupport.map("id",UUID.randomUUID(),"tenant_id",1),"hangup",Map.of()));
        assertEquals(422,error.status);
    }
    @Test void uuid5RemainsCompatibleWithIndependentPythonReference() {
        assertEquals("4fd35a71-71ef-5a55-a9d9-aa75c889a6d0",ConversationPolicy.uuid5("https://example.com").toString());
    }
    @Test void configurationFilesAreRegularAndBoundedIncludingSecretSymlinks() throws Exception {
        Path secret=files.resolve("token");Files.writeString(secret,"测试-token");
        assertEquals("测试-token",ConfigFiles.readUtf8(secret.toString(),32));
        Path link=files.resolve("mounted-secret");Files.createSymbolicLink(link,secret);
        assertEquals("测试-token",ConfigFiles.readUtf8(link.toString(),32));
        assertThrows(java.io.IOException.class,()->ConfigFiles.readUtf8(secret.toString(),3));
        assertThrows(java.io.IOException.class,()->ConfigFiles.readUtf8(files.toString(),32));
        assertThrows(java.io.IOException.class,()->ConfigFiles.readUtf8(files.resolve("missing").toString(),32));
    }
}
