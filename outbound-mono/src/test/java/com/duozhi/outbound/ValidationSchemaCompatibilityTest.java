package com.duozhi.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.customProperties.ValidationSchemaFactoryWrapper;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationSchemaCompatibilityTest {
    static class ConstrainedArguments {
        @NotNull
        @Size(max = 48)
        public String customerName;
    }

    @Test
    void upstreamSchemaGeneratorRetainsLegacyValidationConstraints() throws Exception {
        var mapper = new ObjectMapper();
        var visitor = new ValidationSchemaFactoryWrapper();
        mapper.acceptJsonFormatVisitor(ConstrainedArguments.class, visitor);
        var schema = mapper.valueToTree(visitor.finalSchema());
        var property = schema.path("properties").path("customerName");
        // This upstream generator emits JSON Schema draft 3 property-level required.
        assertThat(property.path("required").asBoolean()).isTrue();
        assertThat(property.path("maxLength").asInt()).isEqualTo(48);
    }
}
