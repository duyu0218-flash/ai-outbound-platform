package com.duozhi.outbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/** Uses the existing SQLModel names, enum storage and transaction boundaries. */
@Repository
public class Store {
    private final JdbcTemplate jdbc;
    private final Map<String, Map<String, Map<String, Object>>> tables;
    private final boolean postgres;

    public Store(JdbcTemplate jdbc, ObjectMapper mapper, Config config) throws IOException {
        this.jdbc = jdbc;
        this.postgres = config.get("spring.datasource.url", "").startsWith("jdbc:postgresql:");
        try (java.io.InputStream in = new ClassPathResource("contracts/tables.json").getInputStream()) {
            tables = mapper.readValue(in, new TypeReference<Map<String,Map<String,Map<String,Object>>>>() {});
        }
    }

    public static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    public JdbcTemplate jdbc() { return jdbc; }
    public String lock() { return postgres ? " FOR UPDATE" : ""; }
    private String name(String table) {
        if (!tables.containsKey(table)) throw new IllegalArgumentException("unknown table");
        return '"' + table + '"';
    }
    private String column(String table, String column) {
        if (!tables.get(table).containsKey(column)) throw new IllegalArgumentException("unknown column");
        return '"' + column + '"';
    }
    public List<Map<String, Object>> list(String table, String where, Object... args) {
        return jdbc.queryForList("SELECT * FROM " + name(table) + " " + where, args)
                .stream().map(row -> decode(table, row)).collect(java.util.stream.Collectors.toList());
    }
    public Map<String, Object> one(String table, String where, Object... args) {
        java.util.List<java.util.Map<java.lang.String,java.lang.Object>> rows = list(table, where, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Map<String, Object> require(String table, long tenant, Object id) {
        java.util.Map<java.lang.String,java.lang.Object> row = one(table, "WHERE tenant_id=? AND id=?", tenant, encode(table, "id", id));
        if (row == null) throw new ApiError(404, table + " not found");
        return row;
    }
    public long count(String table, String where, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + name(table) + " " + where, Long.class, args));
    }
    public Map<String, Object> insert(String table, Map<String, Object> input) {
        java.util.Map<java.lang.String,java.lang.Object> values = defaults(table);
        values.putAll(input);
        java.util.ArrayList<java.lang.String> columns = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO " + name(table) + " (" + columns.stream().map(c -> column(table, c)).collect(Collectors.joining(","))
                + ") VALUES (" + columns.stream().map(c -> placeholder(table, c)).collect(Collectors.joining(",")) + ") RETURNING *";
        return decode(table, jdbc.queryForMap(sql, columns.stream().map(c -> encode(table, c, values.get(c))).toArray()));
    }
    public Map<String, Object> update(String table, long tenant, Object id, Map<String, Object> input) {
        require(table, tenant, id);
        java.util.LinkedHashMap<java.lang.String,java.lang.Object> values = new LinkedHashMap<>(input);
        if (tables.get(table).containsKey("updated_at")) values.put("updated_at", now());
        values.remove("id"); values.remove("tenant_id"); values.remove("created_at");
        if (values.isEmpty()) return require(table, tenant, id);
        java.util.ArrayList<java.lang.String> columns = new ArrayList<>(values.keySet());
        java.util.ArrayList<java.lang.Object> args = new ArrayList<>(columns.stream().map(c -> encode(table, c, values.get(c))).collect(java.util.stream.Collectors.toList()));
        args.add(tenant); args.add(encode(table, "id", id));
        String sql = "UPDATE " + name(table) + " SET " + columns.stream().map(c -> column(table, c) + "=" + placeholder(table, c)).collect(Collectors.joining(","))
                + " WHERE tenant_id=? AND id=? RETURNING *";
        return decode(table, jdbc.queryForMap(sql, args.toArray()));
    }
    public void delete(String table, long tenant, Object id) {
        require(table, tenant, id);
        jdbc.update("DELETE FROM " + name(table) + " WHERE tenant_id=? AND id=?", tenant, encode(table, "id", id));
    }
    /** Internal tables without tenant_id; callers must establish ownership first. */
    public void updateInternal(String table, Object id, Map<String, Object> input) {
        if (tables.get(table).containsKey("tenant_id")) throw new IllegalArgumentException("tenant update required");
        List<String> columns = new ArrayList<>(input.keySet());
        if (columns.isEmpty()) return;
        List<Object> args = columns.stream().map(c -> encode(table, c, input.get(c))).collect(Collectors.toList());
        args.add(encode(table, "id", id));
        jdbc.update("UPDATE " + name(table) + " SET " + columns.stream().map(c -> column(table, c) + "=?").collect(Collectors.joining(",")) + " WHERE id=?", args.toArray());
    }
    public Map<String, Object> defaults(String table) {
        name(table);
        java.util.LinkedHashMap<java.lang.String,java.lang.Object> result = new LinkedHashMap<String, Object>();
        tables.get(table).forEach((key, info) -> {
            if (info.containsKey("default")) result.put(key, info.get("default"));
            else if ("uuid".equals(info.get("factory"))) result.put(key, UUID.randomUUID());
            else if ("now".equals(info.get("factory"))) result.put(key, now());
        });
        return result;
    }
    @SuppressWarnings("unchecked")
    public Object encode(String table, String key, Object value) {
        if (value == null) return null;
        java.util.Map<java.lang.String,java.lang.Object> info = tables.get(table).get(key);
        if (info == null) throw new IllegalArgumentException("unknown column");
        if (info.containsKey("enum")) {
            java.util.Map<java.lang.String,java.lang.String> enums = (Map<String, String>) info.get("enum");
            String enumValue = value.toString();
            return enums.entrySet().stream().filter(e -> e.getValue().equals(enumValue) || e.getKey().equals(enumValue))
                    .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new ApiError(422, "invalid " + key));
        }
        String type = info.get("type").toString();
        if (type.equals("CHAR(32)") || type.equals("UUID")) {
            java.util.UUID uuid = value instanceof UUID ? (UUID)value : UUID.fromString(value.toString());
            return postgres ? uuid : uuid.toString().replace("-", "");
        }
        if ((type.startsWith("DATETIME") || type.startsWith("TIMESTAMP")) && value instanceof String) {
            String text = value.toString().replace(' ', 'T');
            try { value = LocalDateTime.parse(text); }
            catch (java.time.format.DateTimeParseException local) {
                try { value = java.time.OffsetDateTime.parse(text).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(); }
                catch (java.time.format.DateTimeParseException invalid) { throw new ApiError(422, "invalid " + key); }
            }
        }
        if (value instanceof LocalDateTime) return postgres ? Timestamp.valueOf((LocalDateTime)value) : value.toString().replace('T', ' ');
        return value;
    }
    private String placeholder(String table, String key) {
        column(table, key);
        // PostgreSQL JDBC stringtype=unspecified lets the server infer native enum types.
        return "?";
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(String table, Map<String, Object> source) {
        java.util.LinkedHashMap<java.lang.String,java.lang.Object> row = new LinkedHashMap<>(source);
        tables.get(table).forEach((key, info) -> {
            Object value = row.get(key);
            if (value == null) return;
            String type = info.get("type").toString();
            if (info.containsKey("enum")) row.put(key, ((Map<String, String>) info.get("enum")).getOrDefault(value.toString(), value.toString()));
            else if ("BOOLEAN".equals(type)) row.put(key, value instanceof Boolean ? (Boolean)value : ((Number) value).intValue() != 0);
            else if ("CHAR(32)".equals(type) || "UUID".equals(type)) {
                String id = value.toString();
                if (id.length() == 32) id = id.substring(0,8) + "-" + id.substring(8,12) + "-" + id.substring(12,16) + "-" + id.substring(16,20) + "-" + id.substring(20);
                row.put(key, id);
            } else if (value instanceof Timestamp) row.put(key, ((Timestamp)value).toLocalDateTime().toString());
            else if (type.startsWith("DATETIME") || type.startsWith("TIMESTAMP")) row.put(key, value.toString().replace(' ', 'T'));
        });
        return row;
    }
}
