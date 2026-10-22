package com.duozhi.outbound.auth.support;

import com.duozhi.outbound.auth.domain.SysUser;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.BeanWrapperImpl;

/** Same source snake_case fields and timestamp strings; entities stay internal. */
public final class UserRecords {
    private UserRecords() {}

    public static Map<String, Object> map(SysUser row) {
        if (row == null) return null;
        var result = new LinkedHashMap<String, Object>();
        var bean = new BeanWrapperImpl(row);
        for (var descriptor : bean.getPropertyDescriptors()) {
            String property = descriptor.getName();
            if (property.equals("class")) continue;
            Object value = bean.getPropertyValue(property);
            if (value instanceof LocalDateTime t) value = t.toString();
            result.put(
                    property.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT), value);
        }
        return result;
    }

    public static void patch(SysUser row, Map<String, Object> changes) {
        var bean = new BeanWrapperImpl(row);
        for (var entry : changes.entrySet()) {
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char c : entry.getKey().toCharArray()) {
                if (c == '_') {
                    upper = true;
                    continue;
                }
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            bean.setPropertyValue(name.toString(), entry.getValue());
        }
    }
}
