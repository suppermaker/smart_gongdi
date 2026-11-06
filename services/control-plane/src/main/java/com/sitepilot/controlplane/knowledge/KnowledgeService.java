package com.sitepilot.controlplane.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitepilot.controlplane.context.RequestContext;
import com.sitepilot.controlplane.context.RequestContextHolder;
import com.sitepilot.controlplane.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/tiff");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EmbeddingProvider embeddings;

    public KnowledgeService(JdbcTemplate jdbc, ObjectMapper json,
                            @Value("${sitepilot.embedding.endpoint:}") String endpoint,
                            @Value("${sitepilot.embedding.model:smart-worksite-embedding}") String model) {
        this.jdbc = jdbc;
        this.json = json;
        this.embeddings = endpoint.isBlank()
                ? new DeterministicEmbeddingProvider()
                : new HttpEmbeddingProvider(endpoint, model, json);
    }

    @Transactional
    public Map<String, Object> upload(String documentId, String documentVersion,
                                      MultipartFile file, JsonNode metadata) {
        RequestContext context = RequestContextHolder.get();
        byte[] content;
        try { content = file.getBytes(); }
        catch (Exception exception) { throw new ApiException("DOCUMENT_READ_FAILED", 400, exception.getMessage()); }
        if (content.length == 0 || content.length > 25 * 1024 * 1024) {
            throw new ApiException("DOCUMENT_SIZE_INVALID", 400, "document must be 1 byte to 25 MB");
        }
        String fileName = file.getOriginalFilename() == null ? documentId : file.getOriginalFilename();
        String mediaType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String digest = "sha256:" + hash(content);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select document_id from knowledge_documents where tenant_id=? and project_id=? and content_digest=?",
                context.tenantId(), context.projectId(), digest);
        if (!existing.isEmpty()) return readDocument(String.valueOf(existing.get(0).get("document_id")));

        ParseResult parsed = parse(fileName, mediaType, content);
        String status = parsed.ocrRequired ? "OCR_REQUIRED" : "INDEXED";
        String summary = parsed.text.length() > 500 ? parsed.text.substring(0, 500) : parsed.text;
        jdbc.update("insert into knowledge_documents "
                        + "(tenant_id,project_id,document_id,file_name,media_type,source_ref,document_version,"
                        + "content_digest,status,metadata,object_content,extracted_text,summary,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?)",
                context.tenantId(), context.projectId(), documentId, fileName, mediaType,
                "db-object:" + documentId, documentVersion, digest, status,
                write(metadata == null ? json.createObjectNode() : metadata), content,
                parsed.text, summary, OffsetDateTime.now(), OffsetDateTime.now());
        if (!parsed.ocrRequired) index(context, documentId, parsed.sections);
        return readDocument(documentId);
    }

    @Transactional
    public Map<String, Object> reindex(String documentId) {
        RequestContext context = RequestContextHolder.get();
        Map<String, Object> document = documentRow(context, documentId);
        byte[] content = (byte[]) document.get("object_content");
        ParseResult parsed = parse(String.valueOf(document.get("file_name")),
                String.valueOf(document.get("media_type")), content);
        jdbc.update("delete from knowledge_chunks where tenant_id=? and project_id=? and document_id=?",
                context.tenantId(), context.projectId(), documentId);
        if (!parsed.ocrRequired) index(context, documentId, parsed.sections);
        jdbc.update("update knowledge_documents set status=?,extracted_text=?,summary=?,updated_at=?,version=version+1 "
                        + "where tenant_id=? and project_id=? and document_id=?",
                parsed.ocrRequired ? "OCR_REQUIRED" : "INDEXED", parsed.text,
                parsed.text.length() > 500 ? parsed.text.substring(0, 500) : parsed.text,
                OffsetDateTime.now(), context.tenantId(), context.projectId(), documentId);
        return readDocument(documentId);
    }

    @Transactional
    public void delete(String documentId) {
        RequestContext context = RequestContextHolder.get();
        jdbc.update("delete from knowledge_chunks where tenant_id=? and project_id=? and document_id=?",
                context.tenantId(), context.projectId(), documentId);
        int removed = jdbc.update("delete from knowledge_documents where tenant_id=? and project_id=? and document_id=?",
                context.tenantId(), context.projectId(), documentId);
        if (removed == 0) throw new ApiException("DOCUMENT_NOT_FOUND", 404, "document not found");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDocuments() {
        RequestContext context = RequestContextHolder.get();
        return jdbc.queryForList("select document_id as \"documentId\",file_name as \"fileName\","
                        + "media_type as \"mediaType\",document_version as \"documentVersion\","
                        + "content_digest as \"contentDigest\",status,summary,version "
                        + "from knowledge_documents where tenant_id=? and project_id=? order by created_at desc",
                context.tenantId(), context.projectId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> readDocument(String documentId) {
        RequestContext context = RequestContextHolder.get();
        Map<String, Object> row = documentRow(context, documentId);
        Integer chunks = jdbc.queryForObject("select count(*) from knowledge_chunks where tenant_id=? and project_id=? and document_id=?",
                Integer.class, context.tenantId(), context.projectId(), documentId);
        return Map.of("documentId", documentId, "fileName", row.get("file_name"),
                "mediaType", row.get("media_type"), "documentVersion", row.get("document_version"),
                "contentDigest", row.get("content_digest"), "status", row.get("status"),
                "summary", row.get("summary") == null ? "" : row.get("summary"),
                "chunkCount", chunks == null ? 0 : chunks);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String query, int limit) {
        RequestContext context = RequestContextHolder.get();
        List<Double> queryVector = embeddings.embed(List.of(query)).get(0);
        Set<String> queryTerms = terms(query);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select chunk_id,document_id,source_location,content,embedding::text,metadata::text "
                        + "from knowledge_chunks where tenant_id=? and project_id=?",
                context.tenantId(), context.projectId());
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String content = String.valueOf(row.get("content"));
            Set<String> chunkTerms = terms(content);
            double lexical = queryTerms.isEmpty() ? 0.0
                    : (double) queryTerms.stream().filter(chunkTerms::contains).count() / queryTerms.size();
            List<Double> vector = doubles(readJson(String.valueOf(row.get("embedding"))));
            double score = 0.4 * lexical + 0.6 * cosine(queryVector, vector);
            Map<String, Object> hit = new HashMap<>();
            hit.put("chunkId", row.get("chunk_id"));
            hit.put("documentId", row.get("document_id"));
            hit.put("sourceLocation", row.get("source_location"));
            hit.put("quote", content.length() > 500 ? content.substring(0, 500) : content);
            hit.put("score", Math.round(score * 1_000_000d) / 1_000_000d);
            Map<String, Object> doc = documentRow(context, String.valueOf(row.get("document_id")));
            hit.put("documentVersion", doc.get("document_version"));
            hits.add(hit);
        }
        hits.sort(Comparator.comparingDouble(item -> -((Number) item.get("score")).doubleValue()));
        return hits.subList(0, Math.min(Math.max(1, limit), hits.size()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> answer(String question) {
        List<Map<String, Object>> hits = search(question, 5);
        if (hits.isEmpty() || ((Number) hits.get(0).get("score")).doubleValue() < 0.15) {
            return Map.of("status", "CLARIFICATION_REQUIRED", "answer",
                    "没有找到足够的项目知识证据，请补充文档或明确问题。", "citations", List.of());
        }
        String answer = hits.stream().map(hit -> String.valueOf(hit.get("quote")))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return Map.of("status", "ANSWERED", "answer", answer, "citations", hits,
                "authoritative", false);
    }

    private void index(RequestContext context, String documentId, List<Section> sections) {
        List<Chunk> chunks = chunks(documentId, sections);
        List<List<Double>> vectors = embeddings.embed(chunks.stream().map(Chunk::text).toList());
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            jdbc.update("insert into knowledge_chunks "
                            + "(tenant_id,project_id,document_id,chunk_id,source_location,content,token_estimate,"
                            + "embedding,embedding_model,metadata,created_at) "
                            + "values (?,?,?,?,?,?,?,cast(? as jsonb),?,cast(? as jsonb),?)",
                    context.tenantId(), context.projectId(), documentId, chunk.id, chunk.location,
                    chunk.text, Math.max(1, chunk.text.length() / 4), write(vectors.get(index)),
                    embeddings.model(), "{}", OffsetDateTime.now());
        }
    }

    private List<Chunk> chunks(String documentId, List<Section> sections) {
        List<Chunk> chunks = new ArrayList<>();
        int sequence = 0;
        for (Section section : sections) {
            String[] paragraphs = section.text.split("\\R+");
            StringBuilder current = new StringBuilder();
            for (String paragraph : paragraphs) {
                if (paragraph.isBlank()) continue;
                if (current.length() > 0 && current.length() + paragraph.length() > 1200) {
                    sequence++;
                    chunks.add(new Chunk(documentId + ":chunk:" + sequence, section.location, current.toString()));
                    current.setLength(0);
                }
                if (current.length() > 0) current.append('\n');
                current.append(paragraph.trim());
            }
            if (current.length() > 0) {
                sequence++;
                chunks.add(new Chunk(documentId + ":chunk:" + sequence, section.location, current.toString()));
            }
        }
        return chunks;
    }

    private ParseResult parse(String fileName, String mediaType, byte[] content) {
        String suffix = fileName.toLowerCase(Locale.ROOT);
        try {
            if (IMAGE_TYPES.contains(mediaType) || suffix.matches(".*\\.(png|jpg|jpeg|webp|tif|tiff)$")) {
                return new ParseResult("", List.of(), true);
            }
            if (suffix.endsWith(".txt") || suffix.endsWith(".md") || suffix.endsWith(".csv")) {
                String text = new String(content, StandardCharsets.UTF_8);
                return new ParseResult(text, List.of(new Section("file", text)), false);
            }
            if (suffix.endsWith(".docx")) {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
                    List<Section> sections = new ArrayList<>();
                    for (int i = 0; i < document.getParagraphs().size(); i++) {
                        String text = document.getParagraphs().get(i).getText();
                        if (!text.isBlank()) sections.add(new Section("paragraph:" + (i + 1), text));
                    }
                    return combined(sections);
                }
            }
            if (suffix.endsWith(".pptx")) {
                try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(content))) {
                    List<Section> sections = new ArrayList<>();
                    for (int i = 0; i < show.getSlides().size(); i++) {
                        String text = show.getSlides().get(i).getShapes().stream()
                                .filter(shape -> shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape)
                                .map(shape -> ((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText())
                                .reduce((left, right) -> left + "\n" + right).orElse("");
                        if (!text.isBlank()) sections.add(new Section("slide:" + (i + 1), text));
                    }
                    return combined(sections);
                }
            }
            if (suffix.endsWith(".xlsx")) {
                try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
                    List<Section> sections = new ArrayList<>();
                    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                        var sheet = workbook.getSheetAt(i);
                        StringBuilder text = new StringBuilder();
                        sheet.forEach(row -> {
                            if (text.length() > 0) text.append('\n');
                            row.forEach(cell -> text.append(cell.toString()).append(" | "));
                        });
                        if (!text.isEmpty()) sections.add(new Section("sheet:" + sheet.getSheetName(), text.toString()));
                    }
                    return combined(sections);
                }
            }
            if (suffix.endsWith(".pdf")) {
                try (var document = Loader.loadPDF(content)) {
                    List<Section> sections = new ArrayList<>();
                    for (int page = 1; page <= document.getNumberOfPages(); page++) {
                        PDFTextStripper stripper = new PDFTextStripper();
                        stripper.setStartPage(page);
                        stripper.setEndPage(page);
                        String text = stripper.getText(document).trim();
                        if (!text.isBlank()) sections.add(new Section("page:" + page, text));
                    }
                    if (sections.isEmpty()) return new ParseResult("", List.of(), true);
                    return combined(sections);
                }
            }
        } catch (Exception exception) {
            throw new ApiException("DOCUMENT_PARSE_FAILED", 400, exception.getMessage());
        }
        throw new ApiException("DOCUMENT_FORMAT_UNSUPPORTED", 400, fileName);
    }

    private ParseResult combined(List<Section> sections) {
        return new ParseResult(sections.stream().map(Section::text)
                .reduce((left, right) -> left + "\n" + right).orElse(""), sections, false);
    }

    private Map<String, Object> documentRow(RequestContext context, String documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from knowledge_documents where tenant_id=? and project_id=? and document_id=?",
                context.tenantId(), context.projectId(), documentId);
        if (rows.isEmpty()) throw new ApiException("DOCUMENT_NOT_FOUND", 404, "document not found");
        return rows.get(0);
    }

    private Set<String> terms(String value) {
        Set<String> result = new java.util.HashSet<>();
        var matcher = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]{2}").matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private double cosine(List<Double> left, List<Double> right) {
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            dot += left.get(i) * right.get(i);
            a += left.get(i) * left.get(i);
            b += right.get(i) * right.get(i);
        }
        return a == 0 || b == 0 ? 0 : dot / (Math.sqrt(a) * Math.sqrt(b));
    }

    private List<Double> doubles(JsonNode node) {
        List<Double> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asDouble()));
        return values;
    }

    private JsonNode readJson(String value) {
        try { return json.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private interface EmbeddingProvider {
        List<List<Double>> embed(List<String> texts);
        String model();
    }

    private static final class DeterministicEmbeddingProvider implements EmbeddingProvider {
        public List<List<Double>> embed(List<String> texts) {
            return texts.stream().map(text -> {
                double[] vector = new double[32];
                for (int i = 0; i < text.length(); i++) vector[Math.floorMod(text.charAt(i), vector.length)] += 1;
                double norm = Math.sqrt(java.util.Arrays.stream(vector).map(value -> value * value).sum());
                List<Double> result = new ArrayList<>();
                for (double value : vector) result.add(norm == 0 ? 0 : value / norm);
                return result;
            }).toList();
        }
        public String model() { return "deterministic-hash-v1"; }
    }

    private static final class HttpEmbeddingProvider implements EmbeddingProvider {
        private final String endpoint;
        private final String model;
        private final ObjectMapper json;
        private final HttpClient client = HttpClient.newHttpClient();

        private HttpEmbeddingProvider(String endpoint, String model, ObjectMapper json) {
            this.endpoint = endpoint.replaceAll("/+$", "");
            this.model = model;
            this.json = json;
        }

        public List<List<Double>> embed(List<String> texts) {
            try {
                String payload = json.writeValueAsString(Map.of("model", model, "input", texts));
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/v1/embeddings"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                JsonNode root = json.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
                List<List<Double>> result = new ArrayList<>();
                root.path("data").forEach(item -> {
                    List<Double> vector = new ArrayList<>();
                    item.path("embedding").forEach(value -> vector.add(value.asDouble()));
                    result.add(vector);
                });
                return result;
            } catch (Exception exception) {
                throw new ApiException("EMBEDDING_SERVICE_FAILED", 503, exception.getMessage());
            }
        }
        public String model() { return model; }
    }

    private record Section(String location, String text) {}
    private record Chunk(String id, String location, String text) {}
    private record ParseResult(String text, List<Section> sections, boolean ocrRequired) {}
}
