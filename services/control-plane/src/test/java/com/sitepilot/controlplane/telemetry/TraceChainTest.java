package com.sitepilot.controlplane.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitepilot.controlplane.context.RequestContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

class TraceChainTest {
    @Test
    void spansShareTraceAndExcludeSensitivePayloads() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetry telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        SitePilotTelemetry sitePilot = new SitePilotTelemetry(telemetry.getTracer("test"));
        RequestContext context = new RequestContext("tenant-a", "project-a", "actor-a", "corr-a");

        var root = sitePilot.startServerSpan("risk-event", context, null);
        try (Scope ignored = root.makeCurrent()) {
            sitePilot.inSpan("temporal-workflow", context, "workflow", () ->
                    sitePilot.inSpan("agent-mcp-evidence-approval-action-closure", context, "closure", () -> "ok"));
        } finally {
            root.end();
        }

        var spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);
        provider.close();
        assertThat(spans).extracting(item -> item.getTraceId()).containsOnly(spans.get(0).getTraceId());
        assertThat(spans).allSatisfy(item -> {
            String attributes = item.getAttributes().toString().toLowerCase();
            assertThat(attributes).doesNotContain("token", "secret", "password", "payload");
            assertThat(attributes).contains("sitepilot.correlation_id");
        });
    }

    @Test
    void remoteTraceparentIsContinuedAcrossTheHttpBoundary() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetry telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        SitePilotTelemetry sitePilot = new SitePilotTelemetry(telemetry.getTracer("remote-test"));
        RequestContext context = new RequestContext("tenant-a", "project-a", "actor-a", "corr-a");
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";

        sitePilot.inRemoteSpan("mcp.graph_read", context, "mcp", traceparent,
                () -> sitePilot.inSpan("graph.read", context, "GraphMergeService", () -> "ok"));

        var spans = exporter.getFinishedSpanItems();
        provider.close();
        assertThat(spans).hasSize(2);
        assertThat(spans).extracting(item -> item.getTraceId()).containsOnly(traceId);
    }
}
