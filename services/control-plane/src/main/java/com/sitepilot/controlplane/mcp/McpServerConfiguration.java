package com.sitepilot.controlplane.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.telemetry.SitePilotTelemetry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfiguration {
    private static final String PRINCIPAL = "sitepilot.principal";
    private static final String TRACEPARENT = "sitepilot.traceparent";
    private static final Set<String> READ_ROLES = Set.of("INVESTIGATOR", "COMPLIANCE", "VERIFIER", "SUPERVISOR");
    private static final Set<String> PLATFORM_READ_ROLES = Set.of("QUERY_SUPERVISOR", "KNOWLEDGE",
            "DATABASE", "REVIEW", "REPORT", "OCR", "RISK_INVESTIGATION", "SYNTHESIS",
            "VERIFIER", "SUPERVISOR");

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(
            ObjectMapper objectMapper, McpTokenService tokens,
            @Value("${sitepilot.mcp.allowed-hosts:localhost:18080,127.0.0.1:18080}") String allowedHosts,
            @Value("${sitepilot.mcp.allowed-origins:}") String allowedOrigins) {
        Set<String> hosts = Set.of(allowedHosts.split(","));
        Set<String> origins = allowedOrigins.isBlank() ? Set.of() : Set.of(allowedOrigins.split(","));
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint("/mcp")
                .httpHeaderValidator(headers -> {
                    String host = first(headers.getHeader("Host"));
                    String origin = first(headers.getHeader("Origin"));
                    if (host == null || hosts.stream().noneMatch(allowed -> host.equals(allowed) || host.startsWith(allowed + ":"))) {
                        throw new ServerTransportSecurityException(403, "host rejected");
                    }
                    if (origin != null && !origins.contains(origin)) throw new ServerTransportSecurityException(403, "origin rejected");
                })
                .contextExtractor((HttpServletRequest request) -> {
                    McpPrincipal principal = tokens.verify(request.getHeader("Authorization"));
                    Map<String, Object> values = new HashMap<>();
                    values.put(PRINCIPAL, principal);
                    String traceparent = request.getHeader("traceparent");
                    if (traceparent != null && !traceparent.isBlank()) {
                        values.put(TRACEPARENT, traceparent);
                    }
                    return McpTransportContext.create(values);
                }).build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        var registration = new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("sitepilotMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                            McpToolService tools, McpAuditService audit,
                            SitePilotTelemetry telemetry, ObjectMapper json) {
        return McpServer.sync(transport).serverInfo("sitepilot-control-plane", "0.3.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .validateToolInputs(true).tools(
                        spec("create_platform_task", "Create a validated project-scoped platform DAG", platformCreateSchema(),
                                handler("create_platform_task", Set.of("QUERY_SUPERVISOR", "SUPERVISOR"), tools, audit, telemetry, json,
                                        (p, a) -> tools.createPlatformTask(p, string(a, "userInput"),
                                                string(a, "requestedOutput"), strings(a, "attachmentRefs"),
                                                string(a, "idempotencyKey")))),
                        spec("read_platform_task", "Read the authenticated platform task and specialist results", emptySchema(),
                                handler("read_platform_task", PLATFORM_READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.readPlatformTask(p))),
                        spec("execute_platform_task", "Execute the authenticated validated platform DAG", platformExecuteSchema(),
                                handler("execute_platform_task", Set.of("QUERY_SUPERVISOR", "SUPERVISOR"), tools, audit, telemetry, json,
                                        (p, a) -> tools.executePlatformTask(p, a))),
                        spec("get_project_context", "Read project/task/risk context", versionSchema(),
                                handler("get_project_context", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.projectContext(p, lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("get_risk", "Read the task-linked risk", riskSchema(),
                                handler("get_risk", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.risk(p, string(a, "riskId"), lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("get_investigation_task", "Read the authenticated task", versionSchema(),
                                handler("get_investigation_task", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.task(p, lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("get_routing_context", "Read the redacted event and graph routing context", versionSchema(),
                                handler("get_routing_context", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.routingContext(p, lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("record_routing_decision", "Persist a validated routing decision", routingRecordSchema(),
                                handler("record_routing_decision", Set.of("SUPERVISOR"), tools, audit, telemetry, json,
                                        (p, a) -> tools.recordRouting(p, object(a, "decision"), object(a, "compiledDag"),
                                                lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("get_routing_decision", "Read an authoritative routing decision", routingReadSchema(),
                                handler("get_routing_decision", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.getRouting(p, integer(a, "graphRevision"), string(a, "policyVersion"),
                                                lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("get_agent_context", "Build or reuse a role-scoped revision context", agentContextSchema(),
                                handler("get_agent_context", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.agentContext(p, string(a, "agentTaskId"),
                                                string(a, "contextPolicyVersion"), string(a, "promptVersion"),
                                                lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("list_risk_evidence", "List evidence for the task-linked risk", riskSchema(),
                                handler("list_risk_evidence", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.evidence(p, string(a, "riskId"), lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("get_evidence_detail", "Read immutable detail for one context evidence handle", evidenceDetailSchema(),
                                handler("get_evidence_detail", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.evidenceDetail(p, string(a, "evidenceId"),
                                                lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("graph_read", "Read the authenticated task graph", versionSchema(),
                                handler("graph_read", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.graph(p, lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("merge_graph_delta", "Merge a typed GraphDelta through the sole authoritative writer", objectSchema("delta"),
                                handler("merge_graph_delta", Set.of("SUPERVISOR"), tools, audit, telemetry, json,
                                        (p, a) -> tools.mergeGraph(p, object(a, "delta"), lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("propose_action", "Create a non-authoritative action proposal", objectSchema("proposal"),
                                handler("propose_action", Set.of("SUPERVISOR"), tools, audit, telemetry, json,
                                        (p, a) -> tools.proposeAction(p, object(a, "proposal"), lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("read_action", "Read an action for the authenticated task", actionReadSchema(),
                                handler("read_action", READ_ROLES, tools, audit, telemetry, json,
                                        (p, a) -> tools.readAction(p, string(a, "actionId"), lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))),
                        spec("propose_human_question", "Create a non-authoritative draft question", proposalSchema(),
                                handler("propose_human_question", Set.of("SUPERVISOR"), tools, audit, telemetry, json,
                                        (p, a) -> tools.proposeQuestion(p, string(a, "question"), string(a, "rationale"),
                                                lng(a, "expectedTaskVersion"), integer(a, "expectedGraphRevision")))))
                .build();
    }

    private McpServerFeatures.SyncToolSpecification spec(String name, String description,
                                                           Map<String, Object> schema,
                                                           BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange,
                                                                   McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name(name).description(description).inputSchema(schema).build())
                .callHandler(handler).build();
    }

    private BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange, McpSchema.CallToolRequest,
            McpSchema.CallToolResult> handler(String tool, Set<String> roles, McpToolService ignored,
                                               McpAuditService audit, SitePilotTelemetry telemetry,
                                               ObjectMapper json,
                                               BiFunction<McpPrincipal, Map<String, Object>, Map<String, Object>> call) {
        return (exchange, request) -> {
            McpPrincipal principal = (McpPrincipal) exchange.transportContext().get(PRINCIPAL);
            String traceparent = (String) exchange.transportContext().get(TRACEPARENT);
            RequestContext traceContext = new RequestContext(
                    principal.tenantId(), principal.projectId(), principal.actorId(),
                    "mcp:" + principal.taskId());
            return telemetry.inRemoteSpan("mcp." + tool, traceContext, "mcp", traceparent, () -> {
                String digest = digest(json, request.arguments());
                try {
                    if (!roles.contains(principal.role()) || !principal.permissions().contains(tool)) {
                        throw new ApiException("MCP_PERMISSION_DENIED", 403,
                                "tool is not permitted for this role");
                    }
                    Map<String, Object> result = call.apply(principal, request.arguments());
                    audit.record(principal, tool, "SUCCESS", digest);
                    return McpSchema.CallToolResult.builder().structuredContent(result)
                            .addTextContent(write(json, result)).isError(false).build();
                } catch (Exception exception) {
                    audit.record(principal, tool,
                            exception instanceof ApiException ? "REJECTED" : "FAILED", digest);
                    String code = exception instanceof ApiException api
                            ? api.code() : "MCP_TOOL_FAILED";
                    return McpSchema.CallToolResult.builder()
                            .structuredContent(Map.of("code", code))
                            .addTextContent(code + ": " + exception.getMessage())
                            .isError(true).build();
                }
            });
        };
    }

    private Map<String, Object> platformCreateSchema() {
        return schema(Map.of(
                "userInput", Map.of("type", "string", "minLength", 1),
                "requestedOutput", Map.of("type", "string", "minLength", 1),
                "attachmentRefs", Map.of("type", "array", "items", Map.of("type", "string")),
                "idempotencyKey", Map.of("type", "string", "minLength", 1)),
                List.of("userInput", "requestedOutput", "attachmentRefs", "idempotencyKey"));
    }

    private Map<String, Object> emptySchema() {
        return schema(Map.of(), List.of());
    }

    private Map<String, Object> platformExecuteSchema() {
        Map<String, Object> properties = new HashMap<>();
        for (String name : List.of("dataSourceId", "templateId", "templateVersion", "documentId",
                "outputFormat", "narrative", "documentType", "sourceObjectRef", "inputText",
                "riskTaskId")) {
            properties.put(name, Map.of("type", "string"));
        }
        for (String name : List.of("structuredInput", "bindings", "extractionSchema")) {
            properties.put(name, Map.of("type", "object", "additionalProperties", true));
        }
        properties.put("citations", Map.of("type", "array", "items",
                Map.of("type", "object", "additionalProperties", true)));
        return schema(properties, List.of());
    }

    private Map<String, Object> versionSchema() {
        return schema(Map.of("expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> riskSchema() {
        return schema(Map.of("riskId", Map.of("type", "string"),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("riskId", "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> evidenceDetailSchema() {
        return schema(Map.of("evidenceId", Map.of("type", "string", "minLength", 1),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("evidenceId", "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> objectSchema(String name) {
        return schema(Map.of(name, Map.of("type", "object", "additionalProperties", true),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of(name, "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> agentContextSchema() {
        return schema(Map.of("agentTaskId", Map.of("type", "string", "minLength", 1),
                "contextPolicyVersion", Map.of("type", "string", "minLength", 1),
                "promptVersion", Map.of("type", "string", "minLength", 1),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("agentTaskId", "contextPolicyVersion", "promptVersion",
                        "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> routingRecordSchema() {
        return schema(Map.of("decision", Map.of("type", "object", "additionalProperties", true),
                "compiledDag", Map.of("type", "object", "additionalProperties", true),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("decision", "compiledDag", "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> routingReadSchema() {
        return schema(Map.of("graphRevision", Map.of("type", "integer", "minimum", 0),
                "policyVersion", Map.of("type", "string", "minLength", 1),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("graphRevision", "policyVersion", "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> actionReadSchema() {
        return schema(Map.of("actionId", Map.of("type", "string"),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("actionId", "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> proposalSchema() {
        return schema(Map.of("question", Map.of("type", "string", "minLength", 1),
                "rationale", Map.of("type", "string", "minLength", 1),
                "expectedTaskVersion", Map.of("type", "integer"),
                "expectedGraphRevision", Map.of("type", "integer")),
                List.of("question", "rationale", "expectedTaskVersion", "expectedGraphRevision"));
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static String first(List<String> values) { return values == null || values.isEmpty() ? null : values.get(0); }
    private static String string(Map<String, Object> args, String name) { return String.valueOf(args.get(name)); }
    private static long lng(Map<String, Object> args, String name) { return ((Number) args.get(name)).longValue(); }
    private static int integer(Map<String, Object> args, String name) { return ((Number) args.get(name)).intValue(); }
    private static List<String> strings(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> args, String name) {
        return (Map<String, Object>) args.get(name);
    }

    private String write(ObjectMapper json, Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String digest(ObjectMapper json, Object value) {
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
