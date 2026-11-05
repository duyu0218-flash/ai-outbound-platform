package com.duozhi.outbound.common.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JdbcBudgetsTest {
    @Test
    void addsNetworkBudgetsAndRetainsTlsAndSmallerExplicitBudgets() {
        assertEquals(
                "jdbc:mysql://localhost/db?connectTimeout=3000&socketTimeout=10000",
                JdbcBudgets.boundedUrl("jdbc:mysql://localhost/db"));
        String secure =
                "jdbc:mysql://db/app?sslMode=VERIFY_IDENTITY&connectTimeout=1000&socketTimeout=2000";
        assertEquals(secure, JdbcBudgets.boundedUrl(secure));
    }

    @Test
    void refusesUnboundedDuplicateOrMalformedOverridesWithoutPrintingTheUrl() {
        for (String query :
                java.util.List.of(
                        "socketTimeout=0",
                        "socketTimeout=-1",
                        "socketTimeout=10001",
                        "connectTimeout=3001",
                        "socketTimeout",
                        "connectTimeout=1&connectTimeout=2",
                        "socketTimeout=bad")) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    JdbcBudgets.boundedUrl(
                                            "jdbc:mysql://private/db?password=private&" + query));
            assertFalse(error.getMessage().contains("private"));
        }
    }
}
