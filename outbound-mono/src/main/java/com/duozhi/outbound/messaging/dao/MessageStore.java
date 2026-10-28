package com.duozhi.outbound.messaging.dao;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fixed SQL lives in the messaging domain; every resource read includes its effective tenant. */
@Repository
public class MessageStore {
    private final JdbcTemplate jdbc;

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public MessageStore(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
