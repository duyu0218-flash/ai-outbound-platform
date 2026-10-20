package com.duozhi.outbound.embedded;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class EmbeddedAgentTest {
    @TempDir Path directory;
    HttpServer upstream;String base;final AtomicInteger requests=new AtomicInteger();final AtomicReference<Map<String,Object>> received=new AtomicReference<>();final AtomicReference<String> authorization=new AtomicReference<>();int status=200;String response;
    @BeforeEach void start()throws Exception{
        response="{\"id\":\"chat-test\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"original-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"  好的，已为您记录。  \"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5,\"total_tokens\":16}}";
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.createContext("/gateway/v1/chat/completions",exchange->{
            requests.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));received.set(Support.object(exchange.getRequestBody().readAllBytes()));exchange.getResponseHeaders().set("Content-Type","application/json");if(status==429||status==503)exchange.getResponseHeaders().set("Retry-After","10");byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();base="http://127.0.0.1:"+upstream.getAddress().getPort()+"/gateway/v1";
    }
    @AfterEach void stop(){upstream.stop(0);}
    Map<String,String> configuration(){return LedgerTestDatabase.settings(Map.of("OPENAI_BASE_URL",base,"OPENAI_API_KEY","synthetic-provider-key","LLM_PROVIDER","openai-compatible","LANGFUSE_ENABLED","false"));}
    Map<String,Object> payload(){return Map.of("call_id",UUID.randomUUID().toString(),"phone","13100001111","mode","ai_only","script","approved script","transcript","请介绍服务价格","context",Map.of("llm_provider","openai-compatible","llm_model","original-model","external_llm_enabled",true,"conversation",List.of(Map.of("role","assistant","content","previous reply"),Map.of("role","user","content","请介绍服务价格")),"knowledge",List.of(Map.of("title","Approved FAQ","content","价格以确认信息为准"))));}
    @Test void springAiPreservesMessagesModelTemperatureTokensAndTrimmedReply()throws Exception{
        var agent=new EmbeddedAgent(new Settings(configuration()));try{
            var input=payload();var context=Support.map(input.get("context"));var expected=agent.messages(input,context);var result=agent.turn(input,null);
            assertThat(result.get("action")).isEqualTo("speak");assertThat(result.get("tts_text")).isEqualTo("好的，已为您记录。");assertThat(result.get("handoff_to_human")).isEqualTo(false);
            assertThat(requests.get()).isEqualTo(1);assertThat(authorization.get()).isEqualTo("Bearer synthetic-provider-key");assertThat(received.get().get("model")).isEqualTo("original-model");assertThat(((Number)received.get().get("max_tokens")).intValue()).isEqualTo(800);assertThat(((Number)received.get().get("temperature")).doubleValue()).isEqualTo(.3);assertThat(received.get().get("messages")).isEqualTo(expected);
        }finally{agent.close();}
    }
    @Test void ruleAndHandoffDoNotCallExternalProvider()throws Exception{
        var agent=new EmbeddedAgent(new Settings(configuration()));try{
            var input=new LinkedHashMap<>(payload());input.put("mode","ai_handoff");input.put("transcript","请转人工");var result=agent.turn(input,null);assertThat(result.get("action")).isEqualTo("handoff");assertThat(result.get("handoff_to_human")).isEqualTo(true);assertThat(requests.get()).isZero();
            input.put("mode","ai_only");input.put("transcript","一般问题");input.put("context",Map.of("llm_provider","rule"));assertThat(agent.turn(input,null).get("tts_text")).isEqualTo("我已收到您刚才的内容，马上为您处理。");assertThat(requests.get()).isZero();
        }finally{agent.close();}
    }
    @Test void externalProviderStillRequiresExplicitTenantApproval()throws Exception{
        var agent=new EmbeddedAgent(new Settings(configuration()));try{var input=new LinkedHashMap<>(payload());input.put("context",Map.of("llm_provider","openai-compatible","external_llm_enabled",false));assertThatThrownBy(()->agent.turn(input,null)).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(409));assertThat(requests.get()).isZero();}finally{agent.close();}
    }
    @Test void piiRedactionAndHistoryLimitMatchSourceMessages()throws Exception{
        var settings=configuration();settings.put("CONVERSATION_HISTORY_TURNS","1");settings.put("CONVERSATION_HISTORY_MAX_CHARS","1000");var agent=new EmbeddedAgent(new Settings(settings));try{
            var input=new LinkedHashMap<>(payload());input.put("script","email synthetic@example.com phone 13100001111 id 11010519491231002X");var result=agent.turn(input,null);assertThat(result.get("tts_text")).isEqualTo("好的，已为您记录。");String sent=new String(Support.json(received.get().get("messages")),StandardCharsets.UTF_8);assertThat(sent).contains("[REDACTED_EMAIL]","[REDACTED_PHONE]","[REDACTED_ID]").doesNotContain("synthetic@example.com","13100001111","11010519491231002X","previous reply");
        }finally{agent.close();}
    }
    @Test void upstream429IsNotRetriedAndRetainedQuotaCooldownBlocksNextCall()throws Exception{
        status=429;response="{\"error\":{\"message\":\"synthetic rate limit\",\"type\":\"rate_limit\"}}";var settings=configuration();settings.put("LLM_QUOTA_ENABLED","true");var agent=new EmbeddedAgent(new Settings(settings));try{
            assertThatThrownBy(()->agent.turn(payload(),null)).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(502));assertThat(requests.get()).isEqualTo(1);
            assertThatThrownBy(()->agent.turn(payload(),null)).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(429));assertThat(requests.get()).isEqualTo(1);assertThatThrownBy(()->agent.ready(null)).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(503));
        }finally{agent.close();}
    }
    @Test void malformedOrEmptyUpstreamMessagesRemainFailures()throws Exception{
        var agent=new EmbeddedAgent(new Settings(configuration()));try{
            response="{\"id\":\"bad\",\"choices\":[]}";assertThatThrownBy(()->agent.turn(payload(),null)).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(502));
            response="{\"id\":\"empty\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\" \"},\"finish_reason\":\"stop\"}]}";assertThatThrownBy(()->agent.turn(payload(),null)).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(502));
        }finally{agent.close();}
    }
    @Test void serviceTokenStillRejectsMissingAndIncorrectCredentials()throws Exception{
        var settings=configuration();settings.put("SERVICE_TOKEN","synthetic-agent-token");var agent=new EmbeddedAgent(new Settings(settings));try{assertThatThrownBy(()->agent.turn(payload(),null)).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(401));assertThatThrownBy(()->agent.turn(payload(),"Bearer incorrect")).isInstanceOfSatisfying(Support.Failure.class,e->assertThat(e.status).isEqualTo(401));assertThat(agent.turn(payload(),"Bearer synthetic-agent-token").get("action")).isEqualTo("speak");}finally{agent.close();}
    }
}
