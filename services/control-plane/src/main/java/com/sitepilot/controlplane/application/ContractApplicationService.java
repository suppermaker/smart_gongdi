package com.sitepilot.controlplane.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.domain.AuditLogEntity;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractApplicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditLogRepository audits;
    private final OcrAdapter ocr;

    public ContractApplicationService(JdbcTemplate jdbc, ObjectMapper json,
                                      AuditLogRepository audits,
                                      @Value("${sitepilot.ocr.endpoint:}") String ocrEndpoint) {
        this.jdbc = jdbc;
        this.json = json;
        this.audits = audits;
        this.ocr = ocrEndpoint.isBlank()
                ? new DeterministicOcrAdapter()
                : new HttpOcrAdapter(ocrEndpoint, json);
    }

    @Transactional
    public Map<String, Object> createReviewTemplate(ReviewTemplate request) {
        RequestContext context = RequestContextHolder.get();
        jdbc.update("insert into review_templates "
                        + "(tenant_id,project_id,template_id,template_version,name,effective_from,effective_to,rules,created_at) "
                        + "values (?,?,?,?,?,?,?,cast(? as jsonb),?)",
                context.tenantId(), context.projectId(), request.templateId(), request.templateVersion(),
                request.name(), request.effectiveFrom(), request.effectiveTo(), write(request.rules()),
                OffsetDateTime.now());
        return Map.of("templateId", request.templateId(), "templateVersion", request.templateVersion(),
                "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> review(ReviewJob request) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> prior = jdbc.queryForList(
                "select job_id from review_jobs where tenant_id=? and project_id=? and idempotency_key=?",
                context.tenantId(), context.projectId(), request.idempotencyKey());
        if (!prior.isEmpty()) return reviewJob(String.valueOf(prior.get(0).get("job_id")));
        Map<String, Object> template = reviewTemplate(context, request.templateId(), request.templateVersion());
        String content = request.documentId() == null ? request.structuredInput().toString()
                : knowledgeText(context, request.documentId());
        ArrayNode findings = json.createArrayNode();
        JsonNode rules = readJson(String.valueOf(template.get("rules")));
        boolean incomplete = false;
        OffsetDateTime effectiveTo = offsetDateTime(template.get("effective_to"));
        if (effectiveTo != null && effectiveTo.isBefore(OffsetDateTime.now())) incomplete = true;
        for (JsonNode rule : rules) {
            String keyword = rule.path("keyword").asText();
            boolean mustContain = rule.path("mustContain").asBoolean(true);
            boolean matched = content.contains(keyword);
            if ((mustContain && !matched) || (!mustContain && matched)) {
                double confidence = rule.path("confidence").asDouble(0.9);
                String standardRef = rule.path("standardRef").asText();
                String quote = rule.path("quote").asText();
                if (confidence < 0.8 || standardRef.isBlank() || quote.isBlank()) incomplete = true;
                ObjectNode finding = findings.addObject();
                finding.put("findingId", request.jobId() + ":finding:" + findings.size());
                finding.put("severity", rule.path("severity").asText("MEDIUM"));
                finding.set("documentLocation", rule.path("documentLocation").isObject()
                        ? rule.path("documentLocation") : json.valueToTree(Map.of("section", "document")));
                finding.put("problem", rule.path("problem").asText("未满足审查规则: " + keyword));
                finding.put("standardRef", standardRef);
                finding.put("quote", quote);
                finding.put("suggestion", rule.path("suggestion").asText("请按标准补充内容"));
                finding.put("confidence", confidence);
            }
        }
        String status = incomplete ? "NEEDS_HUMAN_REVIEW" : "COMPLETED";
        jdbc.update("insert into review_jobs "
                        + "(tenant_id,project_id,job_id,template_id,template_version,document_id,structured_input,"
                        + "findings,status,idempotency_key,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?)",
                context.tenantId(), context.projectId(), request.jobId(), request.templateId(),
                request.templateVersion(), request.documentId(), write(request.structuredInput()),
                write(findings), status, request.idempotencyKey(), OffsetDateTime.now(), OffsetDateTime.now());
        audit(context, "DOCUMENT_REVIEW_COMPLETED", "review_job", request.jobId(),
                Map.of("status", status, "findingCount", findings.size()));
        return reviewJob(request.jobId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reviewJob(String jobId) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select job_id,template_id,template_version,document_id,findings::text,status "
                        + "from review_jobs where tenant_id=? and project_id=? and job_id=?",
                context.tenantId(), context.projectId(), jobId);
        if (rows.isEmpty()) throw new ApiException("REVIEW_JOB_NOT_FOUND", 404, "review job not found");
        Map<String, Object> row = rows.get(0);
        return Map.of("jobId", row.get("job_id"), "templateId", row.get("template_id"),
                "templateVersion", row.get("template_version"), "status", row.get("status"),
                "findings", readJson(String.valueOf(row.get("findings"))));
    }

    @Transactional
    public Map<String, Object> createReportTemplate(ReportTemplate request) {
        RequestContext context = RequestContextHolder.get();
        jdbc.update("insert into report_templates "
                        + "(tenant_id,project_id,template_id,template_version,name,template_text,variables,sections,created_at) "
                        + "values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)",
                context.tenantId(), context.projectId(), request.templateId(), request.templateVersion(),
                request.name(), request.templateText(), write(request.variables()), write(request.sections()),
                OffsetDateTime.now());
        return Map.of("templateId", request.templateId(), "templateVersion", request.templateVersion());
    }

    @Transactional
    public Map<String, Object> generateReport(ReportJob request) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> prior = jdbc.queryForList(
                "select job_id from report_jobs where tenant_id=? and project_id=? and idempotency_key=?",
                context.tenantId(), context.projectId(), request.idempotencyKey());
        if (!prior.isEmpty()) return reportJob(String.valueOf(prior.get(0).get("job_id")));
        Map<String, Object> template = reportTemplate(context, request.templateId(), request.templateVersion());
        String text = String.valueOf(template.get("template_text"));
        request.bindings().fields().forEachRemaining(entry -> {
            // Replacement is performed below using mutable holder semantics.
        });
        for (var iterator = request.bindings().fields(); iterator.hasNext();) {
            var entry = iterator.next();
            text = text.replace("{{" + entry.getKey() + "}}", entry.getValue().asText());
        }
        if (text.contains("{{")) {
            throw new ApiException("REPORT_BINDING_INCOMPLETE", 409, "required report variables are missing");
        }
        byte[] docx = renderDocx(text, request.bindings());
        byte[] artifact = docx;
        String artifactName = request.jobId() + ".docx";
        String status = "COMPLETED";
        String errorCode = null;
        if ("PDF".equalsIgnoreCase(request.outputFormat())) {
            try {
                artifact = convertPdf(docx, request.jobId());
                artifactName = request.jobId() + ".pdf";
            } catch (ApiException unavailable) {
                status = "DOCX_READY";
                errorCode = "PDF_CONVERTER_UNAVAILABLE";
            }
        }
        jdbc.update("insert into report_jobs "
                        + "(tenant_id,project_id,job_id,template_id,template_version,output_format,bindings,citations,"
                        + "narrative,artifact_name,artifact_content,status,error_code,idempotency_key,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?,?,?,?,?)",
                context.tenantId(), context.projectId(), request.jobId(), request.templateId(),
                request.templateVersion(), request.outputFormat().toUpperCase(Locale.ROOT),
                write(request.bindings()), write(request.citations()), request.narrative(),
                artifactName, artifact, status, errorCode, request.idempotencyKey(),
                OffsetDateTime.now(), OffsetDateTime.now());
        audit(context, "REPORT_GENERATED", "report_job", request.jobId(),
                Map.of("status", status, "artifact", artifactName));
        return reportJob(request.jobId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reportJob(String jobId) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select job_id,status,error_code,artifact_name,output_format,citations::text "
                        + "from report_jobs where tenant_id=? and project_id=? and job_id=?",
                context.tenantId(), context.projectId(), jobId);
        if (rows.isEmpty()) throw new ApiException("REPORT_JOB_NOT_FOUND", 404, "report job not found");
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new HashMap<>();
        result.put("jobId", row.get("job_id"));
        result.put("status", row.get("status"));
        result.put("errorCode", row.get("error_code"));
        result.put("artifactName", row.get("artifact_name"));
        result.put("outputFormat", row.get("output_format"));
        result.put("citations", readJson(String.valueOf(row.get("citations"))));
        return result;
    }

    @Transactional(readOnly = true)
    public Artifact artifact(String jobId) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select artifact_name,artifact_content from report_jobs where tenant_id=? and project_id=? and job_id=?",
                context.tenantId(), context.projectId(), jobId);
        if (rows.isEmpty()) throw new ApiException("REPORT_JOB_NOT_FOUND", 404, "report job not found");
        return new Artifact(String.valueOf(rows.get(0).get("artifact_name")),
                (byte[]) rows.get(0).get("artifact_content"));
    }

    @Transactional
    public Map<String, Object> extract(OcrJob request) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> prior = jdbc.queryForList(
                "select job_id from ocr_jobs where tenant_id=? and project_id=? and idempotency_key=?",
                context.tenantId(), context.projectId(), request.idempotencyKey());
        if (!prior.isEmpty()) return ocrJob(String.valueOf(prior.get(0).get("job_id")));
        JsonNode result = ocr.extract(request.documentType(), request.inputText(), request.schema());
        maskSensitive(request.documentType(), result);
        jdbc.update("insert into ocr_jobs "
                        + "(tenant_id,project_id,job_id,document_type,source_object_ref,input_text,extraction_schema,"
                        + "result,adapter,status,idempotency_key,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?,?)",
                context.tenantId(), context.projectId(), request.jobId(), request.documentType(),
                request.sourceObjectRef(), request.inputText(), write(request.schema()), write(result),
                ocr.name(), "COMPLETED", request.idempotencyKey(), OffsetDateTime.now(), OffsetDateTime.now());
        audit(context, "OCR_EXTRACTION_COMPLETED", "ocr_job", request.jobId(),
                Map.of("documentType", request.documentType(), "adapter", ocr.name()));
        return ocrJob(request.jobId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ocrJob(String jobId) {
        RequestContext context = RequestContextHolder.get();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select job_id,document_type,source_object_ref,result::text,adapter,status "
                        + "from ocr_jobs where tenant_id=? and project_id=? and job_id=?",
                context.tenantId(), context.projectId(), jobId);
        if (rows.isEmpty()) throw new ApiException("OCR_JOB_NOT_FOUND", 404, "OCR job not found");
        Map<String, Object> row = rows.get(0);
        return Map.of("jobId", row.get("job_id"), "documentType", row.get("document_type"),
                "sourceObjectRef", row.get("source_object_ref"), "result",
                readJson(String.valueOf(row.get("result"))), "adapter", row.get("adapter"),
                "status", row.get("status"));
    }

    private byte[] renderDocx(String text, JsonNode bindings) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String line : text.split("\\R")) document.createParagraph().createRun().setText(line);
            if (bindings.isObject()) {
                var table = document.createTable(1, 2);
                bindings.fields().forEachRemaining(entry -> {
                    var row = table.createRow();
                    row.getCell(0).setText(entry.getKey());
                    row.getCell(1).setText(entry.getValue().asText());
                });
                table.removeRow(0);
            }
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ApiException("DOCX_RENDER_FAILED", 500, exception.getMessage());
        }
    }

    private byte[] convertPdf(byte[] docx, String jobId) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("sitepilot-report-");
            Path input = directory.resolve(jobId + ".docx");
            Files.write(input, docx);
            Process process = new ProcessBuilder("libreoffice", "--headless", "--convert-to", "pdf",
                    "--outdir", directory.toString(), input.toString()).redirectErrorStream(true).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new ApiException("PDF_CONVERTER_UNAVAILABLE", 503, "LibreOffice conversion failed");
            }
            Path pdf = directory.resolve(jobId + ".pdf");
            if (!Files.exists(pdf)) throw new ApiException("PDF_CONVERTER_UNAVAILABLE", 503, "PDF missing");
            return Files.readAllBytes(pdf);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException("PDF_CONVERTER_UNAVAILABLE", 503, exception.getMessage());
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        }
    }

    private OffsetDateTime offsetDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset;
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        throw new IllegalStateException("unsupported timestamp value: " + value.getClass().getName());
    }

    private Map<String, Object> reviewTemplate(RequestContext context, String id, String version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from review_templates where tenant_id=? and project_id=? and template_id=? and template_version=?",
                context.tenantId(), context.projectId(), id, version);
        if (rows.isEmpty()) throw new ApiException("REVIEW_TEMPLATE_NOT_FOUND", 404, "template not found");
        return rows.get(0);
    }

    private Map<String, Object> reportTemplate(RequestContext context, String id, String version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from report_templates where tenant_id=? and project_id=? and template_id=? and template_version=?",
                context.tenantId(), context.projectId(), id, version);
        if (rows.isEmpty()) throw new ApiException("REPORT_TEMPLATE_NOT_FOUND", 404, "template not found");
        return rows.get(0);
    }

    private String knowledgeText(RequestContext context, String documentId) {
        List<String> rows = jdbc.queryForList(
                "select extracted_text from knowledge_documents where tenant_id=? and project_id=? and document_id=?",
                String.class, context.tenantId(), context.projectId(), documentId);
        if (rows.isEmpty()) throw new ApiException("DOCUMENT_NOT_FOUND", 404, "document not found");
        return rows.get(0);
    }

    private void maskSensitive(String type, JsonNode result) {
        if (!"ID_CARD".equalsIgnoreCase(type)) return;
        JsonNode field = result.path("fields").path("id_number");
        if (field.isObject() && field.has("value")) {
            String value = field.path("value").asText();
            ((ObjectNode) field).put("value", value.length() > 8
                    ? value.substring(0, 4) + "**********" + value.substring(value.length() - 4) : "****");
        }
    }

    private void audit(RequestContext context, String action, String type, String id, Object details) {
        audits.save(new AuditLogEntity(context.tenantId(), context.projectId(), context.actor(),
                action, type, id, write(details)));
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    public record ReviewTemplate(String templateId, String templateVersion, String name,
                                 OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                 JsonNode rules) {}
    public record ReviewJob(String jobId, String templateId, String templateVersion,
                            String documentId, JsonNode structuredInput, String idempotencyKey) {}
    public record ReportTemplate(String templateId, String templateVersion, String name,
                                 String templateText, JsonNode variables, JsonNode sections) {}
    public record ReportJob(String jobId, String templateId, String templateVersion,
                            String outputFormat, JsonNode bindings, JsonNode citations,
                            String narrative, String idempotencyKey) {}
    public record OcrJob(String jobId, String documentType, String sourceObjectRef,
                         String inputText, JsonNode schema, String idempotencyKey) {}
    public record Artifact(String name, byte[] content) {}

    private interface OcrAdapter {
        JsonNode extract(String documentType, String inputText, JsonNode schema);
        String name();
    }

    private final class DeterministicOcrAdapter implements OcrAdapter {
        public JsonNode extract(String documentType, String text, JsonNode schema) {
            ObjectNode root = json.createObjectNode();
            root.put("documentType", documentType);
            root.put("sourceTextDigest", Integer.toHexString(text == null ? 0 : text.hashCode()));
            ObjectNode fields = root.putObject("fields");
            if (text == null) text = "";
            Map<String, String> patterns = new HashMap<>();
            patterns.put("id_number", "\\b\\d{17}[0-9Xx]\\b");
            patterns.put("plate_number", "[\\u4e00-\\u9fff][A-Z][A-Z0-9]{5,6}");
            patterns.put("amount", "(?:金额|价税合计|合同金额)[:：]?\\s*([0-9,.]+)");
            patterns.put("party_a", "甲方[:：]?\\s*([^\\n,，]+)");
            patterns.put("party_b", "乙方[:：]?\\s*([^\\n,，]+)");
            List<String> requested = new ArrayList<>();
            if (schema != null && schema.path("properties").isObject()) {
                schema.path("properties").fieldNames().forEachRemaining(requested::add);
            }
            if (requested.isEmpty()) requested.addAll(patterns.keySet());
            for (String name : requested) {
                Pattern pattern = Pattern.compile(patterns.getOrDefault(name,
                        Pattern.quote(name) + "[:：]?\\s*([^\\n,，]+)"));
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    ObjectNode field = fields.putObject(name);
                    field.put("value", matcher.groupCount() > 0 ? matcher.group(1) : matcher.group());
                    field.put("confidence", 0.95);
                    field.put("page", 1);
                    field.set("bbox", json.valueToTree(List.of(0, 0, 0, 0)));
                }
            }
            return root;
        }
        public String name() { return "deterministic-ocr-v1"; }
    }

    private final class HttpOcrAdapter implements OcrAdapter {
        private final String endpoint;
        private final HttpClient client = HttpClient.newHttpClient();
        private HttpOcrAdapter(String endpoint, ObjectMapper ignored) {
            this.endpoint = endpoint.replaceAll("/+$", "");
        }
        public JsonNode extract(String documentType, String inputText, JsonNode schema) {
            try {
                String body = write(Map.of("documentType", documentType,
                        "inputText", inputText == null ? "" : inputText,
                        "schema", schema == null ? json.createObjectNode() : schema));
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/extract"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                return json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
            } catch (Exception exception) {
                throw new ApiException("OCR_ADAPTER_FAILED", 503, exception.getMessage());
            }
        }
        public String name() { return "http-ocr"; }
    }
}
