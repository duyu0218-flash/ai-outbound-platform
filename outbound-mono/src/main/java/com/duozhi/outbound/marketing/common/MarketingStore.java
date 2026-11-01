package com.duozhi.outbound.marketing.common;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Fixed SQL lives in each marketing module; every tenant read or write binds tenant_id. Workers
 * that scan across tenants say so in a comment beside the statement.
 */
@Repository
public class MarketingStore {
    private final JdbcTemplate jdbc;

    public MarketingStore(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public long count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? 0 : n.longValue();
    }

    public int write(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
