package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** Build-time boundary check; new exceptions require an explicit edit to one policy file. */
class TenantMapperPolicyTest {
    @Test
    void everyReadUpdateAndDeleteNeedsTenantPredicateOrNamedException() throws Exception {
        JsonNode policy;
        try (var stream = getClass().getResourceAsStream("/contracts/tenant-query-policy.json")) {
            policy = new ObjectMapper().readTree(stream);
        }
        Path directory =
                Path.of(System.getProperty("mapperPolicyDirectory", "src/main/resources/mapper"));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var seenExceptions = new HashSet<String>();
        var violations = new ArrayList<String>();
        int xmlCount = 0, statements = 0, tenantPredicates = 0;
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(f -> f.toString().endsWith(".xml")).sorted().toList()) {
                xmlCount++;
                Element mapper =
                        factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
                String namespace = mapper.getAttribute("namespace");
                for (var node = mapper.getFirstChild();
                        node != null;
                        node = node.getNextSibling()) {
                    if (!(node instanceof Element statement)) continue;
                    String kind = statement.getTagName();
                    if (!kind.equals("select") && !kind.equals("update") && !kind.equals("delete"))
                        continue;
                    statements++;
                    String id = namespace + "." + statement.getAttribute("id");
                    String sql =
                            statement.getTextContent().replace("`", "").replaceAll("\\s+", " ");
                    JsonNode exception = policy.get("exceptions").get(id);
                    if (exception != null) {
                        seenExceptions.add(id);
                        if (!sql.matches(
                                "(?is).*\\b(?:FROM|UPDATE)\\s+"
                                        + exception.get("table").asText()
                                        + "\\b.*")) {
                            violations.add(id + ": exception table differs from SQL");
                        }
                        if (exception.get("category").asText().equals("B")
                                && !kind.equals("select")) {
                            violations.add(
                                    id + ": authentication lookup exception cannot permit writes");
                        }
                    } else if (!sql.matches(
                            "(?is).*\\bWHERE\\b.*\\btenant_id\\s*=\\s*#\\{tenantId\\b.*")) {
                        violations.add(id + ": missing WHERE tenant_id = #{tenantId} predicate");
                    } else tenantPredicates++;
                }
            }
        }
        var declaredExceptions = new HashSet<String>();
        policy.get("exceptions").fieldNames().forEachRemaining(declaredExceptions::add);
        assertThat(seenExceptions)
                .as("Stale or misspelled exception entries")
                .isEqualTo(declaredExceptions);
        assertThat(xmlCount).isGreaterThanOrEqualTo(34);
        assertThat(violations).as("Mapper tenant-boundary violations").isEmpty();
        System.out.println(
                "Tenant mapper policy: XML="
                        + xmlCount
                        + ", statements="
                        + statements
                        + ", tenantPredicates="
                        + tenantPredicates
                        + ", explicitExceptions="
                        + seenExceptions.size());
    }
}
