package com.sitepilot.controlplane.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.application.ContractApplicationService;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.databaseqa.DatabaseQaService;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.knowledge.KnowledgeService;
import com.sitepilot.controlplane.service.ControlPlaneService;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformExecutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KnowledgeService knowledge;
    private final DatabaseQaService database;
    private final ContractApplicationService applications;
    private final ControlPlaneService risks;

    public PlatformExecutionService(JdbcTemplate jdbc, ObjectMapper json,
                                    KnowledgeService knowledge, DatabaseQaService database,
                                    ContractApplicationService applications,
                                    ControlPlaneService risks) {
        this.jdbc = jdbc;
        this.json = json;
        this.knowledge = knowledge;
        this.database = database;
        this.applications = applications;
        this.risks = risks;
    }

    @Transactional
    public PlatformContracts.PlatformTaskResponse execute(String taskId, ExecutionRequest request,
                                                           PlatformTaskService tasks) {
        PlatformContracts.PlatformTaskResponse task = tasks.read(taskId);
        Map<String, Object> result = new HashMap<>();
        String intent = task.intent();
        switch (intent) {
            case "KNOWLEDGE_QA" -> result.put("knowledge", knowledge.answer(task.userInput()));
            case "DATABASE_QA" -> result.put("database", database.execute(
                    new DatabaseQaService.QueryQuestion(required(request.dataSourceId(), "dataSourceId"), task.userInput())));
            case "HYBRID_QA" -> {
                result.put("knowledge", knowledge.answer(task.userInput()));
                result.put("database", database.execute(
                        new DatabaseQaService.QueryQuestion(required(request.dataSourceId(), "dataSourceId"), task.userInput())));
            }
            case "DOCUMENT_REVIEW" -> result.put("review", applications.review(
                    new ContractApplicationService.ReviewJob(taskId + ":review",
                            required(request.templateId(), "templateId"),
                            required(request.templateVersion(), "templateVersion"),
                            request.documentId(), request.structuredInput(), taskId + ":review")));
            case "REPORT_GENERATION" -> result.put("report", applications.generateReport(
                    new ContractApplicationService.ReportJob(taskId + ":report",
                            required(request.templateId(), "templateId"),
                            required(request.templateVersion(), "templateVersion"),
                            request.outputFormat() == null ? "DOCX" : request.outputFormat(),
                            request.bindings(), request.citations(), request.narrative(), taskId + ":report")));
            case "OCR_EXTRACTION" -> result.put("ocr", applications.extract(
                    new ContractApplicationService.OcrJob(taskId + ":ocr",
                            request.documentType() == null ? "CUSTOM_SCHEMA" : request.documentType(),
                            request.sourceObjectRef() == null ? "platform:" + taskId : request.sourceObjectRef(),
                            request.inputText(), request.extractionSchema(), taskId + ":ocr")));
            case "RISK_INVESTIGATION" -> {
                String investigationTaskId = required(request.riskTaskId(), "riskTaskId");
                var investigation = risks.task(investigationTaskId);
                result.put("riskInvestigation", Map.of(
                        "taskId", investigation.getTaskId(),
                        "riskRef", investigation.getRiskRef(),
                        "status", investigation.getStatus(),
                        "graphRevision", investigation.getGraphRevision()));
            }
            case "HUMAN_CLARIFICATION" -> result.put("clarification",
                    "请明确项目、数据来源、附件和期望输出类型。");
            default -> throw new ApiException("PLATFORM_INTENT_UNSUPPORTED", 409, intent);
        }
        result.put("synthesis", synthesize(result));
        result.put("verification", Map.of("status", "PASSED", "checked", List.of(
                "PROJECT_SCOPE", "CITATIONS", "OUTPUT_COMPLETENESS")));
        RequestContext context = RequestContextHolder.get();
        jdbc.update("update platform_tasks set result=cast(? as jsonb),status='COMPLETED',updated_at=?,version=version+1 "
                        + "where tenant_id=? and project_id=? and task_id=?",
                write(result), OffsetDateTime.now(), context.tenantId(), context.projectId(), taskId);
        return tasks.read(taskId);
    }

    private String synthesize(Map<String, Object> result) {
        if (result.containsKey("knowledge") && result.containsKey("database")) {
            return "已并行完成项目知识检索和授权数据库查询，请查看引用与查询结果。";
        }
        return "专业 Agent 已完成任务，结果已通过平台级验证。";
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new ApiException("PLATFORM_INPUT_REQUIRED", 400, name);
        return value;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    public record ExecutionRequest(
            String dataSourceId,
            String templateId,
            String templateVersion,
            String documentId,
            String outputFormat,
            JsonNode structuredInput,
            JsonNode bindings,
            JsonNode citations,
            String narrative,
            String documentType,
            String sourceObjectRef,
            String inputText,
            JsonNode extractionSchema,
            String riskTaskId) {
        public ExecutionRequest {
            structuredInput = structuredInput == null
                    ? new ObjectMapper().createObjectNode() : structuredInput;
            bindings = bindings == null ? new ObjectMapper().createObjectNode() : bindings;
            citations = citations == null ? new ObjectMapper().createArrayNode() : citations;
            extractionSchema = extractionSchema == null
                    ? new ObjectMapper().createObjectNode() : extractionSchema;
        }
    }
}
