package com.duozhi.outbound;

import com.alibaba.druid.pool.DruidDataSource;
import com.github.pagehelper.PageInterceptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StackCompatibilityIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("JWT_SECRET", () -> "test-auth-compatibility-secret-at-least-thirty-two-characters");
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.flyway.url", MYSQL::getJdbcUrl);
        properties.add("spring.flyway.user", MYSQL::getUsername);
        properties.add("spring.flyway.password", MYSQL::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.ai.model.chat", () -> "openai");
        properties.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:9");
        properties.add("spring.ai.openai.api-key", () -> "compatibility-test-not-a-real-key");
        properties.add("spring.ai.openai.chat.options.model", () -> "compatibility-probe");
    }

    @Autowired TestRestTemplate http;
    @Autowired DataSource dataSource;
    @Autowired SqlSessionFactory sqlSessionFactory;
    @Autowired StringRedisTemplate redis;
    @Autowired ApplicationContext context;
    @Autowired ChatClient.Builder chatClient;
    @Autowired Flyway flyway;
    @Autowired DefaultWebSecurityManager securityManager;

    @Test
    void realHttpLivenessAndProtectedInfrastructure() {
        var health = http.getForEntity("/healthz", String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(health.getHeaders().get("Set-Cookie")).isNull();
        for (String path : List.of("/actuator/prometheus", "/druid/index.html", "/not-a-route")) {
            var response = http.getForEntity(path, String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getHeaders().get("Set-Cookie")).isNull();
        }
    }

    @Test
    void mysqlDruidMybatisPageHelperFlywayAndAiCoexist() throws Exception {
        assertThat(dataSource).isInstanceOf(DruidDataSource.class);
        assertThat(sqlSessionFactory.getConfiguration().getInterceptors())
                .anyMatch(PageInterceptor.class::isInstance);
        try (var session = sqlSessionFactory.openSession();
             var statement = session.getConnection().prepareStatement("SELECT VERSION(), @@collation_server");
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).startsWith("8.4.");
            assertThat(result.getString(2)).isEqualTo("utf8mb4_0900_ai_ci");
        }
        assertThat(flyway).isNotNull();
        assertThat(chatClient.build()).isNotNull();
        assertThat(securityManager.getRememberMeManager()).isNull();
        assertThat(context.getBeansOfType(org.springdoc.webmvc.api.OpenApiWebMvcResource.class)).isNotEmpty();
    }

    @Test
    void redisRoundTrip() {
        String key = "compatibility:" + UUID.randomUUID();
        redis.opsForValue().set(key, "ok", Duration.ofSeconds(30));
        assertThat(redis.opsForValue().get(key)).isEqualTo("ok");
        redis.delete(key);
    }

    @Test void voiceLedgerUsesIndependentDurablePool()throws Exception{
        var pool=context.getBean("voiceLedgerDataSource",javax.sql.DataSource.class);assertThat(pool).isNotSameAs(dataSource);
        try(var c=pool.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM voice_flags WHERE `key` IN ('__voice_writer__','__model_writer__')")){assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(2);}
    }
}
