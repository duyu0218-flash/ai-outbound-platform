package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

class ValidationSchemaCompatibilityTest {
    static class ConstrainedArguments {
        @JsonProperty(required = true)
        @Schema(maxLength = 48)
        public String customerName;
    }

    @Test
    void currentSpringAiSchemaGeneratorRetainsRequiredAndLengthConstraints() throws Exception {
        var schema =
                new ObjectMapper()
                        .readTree(JsonSchemaGenerator.generateForType(ConstrainedArguments.class));
        assertThat(schema.path("required").toString()).contains("customerName");
        assertThat(schema.path("properties").path("customerName").path("maxLength").asInt())
                .isEqualTo(48);
    }
}
