package com.duozhi.outbound.common.dao.typehandler;

import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.*;

@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.CHAR, includeNullJdbcType = true)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, UUID value, JdbcType type) throws SQLException {
        statement.setString(index, value.toString());
    }

    @Override
    public UUID getNullableResult(ResultSet results, String name) throws SQLException {
        return decode(results.getString(name));
    }

    @Override
    public UUID getNullableResult(ResultSet results, int index) throws SQLException {
        return decode(results.getString(index));
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int index) throws SQLException {
        return decode(statement.getString(index));
    }

    private UUID decode(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
