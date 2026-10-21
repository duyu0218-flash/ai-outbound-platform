package com.duozhi.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL persistence checks against declarations copied from the original contracts. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DataLayerIT {
    private static final JsonNode BASELINE = baseline();
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 19, 8, 9, 10, 123_000_000);
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "test-auth-compatibility-secret-at-least-thirty-two-characters");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", ()->"root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);

        p.add("spring.ai.model.chat", () -> "none");
    }

    @Autowired SqlSessionFactory sessions;
    @Autowired PlatformTransactionManager transactions;

    static JsonNode baseline() {
        try (var input = DataLayerIT.class.getResourceAsStream("/contracts/data-layer-baseline.json")) {
            return new ObjectMapper().readTree(input);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static Stream<String> tables() {
        var names = new ArrayList<String>();
        BASELINE.fieldNames().forEachRemaining(names::add);
        return names.stream();
    }
    static Stream<String> uniqueTables() {
        return tables().filter(t -> !BASELINE.get(t).get("uniques").isEmpty());
    }
    static Stream<String> foreignTables() {
        return tables().filter(t -> !BASELINE.get(t).get("parents").isEmpty());
    }

    @ParameterizedTest(name = "{0}: CRUD, all fields, nulls and tenant ownership")
    @MethodSource("tables")
    void roundTrip(String table) throws Exception {
        try (var s = sessions.openSession(false)) {
            var f = new Fixture(s);
            Object row = f.create(table);
            Object id = get(row, "id");
            assertThat(id).isNotNull();
            Object read = f.call(table, "findById", row, id);
            assertFields(table, row, read);
            assertJdbc(s, table, row);

            String changed = columns(table).stream()
                    .filter(c -> !Set.of("id", "tenant_id", "created_at").contains(c))
                    .filter(c -> !meta(table).get("parents").has(c)).findFirst().orElseThrow();
            set(row, changed, different(get(row, changed)));
            assertThat(f.call(table, "updateById", row, row)).isEqualTo(1);
            assertFields(table, row, f.call(table, "findById", row, id));
            assertJdbc(s, table, row);

            List<?> listed = (List<?>) f.callList(table, row, 0, 100);
            assertThat(listed.stream().anyMatch(r -> Objects.equals(getUnchecked(r, "id"), id))).isTrue();
            assertThat((List<?>) f.callList(table, row, 0, 0)).isEmpty();
            assertThat((List<?>) f.callList(table, row, 1, 100)).isEmpty();
            if (meta(table).get("scoped").asBoolean()) {
                Integer owner = f.tenant(table, row);
                assertThat(f.raw(table, "findById", id, owner + 100000)).isNull();
                assertThat(f.raw(table, "updateById", row, owner + 100000)).isEqualTo(0);
                assertThat(f.raw(table, "deleteById", id, owner + 100000)).isEqualTo(0);
                assertThat((List<?>) f.raw(table, "list", owner + 100000, 0, 100)).isEmpty();
            }
            for (String col : columns(table)) {
                if (meta(table).get("columns").get(col).get("nullable").asBoolean()) set(row, col, null);
            }
            assertThat(f.call(table, "updateById", row, row)).isEqualTo(1);
            assertFields(table, row, f.call(table, "findById", row, id));
            assertJdbc(s, table, row);
            assertThat(f.call(table, "deleteById", row, id)).isEqualTo(1);
            assertThat(f.call(table, "findById", row, id)).isNull();
            s.rollback();
        }
    }

    @ParameterizedTest(name = "{0}: source application defaults")
    @MethodSource("tables")
    void sourceDefaults(String table) throws Exception {
        Class<?> type = Class.forName("com.duozhi.outbound.entity." + meta(table).get("entity").asText());
        Object row = type.getConstructor().newInstance();
        for (String col : columns(table)) {
            JsonNode info = meta(table).get("columns").get(col);
            Object value = get(row, col);
            if (info.has("default")) {
                JsonNode expected = info.get("default");
                if (value instanceof Enum<?> e) assertThat(e.name().toLowerCase(Locale.ROOT)).isEqualTo(expected.asText());
                else if (value instanceof Number n) assertThat(n.doubleValue()).isEqualTo(expected.asDouble());
                else if (value instanceof Boolean) assertThat(value).isEqualTo(expected.asBoolean());
                else assertThat(value).isEqualTo(expected.asText());
            } else if (info.path("factory").asText().equals("uuid")) assertThat(value).isInstanceOf(UUID.class);
            else if (info.path("factory").asText().equals("now")) {
                assertThat((LocalDateTime) value).isBetween(LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(5), LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(1));
            } else assertThat(value).isNull();
            if (info.has("enum")) {
                Class<?> enumType = type.getMethod("get" + suffix(col)).getReturnType();
                var creator = enumType.getMethod("fromValue", String.class);
                for (Object constant : enumType.getEnumConstants()) {
                    String name = ((Enum<?>) constant).name();
                    assertThat(creator.invoke(null, name)).isEqualTo(constant);
                    assertThat(creator.invoke(null, info.get("enum").get(name).asText())).isEqualTo(constant);
                }
                assertThatThrownBy(() -> creator.invoke(null, "cOnSeNtEd")).isInstanceOf(InvocationTargetException.class);
            }
        }
    }

    @ParameterizedTest(name = "{0}: unique keys reject duplicates")
    @MethodSource("uniqueTables")
    void uniqueKeys(String table) throws Exception {
        try (var s = sessions.openSession(false)) {
            var f = new Fixture(s);
            Object row = f.create(table);
            for (JsonNode key : meta(table).get("uniques")) {
                Object copy = f.fresh(table);
                for (JsonNode col : key) set(copy, col.asText(), get(row, col.asText()));
                assertSqlError(() -> f.raw(table, "insert", copy), 1062);
            }
            s.rollback();
        }
    }

    @ParameterizedTest(name = "{0}: foreign keys reject absent parents")
    @MethodSource("foreignTables")
    void foreignKeys(String table) throws Exception {
        try (var s = sessions.openSession(false)) {
            var f = new Fixture(s);
            f.create(table);
            for (String col : iterable(meta(table).get("parents").fieldNames())) {
                Object copy = new Fixture(s).fresh(table);
                Object parent = get(copy, col);
                set(copy, col, parent instanceof UUID ? UUID.randomUUID() : 2_000_000_000);
                assertSqlError(() -> f.raw(table, "insert", copy), 1452);
            }
            s.rollback();
        }
    }

    @Test void sourceColumnsKeysAndNullabilityMatchMySql() throws Exception {
        try (var s = sessions.openSession(); var statement = s.getConnection().createStatement()) {
            var actualTables = new HashSet<String>();
            try (var rs = statement.executeQuery("SHOW TABLES")) {
                while (rs.next()) actualTables.add(rs.getString(1));
            }
            var expectedTables = new HashSet<String>();
            tables().forEach(t -> expectedTables.add(meta(t).get("table").asText()));
            assertThat(expectedTables).hasSize(34);
            expectedTables.addAll(Set.of("voice_attempts","voice_flags","voice_nonces","voice_outbox","voice_audit","voice_action_commands","voice_permits","voice_media_owners","voice_model_budgets","voice_model_usage"));
            expectedTables.addAll(Set.of("ops_team","ops_member","ops_permission","ops_lead","ops_history","ops_view","ops_config","ops_notice","ops_presence","ops_tag_link"));
            expectedTables.add("flyway_schema_history");
            expectedTables.addAll(Set.of("dial_admission_control","dial_drain_member"));
            expectedTables.addAll(Set.of("dial_migration_generation","dial_daily_counter","dial_fact_revision","dial_fact_head"));
            expectedTables.addAll(Set.of("dial_command_journal","voice_mock_schedule"));
            expectedTables.add("dial_async_batch");
            expectedTables.addAll(Set.of("capacity_commit_evidence","capacity_identity_evidence"));
            expectedTables.addAll(Set.of("dial_reservation","dial_phone_guard","dial_quota_counter"));
            expectedTables.addAll(Set.of("voice_dial_authorization","voice_dial_authorization_history"));
            expectedTables.add("voice_scope_counter");
            assertThat(expectedTables).hasSize(72);
            assertThat(actualTables).isEqualTo(expectedTables);
            int count = 0;
            for (String table : tables().toList()) {
                try (var query = s.getConnection().prepareStatement("SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, DATETIME_PRECISION, COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
                    query.setString(1, physical(table));
                    try (var rs = query.executeQuery()) {
                        for (String col : columns(table)) {
                            assertThat(rs.next()).as(table + "." + col).isTrue();
                            assertThat(rs.getString(1)).isEqualTo(col);
                            assertThat(rs.getString(3)).isEqualTo(meta(table).get("columns").get(col).get("nullable").asBoolean() ? "YES" : "NO");
                            String type = meta(table).get("sqlTypes").get(col).asText();
                            String dataType = type.replaceAll("\\(.*", "").toLowerCase(Locale.ROOT);
                            if (dataType.equals("integer")) dataType = "int";
                            if (dataType.equals("boolean")) dataType = "tinyint";
                            assertThat(rs.getString(2)).isEqualTo(dataType);
                            if (type.startsWith("CHAR(") || type.startsWith("VARCHAR(")) {
                                assertThat(rs.getInt(4)).isEqualTo(Integer.parseInt(type.replaceAll("\\D", "")));
                            }
                            if (type.equals("DATETIME(3)")) assertThat(rs.getInt(5)).isEqualTo(3);
                            if (dataType.equals("char") || dataType.equals("varchar") || dataType.equals("longtext")) {
                                assertThat(rs.getString(6)).isEqualTo("utf8mb4_0900_bin");
                            }
                            count++;
                        }
                        assertThat(rs.next()).isFalse();
                    }
                }
                var indexes = new HashMap<String, List<String>>();
                try (var rs = statement.executeQuery("SHOW INDEX FROM `" + physical(table) + "`")) {
                    while (rs.next()) if (!rs.getBoolean("Non_unique")) {
                        indexes.computeIfAbsent(rs.getString("Key_name"), k -> new ArrayList<>()).add(rs.getString("Column_name"));
                    }
                }
                assertThat(indexes.get("PRIMARY")).containsExactly("id");
                for (JsonNode key : meta(table).get("uniques")) {
                    var wanted = new ArrayList<String>(); key.forEach(c -> wanted.add(c.asText()));
                    assertThat(indexes.values()).contains(wanted);
                }
            }
            assertThat(count).isEqualTo(379);
        }
    }

    @Test void tenantPhoneAndCaseSensitiveIdentitySemantics() throws Exception {
        try (var s = sessions.openSession(false)) {
            var first = new Fixture(s); Object a = first.create("contact");
            var second = new Fixture(s); Object b = second.fresh("contact");
            set(b, "phone", get(a, "phone"));
            assertThat(second.raw("contact", "insert", b)).isEqualTo(1);
            Object upper = first.fresh("user"); set(upper, "username", "CaseSensitive");
            Object lower = first.fresh("user"); set(lower, "username", "casesensitive");
            assertThat(first.raw("user", "insert", upper)).isEqualTo(1);
            assertThat(first.raw("user", "insert", lower)).isEqualTo(1);
            s.rollback();
        }
    }

    @Test void textOrderingLongContentAndNullableIdempotencyKeys() throws Exception {
        try (var s = sessions.openSession(false)) {
            var f = new Fixture(s);
            Object task = f.create("taskoutbox");
            String payload = "{\"z\":1,\"a\":\"中文😀'; DROP TABLE contact; --\"}";
            set(task, "payload_json", payload);
            f.call("taskoutbox", "updateById", task, task);
            assertThat(get(f.call("taskoutbox", "findById", task, get(task, "id")), "payload_json")).isEqualTo(payload);
            Object call = f.create("callsession");
            set(call, "campaign_contact_key", null);
            f.call("callsession", "updateById", call, call);
            Object speech = f.create("speechturn");
            set(speech, "transcript", "中".repeat(100000));
            f.call("speechturn", "updateById", speech, speech);
            assertThat(get(f.call("speechturn", "findById", speech, get(speech, "id")), "transcript")).isEqualTo("中".repeat(100000));
            Object other = f.fresh("callsession"); set(other, "campaign_contact_key", null);
            assertThat(f.raw("callsession", "insert", other)).isEqualTo(1);
            set(speech, "transcript", "中".repeat(100001));
            assertSqlError(() -> f.call("speechturn", "updateById", speech, speech), 3819);
            try (var sql = s.getConnection().prepareStatement("UPDATE contact SET consent_state='consented' WHERE id=?")) {
                Object contact = f.create("contact"); sql.setObject(1, get(contact, "id"));
                assertSqlError(sql::executeUpdate, 3819);
            }
            s.rollback();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackDoesNotPublishRows() throws Exception {
        Object id = new TransactionTemplate(transactions).execute(status -> {
            try (var s = sessions.openSession(false)) {
                var f = new Fixture(s); Object row = f.create("tenant");
                status.setRollbackOnly(); return get(row, "id");
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        try (var s = sessions.openSession()) {
            assertThat(new Fixture(s).raw("tenant", "findById", id)).isNull();
        }
    }

    private static JsonNode meta(String table) { return BASELINE.get(table); }
    private static String physical(String table) { return meta(table).get("table").asText(); }
    private static List<String> columns(String table) {
        var list = new ArrayList<String>(); meta(table).get("columns").fieldNames().forEachRemaining(list::add); return list;
    }
    private static String suffix(String column) {
        return Arrays.stream(column.split("_")).map(p -> p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1)).reduce("", String::concat);
    }
    private static Object get(Object row, String col) throws Exception { return row.getClass().getMethod("get" + suffix(col)).invoke(row); }
    private static Object getUnchecked(Object row, String col) {
        try { return get(row, col); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void set(Object row, String col, Object value) throws Exception {
        var getter = row.getClass().getMethod("get" + suffix(col));
        row.getClass().getMethod("set" + suffix(col), getter.getReturnType()).invoke(row, value);
    }
    private static Object different(Object value) {
        if (value instanceof String) return "changed中文";
        if (value instanceof Integer i) return i + 1;
        if (value instanceof Long l) return l + 1;
        if (value instanceof Double d) return d + 0.5;
        if (value instanceof Boolean b) return !b;
        if (value instanceof LocalDateTime d) return d.plusSeconds(1);
        if (value instanceof UUID) return UUID.randomUUID();
        if (value instanceof Enum<?> e) return e.getDeclaringClass().getEnumConstants()[(e.ordinal() + 1) % e.getDeclaringClass().getEnumConstants().length];
        throw new IllegalArgumentException("Unsupported fixture value " + value);
    }
    private static void assertFields(String table, Object wanted, Object actual) throws Exception {
        assertThat(actual).isNotNull();
        for (String col : columns(table)) assertThat(get(actual, col)).as(table + "." + col).isEqualTo(get(wanted, col));
    }
    private static void assertJdbc(SqlSession s, String table, Object wanted) throws Exception {
        try (var stmt = s.getConnection().prepareStatement("SELECT * FROM `" + physical(table) + "` WHERE id=?")) {
            stmt.setObject(1, String.valueOf(get(wanted, "id")));
            try (var rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                for (String col : columns(table)) {
                    Object expected = get(wanted, col), actual;
                    if (expected instanceof UUID || expected instanceof Enum<?> || expected instanceof String) actual = rs.getString(col);
                    else if (expected instanceof LocalDateTime) actual = rs.getObject(col, LocalDateTime.class);
                    else if (expected instanceof Boolean) actual = rs.getBoolean(col);
                    else if (expected instanceof Integer) actual = rs.getInt(col);
                    else if (expected instanceof Long) actual = rs.getLong(col);
                    else if (expected instanceof Double) actual = rs.getDouble(col);
                    else actual = rs.getObject(col);
                    if (expected instanceof UUID) expected = expected.toString();
                    if (expected instanceof Enum<?> e) expected = e.name();
                    assertThat(actual).as("JDBC " + table + "." + col).isEqualTo(expected);
                }
            }
        }
    }
    @FunctionalInterface interface Action { Object run() throws Exception; }
    private static void assertSqlError(Action action, int code) {
        Throwable caught = catchThrowable(action::run);
        assertThat(caught).isNotNull();
        while (!(caught instanceof SQLException) && caught.getCause() != null) caught = caught.getCause();
        assertThat(caught).isInstanceOf(SQLException.class);
        assertThat(((SQLException) caught).getErrorCode()).isEqualTo(code);
    }
    private static Iterable<String> iterable(Iterator<String> values) { return () -> values; }

    private static final class Fixture {
        final SqlSession session;
        final Map<String, Object> parents = new HashMap<>();
        Fixture(SqlSession session) { this.session = session; }
        Object create(String table) throws Exception {
            if (parents.containsKey(table)) return parents.get(table);
            Object row = fresh(table);
            assertThat(raw(table, "insert", row)).isEqualTo(1);
            parents.put(table, row); return row;
        }
        Object fresh(String table) throws Exception {
            Class<?> type = Class.forName("com.duozhi.outbound.entity." + meta(table).get("entity").asText());
            Object row = type.getConstructor().newInstance();
            for (String col : columns(table)) {
                Class<?> fieldType = type.getMethod("get" + suffix(col)).getReturnType();
                Object value;
                if (col.equals("id") && (fieldType == Integer.class || fieldType == Long.class)) { set(row, col, null); continue; }
                if (meta(table).get("parents").has(col)) {
                    value = get(create(meta(table).get("parents").get(col).get("table").asText()), "id");
                } else if (fieldType == String.class) {
                    String sqlType = meta(table).get("sqlTypes").get(col).asText();
                    String token = UUID.randomUUID().toString().replace("-", "");
                    int max = sqlType.startsWith("VARCHAR(") ? Integer.parseInt(sqlType.replaceAll("\\D", "")) : 100;
                    value = token.substring(0, Math.min(max, token.length()));
                } else if (fieldType == UUID.class) value = UUID.randomUUID();
                else if (fieldType == LocalDateTime.class) value = TIME;
                else if (fieldType == Boolean.class) value = true;
                else if (fieldType == Integer.class) value = 7;
                else if (fieldType == Long.class) value = 7L;
                else if (fieldType == Double.class) value = 7.25d;
                else if (fieldType.isEnum()) value = fieldType.getEnumConstants()[0];
                else throw new IllegalArgumentException("Unknown fixture field " + fieldType);
                set(row, col, value);
            }
            return row;
        }
        Integer tenant(String table, Object row) throws Exception {
            if (meta(table).get("columns").has("tenant_id")) return (Integer) get(row, "tenant_id");
            return (Integer) get(parents.get(table.equals("campaigncontact") ? "campaign" : "callsession"), "tenant_id");
        }
        Object call(String table, String method, Object row, Object arg) throws Exception {
            return meta(table).get("scoped").asBoolean() ? raw(table, method, arg, tenant(table, row)) : raw(table, method, arg);
        }
        Object callList(String table, Object row, int offset, int limit) throws Exception {
            return meta(table).get("scoped").asBoolean() ? raw(table, "list", tenant(table, row), offset, limit) : raw(table, "list", offset, limit);
        }
        Object raw(String table, String method, Object... args) throws Exception {
            Class<?> type = Class.forName("com.duozhi.outbound.mapper." + meta(table).get("entity").asText() + "Mapper");
            Object mapper = session.getMapper(type);
            var target = Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(method)).findFirst().orElseThrow();
            try { return target.invoke(mapper, args); }
            catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
        }
    }
}
