package com.duozhi.outbound.common.dao;

import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

/**
 * Source record semantics backed by fixed, per-table MyBatis statements. No SQL is accepted here.
 */
@Repository
public class Store {
    private final SqlSession sql;
    private final javax.sql.DataSource dataSource;
    private final Map<String, Map<String, Map<String, Object>>> tables;
    private final Map<String, String> mappers = new HashMap<>();

    private record BufferedWrite(
            String statement,
            Object parameters,
            boolean insert,
            Runnable after,
            long estimatedBytes) {}

    private static long insertBytes(Map<String, Object> values) {
        long bytes = 512L + 64L * values.size();
        for (Object value : values.values())
            if (value instanceof String text)
                bytes += 2L * text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            else if (value instanceof byte[] data) bytes += 2L * data.length;
            else bytes += 64;
        return bytes;
    }

    private final ThreadLocal<List<BufferedWrite>> buffered = new ThreadLocal<>();

    /**
     * Only used for locked admission rows; flushes before every subsequent read.
     * SpringManagedTransaction keeps the JDBC batch in the caller's transaction.
     */
    public <T> T bufferWrites(java.util.function.Supplier<T> work) {
        if (buffered.get() != null) return work.get();
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("buffered writes require transaction");
        buffered.set(new ArrayList<>());
        try {
            T result = work.get();
            flushBuffered();
            return result;
        } finally {
            buffered.remove();
        }
    }

    private void flushBuffered() {
        var pending = buffered.get();
        if (pending == null || pending.isEmpty()) return;
        var writes = new ArrayList<>(pending);
        pending.clear();
        var groups = new LinkedHashMap<String, List<BufferedWrite>>();
        for (var op : writes) {
            String shape = op.statement();
            if (op.parameters() instanceof Map<?, ?> m
                    && m.get("changes") instanceof Map<?, ?> changes)
                shape += ":" + m.get("tenantId") + ":" + changes.keySet();
            groups.computeIfAbsent(shape, k -> new ArrayList<>()).add(op);
        }
        var factory = ((org.mybatis.spring.SqlSessionTemplate) sql).getSqlSessionFactory();
        for (var iterator = groups.values().iterator(); iterator.hasNext(); ) {
            var group = iterator.next();
            var first = group.get(0);
            String combined = first.statement().replaceAll("\\.patchFields$", ".patchBatch");
            if (!first.insert()
                    && group.size() > 1
                    && sql.getConfiguration().hasStatement(combined)) {
                for (int start = 0; start < group.size(); ) {
                    int end = start;
                    long bytes = 0;
                    while (end < group.size()
                            && end - start < 100
                            && (end == start
                                    || bytes + group.get(end).estimatedBytes() <= 512 * 1024)) {
                        bytes += group.get(end).estimatedBytes();
                        end++;
                    }
                    writeCombined(combined, group.subList(start, end));
                    start = end;
                }
                iterator.remove();
            }
        }
        try (var batch = factory.openSession(org.apache.ibatis.session.ExecutorType.BATCH)) {
            long bytes = 0;
            for (var group : groups.values())
                for (var op : group) {
                    // Connector/J rewrites inserts into one packet. Keep cohorts below
                    // even the test container's 1 MiB limit; all chunks still share
                    // the caller's transaction and generated-key/rollback semantics.
                    if (bytes > 0 && bytes + op.estimatedBytes() > 512 * 1024) {
                        flushJdbcBatch(batch);
                        bytes = 0;
                    }
                    if (op.insert()) batch.insert(op.statement(), op.parameters());
                    else batch.update(op.statement(), op.parameters());
                    bytes += op.estimatedBytes();
                }
            flushJdbcBatch(batch);
        }
        sql.clearCache();
        for (var op : writes) if (op.after() != null) op.after().run();
    }

    private void writeCombined(String statement, List<BufferedWrite> group) {
        var params = (Map<?, ?>) group.get(0).parameters();
        var changes = (Map<?, ?>) params.get("changes");
        var constants = new HashSet<String>();
        for (var field : changes.keySet())
            if (group.stream()
                    .allMatch(
                            op ->
                                    Objects.equals(
                                            changes.get(field),
                                            ((Map<?, ?>)
                                                            ((Map<?, ?>) op.parameters())
                                                                    .get("changes"))
                                                    .get(field)))) constants.add(field.toString());
        int changed =
                sql.update(
                        statement,
                        Map.of(
                                "tenantId",
                                params.get("tenantId"),
                                "rows",
                                group.stream().map(BufferedWrite::parameters).toList(),
                                "constants",
                                constants));
        if (changed != group.size())
            throw new IllegalStateException("locked batch row was not written");
    }

    private static void flushJdbcBatch(org.apache.ibatis.session.SqlSession batch) {
        for (var result : batch.flushStatements())
            for (int count : result.getUpdateCounts())
                if (count == java.sql.Statement.EXECUTE_FAILED || count == 0)
                    throw new IllegalStateException("buffered locked row was not written");
    }

    public Store(SqlSession sql, ObjectMapper json, javax.sql.DataSource dataSource)
            throws IOException {
        this.dataSource = dataSource;
        this.sql = sql;
        try (var in = new ClassPathResource("contracts/tables.json").getInputStream()) {
            tables =
                    json.readValue(
                            in,
                            new TypeReference<Map<String, Map<String, Map<String, Object>>>>() {});
        }
        for (String name : tables.keySet()) {
            String entity =
                    Map.ofEntries(
                                    Map.entry("user", "SysUser"),
                                    Map.entry("scripttemplate", "ScriptTemplate"),
                                    Map.entry("scriptflowversion", "ScriptFlowVersion"),
                                    Map.entry("knowledgeitem", "KnowledgeItem"),
                                    Map.entry("phonesuppression", "PhoneSuppression"),
                                    Map.entry("callsession", "CallSession"),
                                    Map.entry("campaigncontact", "CampaignContact"),
                                    Map.entry("callevent", "CallEvent"),
                                    Map.entry("speechturn", "SpeechTurn"),
                                    Map.entry("conversationstate", "ConversationState"),
                                    Map.entry("taskoutbox", "TaskOutbox"),
                                    Map.entry("taskreceipt", "TaskReceipt"),
                                    Map.entry("callusage", "CallUsage"),
                                    Map.entry("realtimesession", "RealtimeSession"),
                                    Map.entry("callanalysis", "CallAnalysis"),
                                    Map.entry("adminsetting", "AdminSetting"),
                                    Map.entry("auditlog", "AuditLog"),
                                    Map.entry("contactimportjob", "ContactImportJob"),
                                    Map.entry("gatewaynode", "GatewayNode"),
                                    Map.entry("telephonyline", "TelephonyLine"),
                                    Map.entry("callbackinbox", "CallbackInbox"),
                                    Map.entry("callbackinboxpartition", "CallbackInboxPartition"),
                                    Map.entry("callbackinboxworker", "CallbackInboxWorker"),
                                    Map.entry("webhookeventingest", "WebhookEventIngest"),
                                    Map.entry("handoffrequest", "HandoffRequest"),
                                    Map.entry("recordingasset", "RecordingAsset"),
                                    Map.entry("callmetric", "CallMetric"),
                                    Map.entry("smslog", "SmsLog"),
                                    Map.entry("scenarioversion", "ScenarioVersion"),
                                    Map.entry("productworkitem", "ProductWorkItem"),
                                    Map.entry("callbackappointment", "CallbackAppointment"))
                            .getOrDefault(
                                    name,
                                    Character.toUpperCase(name.charAt(0)) + name.substring(1));
            mappers.put(name, MapperRegistry.namespace(entity + "Mapper"));
        }
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private String namespace(String table) {
        String value = mappers.get(table);
        if (value == null) throw new IllegalArgumentException("unknown table");
        return value;
    }

    public Object encode(String table, String column, Object value) {
        var info = tables.get(table).get(column);
        if (info == null) throw new IllegalArgumentException("unknown column");
        if (value == null) return null;
        if (info.containsKey("enum")) {
            @SuppressWarnings("unchecked")
            var values = (Map<String, String>) info.get("enum");
            String candidate = value.toString();
            return values.entrySet().stream()
                    .filter(e -> e.getKey().equals(candidate) || e.getValue().equals(candidate))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new ApiError(422, "invalid " + column));
        }
        String type = info.get("type").toString();
        if (type.equals("UUID") || type.equals("CHAR(32)"))
            return value instanceof UUID ? value : UUID.fromString(value.toString());
        if (type.startsWith("DATETIME") || type.startsWith("TIMESTAMP")) {
            if (value instanceof String text) {
                text = text.replace(' ', 'T');
                try {
                    return LocalDateTime.parse(text);
                } catch (java.time.format.DateTimeParseException e) {
                    try {
                        return OffsetDateTime.parse(text)
                                .withOffsetSameInstant(ZoneOffset.UTC)
                                .toLocalDateTime();
                    } catch (java.time.format.DateTimeParseException invalid) {
                        throw new ApiError(422, "invalid " + column);
                    }
                }
            }
        }
        return value;
    }

    public Map<String, Object> defaults(String table) {
        namespace(table);
        var result = new LinkedHashMap<String, Object>();
        tables.get(table)
                .forEach(
                        (column, info) -> {
                            if (info.containsKey("default"))
                                result.put(column, info.get("default"));
                            else if ("now".equals(info.get("factory"))) result.put(column, now());
                            else if ("uuid".equals(info.get("factory")))
                                result.put(column, UUID.randomUUID());
                        });
        return result;
    }

    public Map<String, Object> record(String table, Object entity) {
        if (entity == null) return null;
        var result = new LinkedHashMap<String, Object>();
        var bean = new BeanWrapperImpl(entity);
        tables.get(table)
                .forEach(
                        (column, info) -> {
                            Object value =
                                    entity instanceof Map
                                            ? ((Map<?, ?>) entity).get(column)
                                            : bean.getPropertyValue(property(column));
                            if (value instanceof Enum<?>)
                                value = value.toString().toLowerCase(Locale.ROOT);
                            else if (value instanceof UUID) value = value.toString();
                            else if (value instanceof java.sql.Timestamp t)
                                value = t.toLocalDateTime().toString();
                            else if (value instanceof LocalDateTime t) value = t.toString();
                            result.put(column, value);
                        });
        return result;
    }

    private String property(String column) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    public Map<String, Object> insert(String table, Map<String, Object> input) {
        return insert(table, input, true);
    }

    /**
     * Append-only metrics do not need a read-back; generated keys and defaults are still written.
     */
    public void appendMetric(Map<String, Object> input) {
        insert("callmetric", input, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> insert(String table, Map<String, Object> input, boolean reload) {
        var values = defaults(table);
        values.putAll(input);
        Object entity;
        try {
            entity =
                    Class.forName(
                                    namespace(table)
                                            .replace(".dao.", ".domain.")
                                            .replaceAll("Mapper$", ""))
                            .getConstructor()
                            .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        var bean = new BeanWrapperImpl(entity);
        values.forEach(
                (column, raw) -> {
                    Object value = encode(table, column, raw);
                    Class<?> type = bean.getPropertyType(property(column));
                    if (value != null && type.isEnum())
                        value = Enum.valueOf((Class<? extends Enum>) type, value.toString());
                    bean.setPropertyValue(property(column), value);
                });
        String explicit = namespace(table) + ".insertExplicitId",
                statement =
                        input.get("id") != null && sql.getConfiguration().hasStatement(explicit)
                                ? explicit
                                : namespace(table) + ".insert";
        if (buffered.get() != null
                && Set.of(
                                "callevent",
                                "conversationstate",
                                "webhookeventingest",
                                "callusage",
                                "taskoutbox",
                                "callbackinbox")
                        .contains(table)) {
            var result = record(table, entity);
            final Object saved = entity;
            buffered.get()
                    .add(
                            new BufferedWrite(
                                    statement,
                                    Map.of("row", entity),
                                    true,
                                    () -> result.putAll(record(table, saved)),
                                    insertBytes(values)));
            return result;
        }
        flushBuffered();
        sql.insert(statement, Map.of("row", entity));
        long tenant = values.get("tenant_id") instanceof Number n ? n.longValue() : 0;
        if (!reload
                || Set.of(
                                "campaigncontact",
                                "callevent",
                                "webhookeventingest",
                                "taskoutbox",
                                "speechturn")
                        .contains(table)) return record(table, entity);
        return record(
                table,
                sql.selectOne(
                        namespace(table) + ".findById",
                        Map.of("id", bean.getPropertyValue("id"), "tenantId", tenant)));
    }

    public Map<String, Object> require(String table, long tenant, Object id) {
        flushBuffered();
        Object row =
                sql.selectOne(
                        namespace(table) + ".findById",
                        Map.of("id", encode(table, "id", id), "tenantId", tenant));
        if (row == null) throw new ApiError(404, table + " not found");
        return record(table, row);
    }

    public Map<String, Object> update(
            String table, long tenant, Object id, Map<String, Object> input) {
        Map<String, Object> previous = require(table, tenant, id);
        var changes = new LinkedHashMap<String, Object>();
        input.forEach(
                (column, value) -> {
                    if (!Set.of("id", "tenant_id", "created_at").contains(column))
                        changes.put(column, encode(table, column, value));
                });
        if (tables.get(table).containsKey("updated_at")) changes.put("updated_at", now());
        if (!changes.isEmpty())
            sql.update(
                    namespace(table) + ".patchFields",
                    Map.of("id", encode(table, "id", id), "tenantId", tenant, "changes", changes));
        return require(table, tenant, id);
    }

    /**
     * The caller already holds the call/platform lock and has loaded this row in the same
     * transaction. Keep the original patch SQL, tenant predicate and error semantics, without the
     * two read-backs whose results these callers discard.
     */
    public void writeLocked(String table, long tenant, Object id, Map<String, Object> input) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException("known-row write requires transaction");
        var changes = new LinkedHashMap<String, Object>();
        input.forEach(
                (column, value) -> {
                    if (!Set.of("id", "tenant_id", "created_at").contains(column))
                        changes.put(column, encode(table, column, value));
                });
        if (tables.get(table).containsKey("updated_at")) changes.put("updated_at", now());
        if (buffered.get() != null
                && Set.of("callsession", "taskoutbox", "callusage").contains(table)
                && !changes.isEmpty()) {
            String statement = namespace(table) + ".patchFields";
            Object encoded = encode(table, "id", id);
            if (buffered.get().stream()
                    .anyMatch(
                            op ->
                                    !op.insert()
                                            && op.statement().equals(statement)
                                            && op.parameters() instanceof Map<?, ?> previous
                                            && Objects.equals(previous.get("id"), encoded)
                                            && Objects.equals(previous.get("tenantId"), tenant)))
                flushBuffered();
            buffered.get()
                    .add(
                            new BufferedWrite(
                                    namespace(table) + ".patchFields",
                                    Map.of(
                                            "id",
                                            encode(table, "id", id),
                                            "tenantId",
                                            tenant,
                                            "changes",
                                            changes),
                                    false,
                                    null,
                                    insertBytes(changes)));
            return;
        }
        if (changes.isEmpty()
                || sql.update(
                                namespace(table) + ".patchFields",
                                Map.of(
                                        "id",
                                        encode(table, "id", id),
                                        "tenantId",
                                        tenant,
                                        "changes",
                                        changes))
                        == 0) require(table, tenant, id);
    }

    /** Patch a row already read under the caller's transaction lock. */
    public Map<String, Object> patchLoaded(
            String table, Map<String, Object> row, Map<String, Object> input) {
        writeLocked(table, CallLifecycle.tenant(row), row.get("id"), input);
        var result = new LinkedHashMap<>(row);
        input.forEach(
                (key, value) -> {
                    if (!Set.of("id", "tenant_id", "created_at").contains(key))
                        result.put(
                                key,
                                value instanceof LocalDateTime || value instanceof UUID
                                        ? value.toString()
                                        : value);
                });
        if (tables.get(table).containsKey("updated_at")) result.put("updated_at", now().toString());
        return result;
    }

    public void delete(String table, long tenant, Object id) {
        require(table, tenant, id);
        sql.delete(
                namespace(table) + ".deleteById",
                Map.of("id", encode(table, "id", id), "tenantId", tenant));
    }

    public List<Map<String, Object>> query(
            String table, String method, Map<String, Object> params) {
        flushBuffered();
        return sql.selectList(namespace(table) + "." + method, params).stream()
                .map(row -> record(table, row))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public Map<String, Object> one(String table, String method, Map<String, Object> params) {
        var rows = query(table, method, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int execute(String table, String method, Map<String, Object> params) {
        flushBuffered();
        return sql.update(namespace(table) + "." + method, params);
    }

    public long scalar(String table, String method, Map<String, Object> params) {
        flushBuffered();
        Number value = sql.selectOne(namespace(table) + "." + method, params);
        return value == null ? 0 : value.longValue();
    }

    public static Map<String, Object> queryParameters(int tenantPosition, Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index++) result.put("p" + index, values[index]);
        if (tenantPosition >= 0) result.put("tenantId", values[tenantPosition]);
        return result;
    }

    public String lock() {
        return " FOR UPDATE";
    }

    public Map<String, Object> locate(String table, Object id, boolean locked) {
        if (!tables.get(table).containsKey("tenant_id"))
            throw new IllegalArgumentException("only tenant business records can be located");
        for (var tenant : query("tenant", "allForMaintenance", Map.of())) {
            var result =
                    one(
                            table,
                            locked ? "lockById" : "findById",
                            Map.of("id", encode(table, "id", id), "tenantId", tenant.get("id")));
            if (result != null) return result;
        }
        return null;
    }

    public void updateInternal(String table, Object id, Map<String, Object> input) {
        if (!Set.of(
                        "tenant",
                        "gatewaynode",
                        "callbackinbox",
                        "callbackinboxpartition",
                        "callbackinboxworker")
                .contains(table))
            throw new IllegalArgumentException("explicit tenant update required");
        var changes = new LinkedHashMap<String, Object>();
        input.forEach((column, value) -> changes.put(column, encode(table, column, value)));
        if (!changes.isEmpty())
            execute(
                    table,
                    "patchFields",
                    Map.of("id", encode(table, "id", id), "tenantId", 0, "changes", changes));
    }

    public static Map<String, Object> scopedParameters(long tenant, Object... values) {
        var result = queryParameters(-1, values);
        result.put("tenantId", tenant);
        return result;
    }

    public List<Map<String, Object>> across(
            String table, String method, Map<String, Object> input) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var tenant : query("tenant", "allForMaintenance", Map.of())) {
            var parameters = new LinkedHashMap<>(input);
            parameters.put("tenantId", tenant.get("id"));
            rows.addAll(query(table, method, parameters));
        }
        return rows;
    }

    public Map<String, Object> firstAcross(String table, String method, Map<String, Object> input) {
        var rows = across(table, method, input);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long sumAcross(String table, String method, Map<String, Object> input) {
        long result = 0;
        for (var tenant : query("tenant", "allForMaintenance", Map.of())) {
            var parameters = new LinkedHashMap<>(input);
            parameters.put("tenantId", tenant.get("id"));
            result += scalar(table, method, parameters);
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> orderedAcross(
            String table, String method, Map<String, Object> input, List<String> order, int limit) {
        var rows = across(table, method, input);
        Comparator<Map<String, Object>> comparator = (left, right) -> 0;
        for (String item : order) {
            String[] part = item.split(":");
            String column = part[0];
            boolean desc = part.length > 1 && part[1].equals("DESC");
            if (!tables.get(table).containsKey(column))
                throw new IllegalArgumentException("unknown order column");
            Comparator<Map<String, Object>> next =
                    (left, right) -> {
                        Object a = left.get(column), b = right.get(column);
                        int comparison;
                        if (a == b) comparison = 0;
                        else if (a == null) comparison = 1;
                        else if (b == null) comparison = -1;
                        else comparison = ((Comparable) a).compareTo(b);
                        return desc ? -comparison : comparison;
                    };
            comparator = comparator.thenComparing(next);
        }
        rows.sort(comparator);
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    public Map<String, Object> firstAcrossOrdered(
            String table, String method, Map<String, Object> input, List<String> order) {
        var rows = orderedAcross(table, method, input, order, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public <T> T value(String table, String method, Map<String, Object> input, Class<T> type) {
        flushBuffered();
        return sql.selectOne(namespace(table) + "." + method, input);
    }

    public List<Map<String, Object>> projection(
            String table, String method, Map<String, Object> input) {
        flushBuffered();
        return sql.selectList(namespace(table) + "." + method, input);
    }

    public Map<String, Object> projectOne(String table, String method, Map<String, Object> input) {
        flushBuffered();
        return sql.selectOne(namespace(table) + "." + method, input);
    }

    public int ping() {
        try (var connection = dataSource.getConnection()) {
            if (!connection.isValid(3))
                throw new java.sql.SQLException("database validation failed");
            return 1;
        } catch (java.sql.SQLException e) {
            throw new org.springframework.dao.DataAccessResourceFailureException(
                    "database unavailable", e);
        }
    }

    public List<Map<String, Object>> groupedAcross(
            String table, String method, Map<String, Object> input) {
        var totals = new LinkedHashMap<Object, Long>();
        for (var tenant : query("tenant", "allForMaintenance", Map.of())) {
            var scoped = new LinkedHashMap<>(input);
            scoped.put("tenantId", tenant.get("id"));
            for (var row : projection(table, method, scoped))
                totals.merge(row.get("value"), ((Number) row.get("total")).longValue(), Long::sum);
        }
        var result = new ArrayList<Map<String, Object>>();
        totals.forEach(
                (value, total) -> result.add(JsonSupport.map("value", value, "total", total)));
        return result;
    }
}
