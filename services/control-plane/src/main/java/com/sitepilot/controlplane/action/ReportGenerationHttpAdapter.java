package com.sitepilot.controlplane.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** HTTP adapter contract for an external report service; it never generates reports itself. */
public final class ReportGenerationHttpAdapter implements ReportGenerationPort {
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json;

    public ReportGenerationHttpAdapter(URI endpoint, HttpClient http, ObjectMapper json) {
        this.endpoint = endpoint; this.http = http; this.json = json;
    }

    @Override
    public ReportReference requestReport(String taskId, String actionId, Map<String, Object> facts) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "schemaVersion", "1.0", "taskId", taskId, "actionId", actionId,
                    "closureFacts", facts));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("report adapter rejected request: " + response.statusCode());
            }
            var value = json.readTree(response.body());
            return new ReportReference(value.path("reportId").asText(), value.path("status").asText());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("report adapter interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("report adapter failed", exception);
        }
    }
}
