package com.sitepilot.controlplane.databaseqa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.domain.AuditLogEntity;
import com.sitepilot.controlplane.error.ApiException;
import com.sitepilot.controlplane.repository.AuditLogRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseQaService {
    private static final Set<String> SOURCE_TYPES = Set.of("POSTGRESQL", "MYSQL", "KINGBASE", "BUSINESS_API");
    private static final Pattern TABLES = Pattern.compile("\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditLogRepository audits;
    private final TextToSqlProvider provider;

    public DatabaseQaService(JdbcTemplate jdbc, ObjectMapper json, AuditLogRepository audits,
                             @Value("${sitepilot.llm.endpoint:}") String endpoint,
                             @Value("${sitepilot.llm.model:smart-worksite-chat}") String model) {
        this.jdbc = jdbc;
        this.json = json;
        this.audits = audits;
        this.provider = endpoint.isBlank()
                ? new DeterministicTextToSqlProvider()
                : new QwenTextToSqlProvider(endpoint, model, json);
    }

    @Transactional
    public Map<String, Object> register(RegisterDataSource request) {
        RequestContext context = RequestContextHolder.get();
        String type = request.sourceType().toUpperCase(Locale.ROOT);
        if (!SOURCE_TYPES.contains(type)) throw new ApiException("DATA_SOURCE_TYPE_INVALID", 400, type);
        if (request.credentialRef().isBlank() || request.credentialRef().contains("://")) {
            throw new ApiException("CREDENTIAL_REFERENCE_REQUIRED", 400,
                    "credentialRef must be an opaque server-side reference");
        }
        if (request.queryTimeoutSeconds() < 1 || request.queryTimeoutSeconds() > 60
                || request.maxRows() < 1 || request.maxRows() > 10_000) {
            throw new ApiException("DATA_SOURCE_QUERY_LIMIT_INVALID", 400,
                    "query timeout or max rows is outside the server policy");
        }
        jdbc.update("insert into platform_data_sources "
                        + "(tenant_id,project_id,data_source_id,name,source_type,credential_ref,read_only,"
                        + "allowed_tables,allowed_columns,synonyms,project_column,query_timeout_seconds,"
                        + "max_rows,status,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,true,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb),?,?,?,?,?,?)",
                context.tenantId(), context.projectId(), request.dataSourceId(), request.name(), type,
                request.credentialRef(), write(request.allowedTables()), write(request.allowedColumns()),
                write(request.synonyms()), request.projectColumn(), request.queryTimeoutSeconds(),
                request.maxRows(), "ACTIVE", OffsetDateTime.now(), OffsetDateTime.now());
        return source(request.dataSourceId());
    }

    @Transactional
    public Map<String, Object> snapshot(String dataSourceId, SchemaSnapshot request) {
        RequestContext context = RequestContextHolder.get();
        sourceRow(context, dataSourceId);
        String payload = write(request.schema());
        String digest = "sha256:" + hash(payload);
        jdbc.update("insert into schema_snapshots "
                        + "(tenant_id,project_id,data_source_id,snapshot_version,schema_payload,content_digest,created_at) "
                        + "values (?,?,?,?,cast(? as jsonb),?,?)",
                context.tenantId(), context.projectId(), dataSourceId, request.snapshotVersion(),
                payload, digest, OffsetDateTime.now());
        return Map.of("dataSourceId", dataSourceId, "snapshotVersion", request.snapshotVersion(),
                "contentDigest", digest);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> plan(QueryQuestion request) {
        RequestContext context = RequestContextHolder.get();
        Map<String, Object> source = sourceRow(context, request.dataSourceId());
        JsonNode schema = latestSchema(context, request.dataSourceId());
        String sql = provider.generate(request.question(), schema, readJson(String.valueOf(source.get("synonyms"))));
        ValidatedSql validated = validateAndScope(sql, context.projectId(), source);
        return Map.of("dataSourceId", request.dataSourceId(), "question", request.question(),
                "sql", validated.sql, "tables", validated.tables, "readOnly", true,
                "provider", provider.name());
    }

    @Transactional
    public Map<String, Object> execute(QueryQuestion request) {
        RequestContext context = RequestContextHolder.get();
        Map<String, Object> source = sourceRow(context, request.dataSourceId());
        JsonNode schema = latestSchema(context, request.dataSourceId());
        String proposed = provider.generate(request.question(), schema,
                readJson(String.valueOf(source.get("synonyms"))));
        return executeProposed(context, request.dataSourceId(), proposed, source);
    }

    @Transactional
    public Map<String, Object> executePlan(SqlPlan request) {
        RequestContext context = RequestContextHolder.get();
        Map<String, Object> source = sourceRow(context, request.dataSourceId());
        return executeProposed(context, request.dataSourceId(), request.sql(), source);
    }

    private Map<String, Object> executeProposed(RequestContext context, String dataSourceId,
                                                String proposed, Map<String, Object> source) {
        if (!"CONTROL_PLANE_DATASOURCE".equals(source.get("credential_ref"))) {
            throw new ApiException("DATA_SOURCE_ADAPTER_UNAVAILABLE", 409,
                    "only the configured control-plane PostgreSQL adapter is enabled");
        }
        ValidatedSql validated = validateAndScope(proposed, context.projectId(), source);
        JsonNode allowedColumns = readJson(String.valueOf(source.get("allowed_columns")));
        validateColumns(validated.sql, allowedColumns);
        int timeoutSeconds = ((Number) source.get("query_timeout_seconds")).intValue();
        int maxRows = ((Number) source.get("max_rows")).intValue();
        List<Map<String, Object>> raw = jdbc.query(connection -> {
            var statement = connection.prepareStatement(validated.sql);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            return statement;
        }, new ColumnMapRowMapper());
        List<Map<String, Object>> rows = raw.stream().map(row -> redact(row, allowedColumns)).toList();
        audits.save(new AuditLogEntity(context.tenantId(), context.projectId(), context.actor(),
                "DATABASE_QA_EXECUTED", "platform_data_source", dataSourceId,
                write(Map.of("sqlDigest", "sha256:" + hash(validated.sql), "rowCount", rows.size()))));
        return Map.of("dataSourceId", dataSourceId, "sql", validated.sql,
                "tables", validated.tables, "rows", rows, "rowCount", rows.size(),
                "authoritative", false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> source(String dataSourceId) {
        RequestContext context = RequestContextHolder.get();
        Map<String, Object> row = sourceRow(context, dataSourceId);
        return Map.of("dataSourceId", row.get("data_source_id"), "name", row.get("name"),
                "sourceType", row.get("source_type"), "readOnly", row.get("read_only"),
                "allowedTables", readJson(String.valueOf(row.get("allowed_tables"))),
                "allowedColumns", readJson(String.valueOf(row.get("allowed_columns"))),
                "status", row.get("status"));
    }

    private ValidatedSql validateAndScope(String proposed, String projectId, Map<String, Object> source) {
        String sql = proposed.trim();
        if (sql.contains(";") && !sql.endsWith(";")) {
            throw new ApiException("SQL_MULTIPLE_STATEMENTS_FORBIDDEN", 400, "multiple statements");
        }
        sql = sql.replaceAll(";+\\s*$", "");
        Statement statement;
        try { statement = CCJSqlParserUtil.parse(sql); }
        catch (Exception exception) { throw new ApiException("SQL_PARSE_FAILED", 400, exception.getMessage()); }
        if (!(statement instanceof Select)) {
            throw new ApiException("SQL_READ_ONLY_REQUIRED", 403, "only SELECT is allowed");
        }
        Set<String> tables = new HashSet<>();
        Matcher matcher = TABLES.matcher(sql);
        while (matcher.find()) tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        Set<String> allowed = strings(readJson(String.valueOf(source.get("allowed_tables"))));
        if (tables.isEmpty() || !allowed.containsAll(tables)) {
            throw new ApiException("SQL_TABLE_NOT_ALLOWED", 403, "table not allowed");
        }
        String projectColumn = String.valueOf(source.get("project_column"));
        String predicate = projectColumn + " = '" + projectId.replace("'", "''") + "'";
        String lower = sql.toLowerCase(Locale.ROOT);
        int insertion = sql.length();
        for (String keyword : List.of(" group by ", " order by ", " limit ", " offset ")) {
            int index = lower.indexOf(keyword);
            if (index >= 0) insertion = Math.min(insertion, index);
        }
        if (lower.contains(" where ")) {
            sql = sql.replaceFirst("(?i)\\bwhere\\b", "WHERE " + predicate + " AND ");
        } else {
            sql = sql.substring(0, insertion) + " WHERE " + predicate + sql.substring(insertion);
        }
        int maxRows = ((Number) source.get("max_rows")).intValue();
        Matcher limit = Pattern.compile("(?i)\\blimit\\s+(\\d+)").matcher(sql);
        if (limit.find()) {
            if (Integer.parseInt(limit.group(1)) > maxRows) {
                sql = limit.replaceFirst("LIMIT " + maxRows);
            }
        } else {
            sql += " LIMIT " + maxRows;
        }
        return new ValidatedSql(sql, tables.stream().sorted().toList());
    }

    private void validateColumns(String sql, JsonNode allowedColumns) {
        Set<String> allowed = new HashSet<>();
        allowedColumns.fields().forEachRemaining(entry ->
                entry.getValue().forEach(item -> allowed.add(item.asText().toLowerCase(Locale.ROOT))));
        if (allowed.isEmpty()) return;
        Matcher select = Pattern.compile("(?is)^\\s*select\\s+(.*?)\\s+from\\s+").matcher(sql);
        if (!select.find()) throw new ApiException("SQL_COLUMN_NOT_ALLOWED", 403, "select list unavailable");
        for (String item : splitSelectItems(select.group(1))) {
            String expression = item.trim().replaceFirst("(?i)^distinct\\s+", "");
            if (expression.equals("*") || expression.endsWith(".*")) {
                throw new ApiException("SQL_COLUMN_NOT_ALLOWED", 403, "wildcard columns are forbidden");
            }
            String candidate;
            Matcher alias = Pattern.compile("(?i)\\s+as\\s+([a-zA-Z_][\\w]*)\\s*$").matcher(expression);
            if (alias.find()) {
                candidate = alias.group(1);
            } else if (expression.contains("(")) {
                throw new ApiException("SQL_COLUMN_NOT_ALLOWED", 403, "aggregate expressions require an allowed alias");
            } else {
                candidate = expression.replaceAll(".*\\.", "").replace("\"", "").trim();
            }
            if (!allowed.contains(candidate.toLowerCase(Locale.ROOT))) {
                throw new ApiException("SQL_COLUMN_NOT_ALLOWED", 403, "column not allowed: " + candidate);
            }
        }
    }

    private List<String> splitSelectItems(String value) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '(') depth++;
            if (current == ')') depth--;
            if (current == ',' && depth == 0) {
                items.add(value.substring(start, index));
                start = index + 1;
            }
        }
        items.add(value.substring(start));
        return items;
    }

    private Map<String, Object> redact(Map<String, Object> row, JsonNode allowedColumns) {
        Set<String> globallyAllowed = new HashSet<>();
        allowedColumns.fields().forEachRemaining(entry -> entry.getValue().forEach(item -> globallyAllowed.add(item.asText().toLowerCase(Locale.ROOT))));
        Map<String, Object> result = new HashMap<>();
        row.forEach((key, value) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            if (globallyAllowed.isEmpty() || globallyAllowed.contains(lower)) {
                result.put(key, sensitive(lower) ? "***" : value);
            }
        });
        return result;
    }

    private boolean sensitive(String column) {
        return column.contains("id_card") || column.contains("phone")
                || column.contains("password") || column.contains("secret");
    }

    private Map<String, Object> sourceRow(RequestContext context, String dataSourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from platform_data_sources where tenant_id=? and project_id=? and data_source_id=?",
                context.tenantId(), context.projectId(), dataSourceId);
        if (rows.isEmpty()) throw new ApiException("DATA_SOURCE_NOT_FOUND", 404, "data source not found");
        return rows.get(0);
    }

    private JsonNode latestSchema(RequestContext context, String dataSourceId) {
        List<String> rows = jdbc.queryForList(
                "select schema_payload::text from schema_snapshots where tenant_id=? and project_id=? and data_source_id=? order by created_at desc limit 1",
                String.class, context.tenantId(), context.projectId(), dataSourceId);
        if (rows.isEmpty()) throw new ApiException("SCHEMA_SNAPSHOT_REQUIRED", 409, "schema snapshot required");
        return readJson(rows.get(0));
    }

    private Set<String> strings(JsonNode value) {
        Set<String> result = new HashSet<>();
        value.forEach(item -> result.add(item.asText().toLowerCase(Locale.ROOT)));
        return result;
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public record RegisterDataSource(String dataSourceId, String name, String sourceType,
                                     String credentialRef, List<String> allowedTables,
                                     Map<String, List<String>> allowedColumns,
                                     Map<String, String> synonyms, String projectColumn,
                                     int queryTimeoutSeconds, int maxRows) {}
    public record SchemaSnapshot(String snapshotVersion, JsonNode schema) {}
    public record QueryQuestion(String dataSourceId, String question) {}
    public record SqlPlan(String dataSourceId, String sql) {}
    private record ValidatedSql(String sql, List<String> tables) {}

    private interface TextToSqlProvider {
        String generate(String question, JsonNode schema, JsonNode synonyms);
        String name();
    }

    private static final class DeterministicTextToSqlProvider implements TextToSqlProvider {
        public String generate(String question, JsonNode schema, JsonNode synonyms) {
            String text = question.toLowerCase(Locale.ROOT);
            if (text.contains("高风险") || text.contains("隐患")) {
                return "SELECT risk_level, count(*) AS count FROM safety_inspection GROUP BY risk_level";
            }
            if (text.contains("库存")) {
                return "SELECT material_name, quantity FROM material_inventory ORDER BY quantity DESC";
            }
            if (text.contains("考勤") || text.contains("到场")) {
                return "SELECT worker_name, check_in_status FROM worker_attendance";
            }
            String table = schema.path("tables").isArray() && !schema.path("tables").isEmpty()
                    ? schema.path("tables").get(0).path("name").asText() : "project_info";
            return "SELECT * FROM " + table;
        }
        public String name() { return "deterministic-text-to-sql-v1"; }
    }

    private static final class QwenTextToSqlProvider implements TextToSqlProvider {
        private final String endpoint;
        private final String model;
        private final ObjectMapper json;
        private final HttpClient client = HttpClient.newHttpClient();

        private QwenTextToSqlProvider(String endpoint, String model, ObjectMapper json) {
            this.endpoint = endpoint.replaceAll("/+$", "");
            this.model = model;
            this.json = json;
        }

        public String generate(String question, JsonNode schema, JsonNode synonyms) {
            try {
                String prompt = "Return JSON {sql:string}. Only SELECT. Question=" + question
                        + " Schema=" + schema + " Synonyms=" + synonyms;
                String body = json.writeValueAsString(Map.of("model", model, "temperature", 0,
                        "messages", List.of(Map.of("role", "user", "content", prompt)),
                        "response_format", Map.of("type", "json_object")));
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                JsonNode root = json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
                return json.readTree(root.path("choices").get(0).path("message").path("content").asText())
                        .path("sql").asText();
            } catch (Exception exception) {
                throw new ApiException("TEXT_TO_SQL_PROVIDER_FAILED", 503, exception.getMessage());
            }
        }
        public String name() { return "qwen:" + model; }
    }
}
