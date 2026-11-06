package com.sitepilot.controlplane.service;

import com.sitepilot.controlplane.api.Requests.CreateProject;
import com.sitepilot.controlplane.api.Requests.CreateTask;
import com.sitepilot.controlplane.api.Requests.IngestEvent;
import com.sitepilot.controlplane.api.Requests.RegisterEvidence;
import com.sitepilot.controlplane.api.Requests.TransitionRisk;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.domain.AuditLogEntity;
import com.sitepilot.controlplane.domain.EvidenceEntity;
import com.sitepilot.controlplane.domain.InvestigationTaskEntity;
import com.sitepilot.controlplane.domain.OutboxEventEntity;
import com.sitepilot.controlplane.domain.ProjectEntity;
import com.sitepilot.controlplane.domain.RiskEntity;
import com.sitepilot.controlplane.domain.RiskStatus;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import com.sitepilot.controlplane.repository.EvidenceRepository;
import com.sitepilot.controlplane.repository.InvestigationTaskRepository;
import com.sitepilot.controlplane.repository.OutboxEventRepository;
import com.sitepilot.controlplane.repository.ProjectRepository;
import com.sitepilot.controlplane.repository.RiskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlPlaneService {
    private final ProjectRepository projects;
    private final RiskRepository risks;
    private final EvidenceRepository evidence;
    private final InvestigationTaskRepository tasks;
    private final AuditLogRepository audits;
    private final OutboxEventRepository outbox;
    private final EventIngestionTransaction ingestion;
    private final JsonWriter json;

    public ControlPlaneService(ProjectRepository projects, RiskRepository risks,
                               EvidenceRepository evidence, InvestigationTaskRepository tasks,
                               AuditLogRepository audits, OutboxEventRepository outbox,
                               EventIngestionTransaction ingestion, JsonWriter json) {
        this.projects = projects;
        this.risks = risks;
        this.evidence = evidence;
        this.tasks = tasks;
        this.audits = audits;
        this.outbox = outbox;
        this.ingestion = ingestion;
        this.json = json;
    }

    @Transactional
    public ProjectEntity createProject(CreateProject request) {
        RequestContext context = context();
        return projects.findByTenantIdAndProjectId(context.tenantId(), context.projectId())
                .orElseGet(() -> projects.save(new ProjectEntity(
                        context.tenantId(), context.projectId(), request.name())));
    }

    @Transactional(readOnly = true)
    public ProjectEntity project() {
        RequestContext context = context();
        return requireProject(context);
    }

    public RiskEntity ingest(IngestEvent request) {
        RequestContext context = context();
        requireProject(context);
        RiskEntity existing = findRiskByEvent(context, request.eventId());
        if (existing != null) {
            return existing;
        }
        try {
            return ingestion.create(context, request);
        } catch (DataIntegrityViolationException exception) {
            RiskEntity raced = findRiskByEvent(context, request.eventId());
            if (raced != null) {
                return raced;
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public RiskEntity risk(String riskId) {
        RequestContext context = context();
        return requireRisk(context, riskId);
    }

    @Transactional
    public RiskEntity transition(String riskId, TransitionRisk request) {
        RequestContext context = context();
        RiskStatus target = parseStatus(request.status());
        String eventId = transitionEventId(riskId, request.idempotencyKey());
        var previous = outbox.findByTenantIdAndProjectIdAndEventId(
                context.tenantId(), context.projectId(), eventId);
        if (previous.isPresent()) {
            RiskEntity current = requireRisk(context, riskId);
            verifyTransitionReplay(previous.get().getPayload(), target);
            return current;
        }

        RiskEntity risk = risks.findByNamespaceAndRiskIdForUpdate(
                        context.tenantId(), context.projectId(), riskId)
                .orElseThrow(() -> new ApiException("RISK_NOT_FOUND", 404, "risk not found"));
        if (risk.getStatus() == target) {
            var completed = outbox.findByTenantIdAndProjectIdAndEventId(
                    context.tenantId(), context.projectId(), eventId);
            if (completed.isPresent()) {
                verifyTransitionReplay(completed.get().getPayload(), target);
                return risk;
            }
        }
        risk.transition(target);
        String payload = json.write(Map.of("status", target.name(), "idempotencyKey", request.idempotencyKey()));
        audits.save(new AuditLogEntity(
                context.tenantId(), context.projectId(), context.actor(), "RISK_TRANSITION",
                "risk", riskId, json.write(Map.of("status", target.name(), "idempotencyKey", request.idempotencyKey()))));
        try {
            outbox.saveAndFlush(new OutboxEventEntity(
                    context.tenantId(), context.projectId(), eventId,
                    "risk", riskId, "risk.transitioned", payload));
        } catch (DataIntegrityViolationException exception) {
            RiskEntity current = requireRisk(context, riskId);
            var raced = outbox.findByTenantIdAndProjectIdAndEventId(
                    context.tenantId(), context.projectId(), eventId);
            if (raced.isPresent()) {
                verifyTransitionReplay(raced.get().getPayload(), target);
                return current;
            }
            throw exception;
        }
        return risk;
    }

    @Transactional
    public EvidenceEntity register(RegisterEvidence request) {
        RequestContext context = context();
        requireRisk(context, request.riskRef());
        String structuredValue = json.write(request.structuredValue());
        String provenance = json.write(request.toolProvenance());
        String semanticHash = hash(json.write(Map.ofEntries(
                Map.entry("riskRef", request.riskRef()), Map.entry("sourceType", request.sourceType()),
                Map.entry("sourceRef", request.sourceRef()), Map.entry("sourceVersion", String.valueOf(request.sourceVersion())),
                Map.entry("claim", request.claim()), Map.entry("observedAt", request.observedAt().toString()),
                Map.entry("validUntil", String.valueOf(request.validUntil())), Map.entry("digest", String.valueOf(request.digest())),
                Map.entry("quote", String.valueOf(request.quote())), Map.entry("structuredValue", structuredValue),
                Map.entry("reliability", request.reliability()), Map.entry("completeness", request.completeness()),
                Map.entry("toolProvenance", provenance))));
        EvidenceEntity existing = evidence.findByTenantIdAndProjectIdAndEvidenceId(
                context.tenantId(), context.projectId(), request.evidenceId()).orElse(null);
        if (existing != null) {
            if (!existing.hasSemanticHash(semanticHash)) {
                throw new ApiException("EVIDENCE_ID_CONFLICT", 409,
                        "evidence id already exists with different content");
            }
            return existing;
        }
        return evidence.save(new EvidenceEntity(
                context.tenantId(), context.projectId(), request.evidenceId(), request.riskRef(),
                request.sourceType(), request.sourceRef(), request.sourceVersion(), request.claim(),
                request.observedAt(), request.validUntil(), request.digest(), request.quote(),
                structuredValue, request.reliability(), request.completeness(), provenance, semanticHash));
    }

    @Transactional
    public InvestigationTaskEntity createTask(CreateTask request) {
        RequestContext context = context();
        requireRisk(context, request.riskRef());
        String budgets = json.write(request.budgets());
        String semanticHash = hash(json.write(Map.of(
                "goal", request.goal(), "riskRef", request.riskRef(), "status", request.status(),
                "graphRevision", request.graphRevision(), "budgets", budgets)));
        InvestigationTaskEntity existing = tasks.findByTenantIdAndProjectIdAndTaskId(
                context.tenantId(), context.projectId(), request.taskId()).orElse(null);
        if (existing != null) {
            if (!existing.hasSemanticHash(semanticHash)) {
                throw new ApiException("TASK_ID_CONFLICT", 409,
                        "task id already exists with different content");
            }
            return existing;
        }
        return tasks.save(new InvestigationTaskEntity(
                context.tenantId(), context.projectId(), request.taskId(), request.goal(),
                request.riskRef(), request.status(), request.graphRevision(), budgets, semanticHash));
    }

    @Transactional(readOnly = true)
    public InvestigationTaskEntity task(String taskId) {
        RequestContext context = context();
        return tasks.findByTenantIdAndProjectIdAndTaskId(
                        context.tenantId(), context.projectId(), taskId)
                .orElseThrow(() -> new ApiException("TASK_NOT_FOUND", 404, "task not found"));
    }

    private ProjectEntity requireProject(RequestContext context) {
        return projects.findByTenantIdAndProjectId(context.tenantId(), context.projectId())
                .orElseThrow(() -> new ApiException("PROJECT_NOT_FOUND", 404, "project not found"));
    }

    private RiskEntity requireRisk(RequestContext context, String riskId) {
        return risks.findByTenantIdAndProjectIdAndRiskId(
                        context.tenantId(), context.projectId(), riskId)
                .orElseThrow(() -> new ApiException("RISK_NOT_FOUND", 404, "risk not found"));
    }

    private RiskEntity findRiskByEvent(RequestContext context, String eventId) {
        return risks.findByTenantIdAndProjectIdAndSourceEventId(
                context.tenantId(), context.projectId(), eventId).orElse(null);
    }

    private RiskStatus parseStatus(String status) {
        try {
            return RiskStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new ApiException("INVALID_RISK_STATUS", 400, "unknown risk status");
        }
    }

    private String transitionEventId(String riskId, String idempotencyKey) {
        return "risk-transition:" + riskId + ":" + idempotencyKey;
    }

    private void verifyTransitionReplay(String payload, RiskStatus requestedStatus) {
        try {
            JsonNode recorded = json.readTree(payload);
            if (!requestedStatus.name().equals(recorded.path("status").asText())) {
                throw new ApiException("IDEMPOTENCY_KEY_CONFLICT", 409,
                        "idempotency key was already used for a different risk transition");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException("IDEMPOTENCY_RECORD_INVALID", 500,
                    "stored idempotency record cannot be read");
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private RequestContext context() {

        return RequestContextHolder.get();
    }
}
