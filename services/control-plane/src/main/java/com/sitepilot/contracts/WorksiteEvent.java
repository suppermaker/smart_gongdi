package com.sitepilot.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;

public record WorksiteEvent(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        Subject subject,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        @JsonProperty("ingested_at") OffsetDateTime ingestedAt,
        Source source,
        Map<String, Object> attributes,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("aggregate_version") Integer aggregateVersion) {

    public record Subject(String type, String id, @JsonProperty("display_name") String displayName) {}

    public record Source(
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_ref") String sourceRef,
            @JsonProperty("source_version") String sourceVersion) {}
}
