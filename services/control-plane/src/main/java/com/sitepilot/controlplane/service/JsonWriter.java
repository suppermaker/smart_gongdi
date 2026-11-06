package com.sitepilot.controlplane.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
class JsonWriter {
    private final ObjectMapper objectMapper;

    JsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode readTree(String value) throws java.io.IOException {
        return objectMapper.readTree(value);
    }

    String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value cannot be serialized", exception);
        }
    }
}
