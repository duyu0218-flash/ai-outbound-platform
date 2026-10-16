package com.duozhi.outbound;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecurityRegressionTest {
    @Test void bootYamlRemainsCompatibleAndRejectsObjectConstruction() throws Exception {
        YamlPropertySourceLoader loader=new YamlPropertySourceLoader();
        assertEquals("working",loader.load("test",new ByteArrayResource("security:\n  marker: working\n".getBytes(StandardCharsets.UTF_8))).get(0).getProperty("security.marker"));
        assertThrows(RuntimeException.class,()->loader.load("unsafe",new ByteArrayResource("payload: !!javax.script.ScriptEngineManager []".getBytes(StandardCharsets.UTF_8))));
    }
    @Test void jacksonRejectsExcessiveNestingWithoutStackOverflow() {
        StringBuilder input=new StringBuilder();for(int i=0;i<1200;i++)input.append('[');input.append('0');for(int i=0;i<1200;i++)input.append(']');
        assertThrows(tools.jackson.core.exc.StreamConstraintsException.class,()->new ObjectMapper().readTree(input.toString()));
    }
    @Test void loggingUsesThePatchedLog4jBinding() {
        assertEquals("org.apache.logging.slf4j.Log4jLoggerFactory",org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName());
        assertThrows(ClassNotFoundException.class,()->Class.forName("ch.qos.logback.classic.LoggerContext"));
    }
    @Test void malformedOriginsCannotReachResponseHeadersEvenInDevelopment() throws Exception {
        HttpBoundary boundary=new HttpBoundary(new Config(new MockEnvironment().withProperty("CORS_ALLOW_ORIGINS","*")));
        for(String origin:Arrays.asList("https://ok.test\r\nX-Injected: yes","https://ok.test/path","https://user@ok.test","null","https://ok.test:99999")){
            MockHttpServletRequest request=new MockHttpServletRequest("OPTIONS","/api/v1/contacts");request.addHeader("Origin",origin);
            MockHttpServletResponse response=new MockHttpServletResponse();boundary.doFilter(request,response,(a,b)->{});
            assertNull(response.getHeader("Access-Control-Allow-Origin"),origin);assertNull(response.getHeader("X-Injected"));
        }
    }
    @Test void httpTransportRejectsFileSchemesAndEmbeddedCredentialsBeforeConnecting() {
        TelephonyClient client=new TelephonyClient(null,new Config(new MockEnvironment()),new JsonSupport(new ObjectMapper()));
        for(String endpoint:Arrays.asList("file:///etc/passwd","ftp://127.0.0.1/secret","http://user:pass@127.0.0.1/","http://127.0.0.1/#fragment"))
            assertThrows(TelephonyClient.Failure.class,()->client.get(endpoint,Collections.emptyMap(),50));
    }
    @Test void dynamicSqlIdentifiersAreAllowlistedAndValuesStayBound() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);Store store=new Store(jdbc,new ObjectMapper(),new Config(new MockEnvironment()));
        assertThrows(IllegalArgumentException.class,()->store.insert("contact; DROP TABLE contact",Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class,()->store.insert("contact",JsonSupport.map("name) VALUES ('x'); --","x")));
        verifyNoInteractions(jdbc);
        when(jdbc.queryForMap(anyString(),any(Object[].class))).thenReturn(Collections.emptyMap());
        String payload="x'); DROP TABLE contact; --";store.insert("contact",JsonSupport.map("tenant_id",1,"phone","13900000000","name",payload));
        ArgumentCaptor<String> query=ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForMap(query.capture(),any(Object[].class));assertFalse(query.getValue().contains(payload));
        Object[] bound=mockingDetails(jdbc).getInvocations().iterator().next().getArguments();assertTrue(Arrays.asList(bound).contains(payload));
    }
    @Test void directoryXmlEscapesUntrustedAttributeValues() throws Exception {
        Config config=new Config(new MockEnvironment().withProperty("FREESWITCH_DIRECTORY_TOKEN","synthetic-token"));
        JsonSupport json=new JsonSupport(new ObjectMapper());ExpiringValues cache=new ExpiringValues(config);
        String payload="\"/><injected value=\"owned\"/><!--&";
        cache.set("ai-outbound:sip-extension:test",json.json(JsonSupport.map("extension",payload,"password",payload)),60);
        AgentMedia media=new AgentMedia(null,config,null,json,cache,null);
        try{
            String xml=media.directory("test",payload,"synthetic-token");
            javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            org.w3c.dom.Document document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            assertEquals(0,document.getElementsByTagName("injected").getLength());assertEquals(payload,document.getElementsByTagName("domain").item(0).getAttributes().getNamedItem("name").getNodeValue());
        }finally{media.close();}
    }
}
