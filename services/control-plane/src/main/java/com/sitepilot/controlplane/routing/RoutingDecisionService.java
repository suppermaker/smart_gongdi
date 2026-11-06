package com.sitepilot.controlplane.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.error.ApiException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutingDecisionService {
    private final RoutingDecisionRepository decisions;
    private final RoutingDecisionValidator validator;
    private final ObjectMapper json;

    public RoutingDecisionService(RoutingDecisionRepository decisions,
                                  RoutingDecisionValidator validator,
                                  ObjectMapper json) {
        this.decisions = decisions;
        this.validator = validator;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> record(RequestContext context, String taskId, int graphRevision,
                                      JsonNode decision, JsonNode compiledDag) {
        validator.validate(decision, taskId, graphRevision);
        String policyVersion = decision.path("policy_version").asText();
        String inputDigest = decision.path("input_digest").asText();
        var existing = decisions.findByTenantIdAndProjectIdAndTaskIdAndGraphRevisionAndPolicyVersion(
                context.tenantId(), context.projectId(), taskId, graphRevision, policyVersion);
        if (existing.isPresent()) return idempotent(existing.get(), inputDigest);
        RoutingDecisionEntity entity = new RoutingDecisionEntity(
                context.tenantId(), context.projectId(), taskId, graphRevision, policyVersion,
                decision.path("provider").asText(), decision.path("model_version").asText(),
                inputDigest, decision.path("decision_id").asText(), write(decision),
                write(compiledDag), decision.path("route_disposition").asText());
        try {
            return result(decisions.saveAndFlush(entity), true);
        } catch (DataIntegrityViolationException conflict) {
            RoutingDecisionEntity concurrent = decisions
                    .findByTenantIdAndProjectIdAndTaskIdAndGraphRevisionAndPolicyVersion(
                            context.tenantId(), context.projectId(), taskId,
                            graphRevision, policyVersion)
                    .orElseThrow(() -> conflict);
            return idempotent(concurrent, inputDigest);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(RequestContext context, String taskId,
                                   int graphRevision, String policyVersion) {
        RoutingDecisionEntity entity = decisions
                .findByTenantIdAndProjectIdAndTaskIdAndGraphRevisionAndPolicyVersion(
                        context.tenantId(), context.projectId(), taskId,
                        graphRevision, policyVersion)
                .orElseThrow(() -> new ApiException(
                        "ROUTING_DECISION_NOT_FOUND", 404, "routing decision not found"));
        return result(entity, false);
    }

    private Map<String, Object> idempotent(RoutingDecisionEntity entity, String inputDigest) {
        if (!entity.getInputDigest().equals(inputDigest)) {
            throw new ApiException("ROUTING_DECISION_CONFLICT", 409,
                    "routing key was already used for different input");
        }
        return result(entity, false);
    }

    private Map<String, Object> result(RoutingDecisionEntity entity, boolean applied) {
        return Map.of(
                "taskId", entity.getTaskId(),
                "graphRevision", entity.getGraphRevision(),
                "policyVersion", entity.getPolicyVersion(),
                "status", entity.getStatus(),
                "decision", read(entity.getDecision()),
                "compiledDag", read(entity.getCompiledDag()),
                "applied", applied);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException("ROUTING_DECISION_INVALID", 400,
                    "routing payload cannot be serialized");
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored routing decision is invalid", exception);
        }
    }
}
