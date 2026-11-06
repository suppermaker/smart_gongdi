package com.sitepilot.controlplane.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfiguration {
    @Bean(destroyMethod = "close")
    SdkTracerProvider tracerProvider(@Value("${sitepilot.otel.otlp-endpoint:}") String endpoint) {
        var resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), "sitepilot-control-plane").build();
        var builder = SdkTracerProvider.builder().setResource(resource);
        if (!endpoint.isBlank()) {
            var exporter = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint)
                    .setTimeout(Duration.ofSeconds(5)).build();
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        }
        return builder.build();
    }

    @Bean
    OpenTelemetry openTelemetry(SdkTracerProvider provider) {
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }

    @Bean
    Tracer sitePilotTracer(OpenTelemetry telemetry) {
        return telemetry.getTracer("com.sitepilot.control-plane", "2.0");
    }
}
