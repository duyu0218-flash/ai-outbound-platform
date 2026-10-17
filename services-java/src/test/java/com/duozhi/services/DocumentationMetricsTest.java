package com.duozhi.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentationMetricsTest {
    @TempDir Path directory;
    @Test void allRoleContractsResolveReferencesAndDescribeAuthentication() throws Exception {
        for (String role : List.of("agent", "recording", "voice", "media")) {
            var documentation = new ApiDocumentation(role);
            Map<String,Object> spec = Support.object(documentation.openapi());
            assertTrue(spec.get("openapi").toString().startsWith("3."));
            assertFalse(Support.map(spec.get("paths")).isEmpty());
            assertFalse(Support.map(Support.map(spec.get("components")).get("securitySchemes")).isEmpty());
            references(spec, spec);
            byte[] copy = documentation.openapi(); copy[0] = 0;
            assertEquals('{', documentation.openapi()[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> new ApiDocumentation("../voice"));
    }
    private void references(Object node, Map<String,Object> spec) {
        if (node instanceof Map<?,?> map) {
            if (map.containsKey("$ref")) {
                String ref = map.get("$ref").toString(); assertTrue(ref.startsWith("#/"));
                Object target = spec;
                for (String part : ref.substring(2).split("/")) target = Support.map(target).get(part.replace("~1", "/").replace("~0", "~"));
                assertNotNull(target, ref);
            }
            map.values().forEach(value -> references(value, spec));
        } else if (node instanceof List<?> list) list.forEach(value -> references(value, spec));
    }
    @Test void monitoringOmitsUnknownValuesAndRecordsOnlySuccessfulRootTransactions() throws Exception {
        StringBuilder output = new StringBuilder();
        Metrics.append(output, "test_", Map.of("valid", 2, "enabled", true, "unknown", "secret", "invalid", Double.NaN));
        assertTrue(output.toString().contains("test_valid 2\n")); assertTrue(output.toString().contains("test_enabled 1\n"));
        assertFalse(output.toString().contains("secret")); assertFalse(output.toString().contains("unknown")); assertFalse(output.toString().contains("NaN"));
        assertThrows(IllegalArgumentException.class, () -> Metrics.append(output, "test_", Map.of("bad{label}", 1)));
        assertTrue(Metrics.jvm().contains("ai_outbound_java_uptime_seconds "));
        try (var ledger = new LocalLedger(directory.resolve("metrics.db").toString())) {
            assertEquals(0, ledger.commits());
            ledger.transaction(db -> { ledger.transaction(nested -> null); return null; });
            assertEquals(1, ledger.commits());
            assertThrows(Support.Failure.class, () -> ledger.transaction(db -> { throw new IllegalStateException("rollback"); }));
            assertEquals(1, ledger.commits());
        }
    }
}
