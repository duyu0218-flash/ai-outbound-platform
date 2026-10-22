package com.duozhi.outbound.common.web;

import com.duozhi.outbound.common.exception.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Structural request/response contract exported from the pinned Python baseline. Business
 * invariants are implemented separately in each service.
 */
@Component
public class Contract {
    private final ObjectMapper mapper;
    private final JsonNode schemas;

    public Contract(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        try (InputStream in =
                new ClassPathResource("contracts/openapi-baseline.json").getInputStream()) {
            schemas = mapper.readTree(in).path("components").path("schemas");
        }
        try (var in = new ClassPathResource("contracts/openapi-capacity.json").getInputStream()) {
            var additions = mapper.readTree(in).path("components").path("schemas");
            for (var entry : additions.properties()) {
                if (schemas.has(entry.getKey()))
                    throw new IOException("capacity contract cannot override baseline schema");
                ((ObjectNode) schemas).set(entry.getKey(), entry.getValue());
            }
        }
    }

    private JsonNode resolve(JsonNode schema) {
        if (schema.has("$ref"))
            return schemas.path(schema.get("$ref").asText().replace("#/components/schemas/", ""));
        return schema;
    }

    public Map<String, Object> input(String type, Map<String, Object> raw, boolean defaults) {
        JsonNode schema = schemas.path(type);
        if (schema.isMissingNode()) throw new IllegalArgumentException("Unknown contract " + type);
        validate(schema, mapper.valueToTree(raw), type);
        LinkedHashMap<String, Object> output = new LinkedHashMap<String, Object>();
        schema.path("properties")
                .properties()
                .forEach(
                        entry -> {
                            String key = entry.getKey();
                            if (raw.containsKey(key)) {
                                validate(entry.getValue(), mapper.valueToTree(raw.get(key)), key);
                                output.put(key, raw.get(key));
                            } else if (defaults && entry.getValue().has("default"))
                                output.put(
                                        key,
                                        mapper.convertValue(
                                                entry.getValue().get("default"), Object.class));
                        });
        for (JsonNode required : schema.path("required"))
            if (!raw.containsKey(required.asText()))
                throw new ApiError(400, required.asText() + " is required");
        return output;
    }

    public Map<String, Object> output(String type, Map<String, Object> row) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        JsonNode schema = schemas.path(type);
        if (schema.isMissingNode()) throw new IllegalArgumentException("Unknown contract " + type);
        schema.path("properties")
                .fieldNames()
                .forEachRemaining(
                        key -> {
                            if (row.containsKey(key)) result.put(key, row.get(key));
                            else if (schema.path("properties").path(key).has("default"))
                                result.put(
                                        key,
                                        mapper.convertValue(
                                                schema.path("properties").path(key).get("default"),
                                                Object.class));
                        });
        return result;
    }

    private void validate(JsonNode unresolved, JsonNode value, String key) {
        JsonNode schema = resolve(unresolved);
        if (schema.has("anyOf")) {
            for (JsonNode candidate : schema.get("anyOf"))
                try {
                    validate(candidate, value, key);
                    return;
                } catch (ApiError ignored) {
                }
            throw new ApiError(400, "invalid " + key);
        }
        String type = schema.path("type").asText();
        boolean valid;
        switch (type) {
            case "null":
                valid = value.isNull();
                break;
            case "string":
                valid = value.isTextual();
                break;
            case "integer":
                valid = value.isIntegralNumber();
                break;
            case "number":
                valid = value.isNumber();
                break;
            case "boolean":
                valid = value.isBoolean();
                break;
            case "array":
                valid = value.isArray();
                break;
            case "object":
                valid = value.isObject();
                break;
            default:
                valid = true;
        }
        if (!valid) throw new ApiError(400, "invalid " + key);
        if (schema.has("const") && !schema.get("const").equals(value))
            throw new ApiError(400, "invalid " + key);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) if (option.equals(value)) found = true;
            if (!found) throw new ApiError(400, "invalid " + key);
        }
        if (value.isTextual()) {
            String text = value.asText();
            int length = text.codePointCount(0, text.length());
            if (length < schema.path("minLength").asInt(0)
                    || length > schema.path("maxLength").asInt(Integer.MAX_VALUE))
                throw new ApiError(400, "invalid length: " + key);
            if (schema.has("pattern")
                    && !Pattern.compile(schema.get("pattern").asText()).matcher(text).find())
                throw new ApiError(400, "invalid " + key);
            if ("uuid".equals(schema.path("format").asText()))
                try {
                    UUID.fromString(text);
                } catch (IllegalArgumentException e) {
                    throw new ApiError(400, "invalid " + key);
                }
        }
        if (value.isNumber()
                && (value.asDouble() < schema.path("minimum").asDouble(-Double.MAX_VALUE)
                        || value.asDouble() > schema.path("maximum").asDouble(Double.MAX_VALUE)))
            throw new ApiError(400, "out of range: " + key);
        if (value.isObject()) {
            for (JsonNode required : schema.path("required"))
                if (!value.has(required.asText()))
                    throw new ApiError(400, key + "." + required.asText() + " is required");
            schema.path("properties")
                    .properties()
                    .forEach(
                            entry -> {
                                if (value.has(entry.getKey()))
                                    validate(
                                            entry.getValue(),
                                            value.get(entry.getKey()),
                                            key + "." + entry.getKey());
                            });
            if (schema.has("additionalProperties")
                    && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean())
                value.fieldNames()
                        .forEachRemaining(
                                name -> {
                                    if (!schema.path("properties").has(name))
                                        throw new ApiError(
                                                400, "unsupported field: " + key + "." + name);
                                });
        }
        if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt(0)
                    || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE))
                throw new ApiError(400, "invalid count: " + key);
            for (JsonNode item : value) validate(schema.path("items"), item, key);
        }
    }

    public static int page(int page, int size) {
        if (page < 1 || size < 1 || size > 200 || page > Integer.MAX_VALUE / size)
            throw new ApiError(400, "invalid pagination");
        return (page - 1) * size;
    }
}
