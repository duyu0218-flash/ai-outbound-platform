package com.duozhi.outbound;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpBoundaryTest {
    MockHttpServletRequest request(String host){MockHttpServletRequest r=new MockHttpServletRequest("GET","/api/v1/runtime");r.setServerName(host);return r;}
    @Test void untrustedHostsAreRejectedAndRequestIdsAreSanitized() throws Exception {
        HttpBoundary filter=new HttpBoundary(new Config(new MockEnvironment().withProperty("TRUSTED_HOSTS","example.test,*.internal.test")));
        MockHttpServletRequest bad=request("evil-example.test");bad.addHeader("X-Request-ID","bad\r\nheader");MockHttpServletResponse response=new MockHttpServletResponse();
        filter.doFilter(bad,response,(r,s)->fail("must reject before controller"));assertEquals(400,response.getStatus());assertFalse(response.getHeader("X-Request-ID").contains("\r"));
        MockHttpServletResponse good=new MockHttpServletResponse();filter.doFilter(request("api.internal.test"),good,(r,s)->s.getWriter().write("allowed"));assertEquals("allowed",good.getContentAsString());
    }
    @Test void corsPreflightIsAllowedOnlyForConfiguredOrigin() throws Exception {
        HttpBoundary filter=new HttpBoundary(new Config(new MockEnvironment().withProperty("CORS_ALLOW_ORIGINS","https://portal.test")));
        MockHttpServletRequest r=request("localhost");r.setMethod("OPTIONS");r.addHeader("Origin","https://portal.test");MockHttpServletResponse response=new MockHttpServletResponse();
        filter.doFilter(r,response,(a,b)->fail("preflight must not authenticate"));assertEquals("https://portal.test",response.getHeader("Access-Control-Allow-Origin"));
        MockHttpServletRequest other=request("localhost");other.addHeader("Origin","https://evil.test");MockHttpServletResponse denied=new MockHttpServletResponse();filter.doFilter(other,denied,(a,b)->{});assertNull(denied.getHeader("Access-Control-Allow-Origin"));
    }
    @Test void rateLimitDoesNotTrustForwardedAddresses() throws Exception {
        HttpBoundary filter=new HttpBoundary(new Config(new MockEnvironment().withProperty("RATE_LIMIT_BURST","1").withProperty("RATE_LIMIT_RPS","1")));
        filter.doFilter(request("localhost"),new MockHttpServletResponse(),(a,b)->{});
        MockHttpServletRequest next=request("localhost");next.addHeader("X-Forwarded-For","192.0.2.99");MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(next,response,(a,b)->fail("must be rate limited"));assertEquals(429,response.getStatus());assertEquals("1",response.getHeader("Retry-After"));
    }
    @Test void concurrencyAdmissionRecoversAfterControllerFailure() throws Exception {
        HttpBoundary filter=new HttpBoundary(new Config(new MockEnvironment().withProperty("API_MAX_CONCURRENT_REQUESTS","1")));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> first=pool.submit(()->{try{filter.doFilter(request("localhost"),new MockHttpServletResponse(),(a,b)->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new jakarta.servlet.ServletException("test failure");});}catch(Exception ignored){}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));MockHttpServletResponse busy=new MockHttpServletResponse();filter.doFilter(request("localhost"),busy,(a,b)->fail("must be bounded"));assertEquals(503,busy.getStatus());release.countDown();first.get(3,TimeUnit.SECONDS);
            MockHttpServletResponse after=new MockHttpServletResponse();filter.doFilter(request("localhost"),after,(a,b)->{});assertEquals(200,after.getStatus());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void releaseFlagCannotBypassProductionConfigurationChecks(){
        Config c=new Config(new MockEnvironment().withProperty("ENV","production").withProperty("JAVA_RELEASE_ACCEPTED","true"));
        IllegalStateException error=assertThrows(IllegalStateException.class,()->ProductionConfiguration.validate(c));assertTrue(error.getMessage().contains("PostgreSQL"));assertTrue(error.getMessage().contains("JWT_SECRET"));assertTrue(error.getMessage().contains("METRICS_TOKEN"));
        assertDoesNotThrow(()->ProductionConfiguration.validate(new Config(new MockEnvironment())));
    }
    @Test void productionConfigurationRequiresSeparateAcceptanceAndRejectsEmbeddedWildcards(){
        MockEnvironment env=new MockEnvironment().withProperty("ENV","production").withProperty("TRUSTED_HOSTS","portal.test,127.0.0.1")
            .withProperty("JAVA_DATABASE_URL","jdbc:postgresql://db/test").withProperty("REDIS_URL","rediss://:synthetic-redis-password-long@redis:6379/0")
            .withProperty("TELEPHONY_PROVIDER","http").withProperty("TELEPHONY_WEBHOOK_TOKEN","synthetic-token")
            .withProperty("OUTBOUND_ALLOWED_PHONE_PREFIXES","139").withProperty("RECORDING_RETENTION_DAYS","0");
        for(String key:new String[]{"SECRET_KEY","JWT_SECRET","TELEPHONY_WEBHOOK_SECRET","VOICE_COMMAND_SECRET","OUTBOUND_SECURITY_APPROVAL_TOKEN","TELEPHONY_SERVICE_TOKEN","AI_AGENT_SERVICE_TOKEN","API_KEY","METRICS_TOKEN"})env.withProperty(key,"synthetic-acceptance-only-secret-"+key);
        assertTrue(assertThrows(IllegalStateException.class,()->ProductionConfiguration.validate(new Config(env))).getMessage().contains("JAVA_RELEASE_ACCEPTED"));
        env.withProperty("JAVA_RELEASE_ACCEPTED","true");assertDoesNotThrow(()->ProductionConfiguration.validate(new Config(env)));
        env.withProperty("TRUSTED_HOSTS","portal.test, * ");assertThrows(IllegalStateException.class,()->ProductionConfiguration.validate(new Config(env)));
    }
}
