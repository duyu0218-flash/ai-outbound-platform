package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LogbackServiceProvider;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import com.alibaba.druid.pool.DruidDataSource;
import com.github.pagehelper.PageInterceptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StackCompatibilityIT {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add(
                "JWT_SECRET",
                () -> "test-auth-compatibility-secret-at-least-thirty-two-characters");
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.flyway.url", MYSQL::getJdbcUrl);
        properties.add("spring.flyway.user", () -> "root");
        properties.add("spring.flyway.password", MYSQL::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.ai.model.chat", () -> "openai");
        properties.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:9");
        properties.add("spring.ai.openai.api-key", () -> "compatibility-test-not-a-real-key");
        properties.add("spring.ai.openai.chat.options.model", () -> "compatibility-probe");
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    DataSource dataSource;
    @Autowired
    SqlSessionFactory sqlSessionFactory;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ApplicationContext context;
    @Autowired
    ChatClient.Builder chatClient;
    @Autowired
    Flyway flyway;
    @Autowired
    DefaultWebSecurityManager securityManager;

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
                var statement =
                        session.getConnection()
                                .prepareStatement("SELECT VERSION(), @@collation_server");
                var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).startsWith("8.4.");
            assertThat(result.getString(2)).isEqualTo("utf8mb4_0900_ai_ci");
        }
        assertThat(flyway).isNotNull();
        assertThat(chatClient.build()).isNotNull();
        assertThat(securityManager.getRememberMeManager()).isNull();
        assertThat(context.getBeansOfType(OpenApiWebMvcResource.class)).isNotEmpty();
    }

    @Test
    void redisRoundTrip() {
        String key = "compatibility:" + UUID.randomUUID();
        redis.opsForValue().set(key, "ok", Duration.ofSeconds(30));
        assertThat(redis.opsForValue().get(key)).isEqualTo("ok");
        redis.delete(key);
    }

    @Test
    void voiceLedgerUsesIndependentDurablePool() throws Exception {
        var pool = context.getBean("voiceLedgerDataSource", DataSource.class);
        assertThat(pool).isNotSameAs(dataSource);
        try (var c = pool.getConnection();
                var q = c.createStatement();
                var r =
                        q.executeQuery(
                                "SELECT COUNT(*) FROM voice_flags WHERE `key` IN"
                                        + " ('__voice_writer__','__model_writer__')")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void suppliedLogbackConfigurationStartsConsoleAndRollingFileAppenders() throws Exception {
        var providers = ServiceLoader.load(SLF4JServiceProvider.class).stream().toList();
        assertThat(providers).hasSize(1);
        assertThat(providers.getFirst().type()).isEqualTo(LogbackServiceProvider.class);
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var app = context.getLogger("com.duozhi.outbound");
        assertThat(app.isAdditive()).isFalse();
        assertThat(app.getAppender("console").isStarted()).isTrue();
        var file = (RollingFileAppender<?>) app.getAppender("logFile");
        assertThat(file.isStarted()).isTrue();
        var policy = (SizeAndTimeBasedRollingPolicy<?>) file.getRollingPolicy();
        assertThat(policy.getMaxHistory()).isEqualTo(7);
        app.info("logging configuration smoke probe");
        assertThat(Files.readString(Path.of(file.getFile())))
                .contains("logging configuration smoke probe");
    }
}
