package com.sitepilot.controlplane.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.telemetry.SitePilotTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("sitePilotRequestContextFilter")
public class RequestContextFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;
    private final SitePilotTelemetry telemetry;

    public RequestContextFilter(ObjectMapper objectMapper, SitePilotTelemetry telemetry) {
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenant = request.getHeader("X-SitePilot-Tenant");
        String project = request.getHeader("X-SitePilot-Project");
        String actor = request.getHeader("X-SitePilot-Actor");
        if (blank(tenant) || blank(project) || blank(actor)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "code", "IDENTITY_CONTEXT_REQUIRED",
                    "message", "trusted identity headers are required"));
            return;
        }
        String correlation = request.getHeader("X-Correlation-ID");
        if (blank(correlation)) correlation = UUID.randomUUID().toString();
        RequestContext context = new RequestContext(tenant, project, actor, correlation);
        response.setHeader("X-Correlation-ID", correlation);
        RequestContextHolder.set(context);
        Span span = telemetry.startServerSpan(
                "control-plane " + request.getMethod() + " " + request.getRequestURI(),
                context,
                request.getHeader("traceparent"));
        try (Scope ignored = span.makeCurrent()) {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            span.recordException(exception);
            throw exception;
        } finally {
            span.end();
            RequestContextHolder.clear();
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
