package com.duozhi.outbound.common.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

/** All pools and the dedicated ownership connection must have bounded driver waits. */
public final class JdbcBudgets {
    private JdbcBudgets() {}

    public static String boundedUrl(String url) {
        if (url == null || !url.startsWith("jdbc:mysql:"))
            throw new IllegalArgumentException("MySQL JDBC URL required");
        var found = new HashSet<String>();
        int query = url.indexOf('?');
        if (query >= 0) {
            for (String parameter : url.substring(query + 1).split("&")) {
                String[] pair = parameter.split("=", 2);
                String key =
                        URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                int max =
                        switch (key) {
                            case "connecttimeout" -> 3000;
                            case "sockettimeout" -> 10000;
                            default -> 0;
                        };
                if (max == 0) continue;
                try {
                    if (!(pair[0].equals("connectTimeout") || pair[0].equals("socketTimeout")))
                        throw new IllegalArgumentException();
                    int value =
                            Integer.parseInt(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    if (!found.add(key) || value < 1 || value > max)
                        throw new IllegalArgumentException();
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException(
                            "JDBC timeout must be positive and within the runtime budget");
                }
            }
        }
        String result = url;
        if (!found.contains("connecttimeout"))
            result += (result.contains("?") ? "&" : "?") + "connectTimeout=3000";
        if (!found.contains("sockettimeout")) result += "&socketTimeout=10000";
        return result;
    }
}
