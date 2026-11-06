package com.sitepilot.controlplane.telemetry;

import com.sitepilot.controlplane.context.RequestContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class SitePilotTelemetry {
    private static final TextMapGetter<Map<String, String>> TRACE_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };

    private final Tracer tracer;

    public SitePilotTelemetry(Tracer tracer) {
        this.tracer = tracer;
    }

    public <T> T inSpan(String name, RequestContext context, String stage, Supplier<T> operation) {
        return runSpan(tracer.spanBuilder(name).startSpan(), context, stage, operation);
    }

    public <T> T inRemoteSpan(
            String name,
            RequestContext context,
            String stage,
            String traceparent,
            Supplier<T> operation) {
        Span span = tracer.spanBuilder(name)
                .setParent(remoteParent(traceparent))
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        return runSpan(span, context, stage, operation);
    }

    public Span startServerSpan(String name, RequestContext context, String traceparent) {
        Span span = tracer.spanBuilder(name)
                .setParent(remoteParent(traceparent))
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        attributes(span, context, "http");
        return span;
    }

    private <T> T runSpan(
            Span span, RequestContext context, String stage, Supplier<T> operation) {
        attributes(span, context, stage);
        try (Scope ignored = span.makeCurrent()) {
            return operation.get();
        } catch (RuntimeException exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR);
            throw exception;
        } finally {
            span.end();
        }
    }

    private void attributes(Span span, RequestContext context, String stage) {
        span.setAttribute("sitepilot.tenant", context.tenantId());
        span.setAttribute("sitepilot.project", context.projectId());
        span.setAttribute("sitepilot.correlation_id", context.correlationId());
        span.setAttribute("sitepilot.stage", stage);
    }

    private Context remoteParent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.current();
        }
        return W3CTraceContextPropagator.getInstance().extract(
                Context.current(), Map.of("traceparent", traceparent), TRACE_GETTER);
    }
}
