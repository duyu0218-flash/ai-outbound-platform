package com.duozhi.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;

class HttpBoundaryTest {
    private ConfigurableApplicationContext app;
    private int port;
    private final HttpClient http = Support.client();
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({AgentController.class, ApiDocumentation.class, Boundary.class, ServiceErrors.class})
    static class AgentFixture {
        @Bean
        Settings settings() { return new Settings(Map.of("SERVICE_TOKEN", "isolated-boundary-token", "LLM_PROVIDER", "rule")); }
    }
    @BeforeEach
    void start() {
        app = SpringApplication.run(AgentFixture.class, "--server.port=0", "--server.address=127.0.0.1", "--service.role=agent", "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
        port = ((ServletWebServerApplicationContext)app).getWebServer().getPort();
    }
    @AfterEach
    void close() { app.close(); }
    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test
    void actualAgentRoutesEnforceCredentialAndRoleBoundaries() throws Exception {
        assertEquals(401, post("/agent/start", null, "{}").statusCode());
        assertEquals(401, post("/agent/start", "wrong", "{}").statusCode());
        var reply = post("/agent/start", "isolated-boundary-token", "{\"call_id\":\"http-test\",\"phone\":\"+8613900139000\",\"mode\":\"ai_only\",\"context\":{\"language\":\"zh-CN\"}}");
        assertEquals(200, reply.statusCode()); assertEquals("nosniff", reply.headers().firstValue("x-content-type-options").orElse(""));
        assertFalse(Support.str(Support.object(reply.body().getBytes(StandardCharsets.UTF_8)), "tts_text", "").isBlank());
        assertEquals(404, post("/v1/call/dial", "isolated-boundary-token", "{}").statusCode());
        assertEquals(422, post("/agent/start", "isolated-boundary-token", "{").statusCode());
    }
    @Test
    void documentationIsSameOriginReadOnlyAndDoesNotWeakenApiAuthentication() throws Exception {
        var docs = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, docs.statusCode());
        assertTrue(docs.headers().firstValue("content-security-policy").orElseThrow().contains("connect-src 'self'"));
        assertTrue(docs.body().contains("textContent")); assertFalse(docs.body().contains("innerHTML"));
        var spec = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/openapi.json")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, spec.statusCode());
        assertTrue(Support.map(Support.object(spec.body()).get("paths")).containsKey("/agent/start"));
        assertEquals(401, post("/agent/start", null, "{}").statusCode());
    }
    private int raw(String headers, String body) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000); socket.getOutputStream().write(("POST /agent/start HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: close\r\nContent-Type: application/json\r\n" + headers + "\r\n" + body).getBytes(StandardCharsets.US_ASCII));
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertNotNull(line); return Integer.parseInt(line.split(" ")[1]);
        }
    }
    @Test
    void duplicateCredentialsAndOversizedBodyFailBeforeDispatch() throws Exception {
        assertEquals(400, raw("Authorization: Bearer isolated-boundary-token\r\nAuthorization: Bearer wrong\r\nContent-Length: 2\r\n", "{}"));
        assertEquals(413, raw("Authorization: Bearer isolated-boundary-token\r\nContent-Length: 1048577\r\n", ""));
        String chunk = "x".repeat(1048577);
        assertEquals(413, raw("Authorization: Bearer isolated-boundary-token\r\nTransfer-Encoding: chunked\r\n", Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n0\r\n\r\n"));
    }
}
