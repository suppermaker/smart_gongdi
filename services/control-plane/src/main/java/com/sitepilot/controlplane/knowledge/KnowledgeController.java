package com.sitepilot.controlplane.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    private final ObjectMapper json;

    public KnowledgeController(KnowledgeService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> upload(@RequestParam String documentId,
                               @RequestParam(defaultValue = "1") String documentVersion,
                               @RequestParam(defaultValue = "{}") String metadata,
                               @RequestParam MultipartFile file) throws Exception {
        return service.upload(documentId, documentVersion, file, json.readTree(metadata));
    }

    @GetMapping("/documents")
    List<Map<String, Object>> documents() { return service.listDocuments(); }

    @GetMapping("/documents/{documentId}")
    Map<String, Object> document(@PathVariable String documentId) {
        return service.readDocument(documentId);
    }

    @PostMapping("/documents/{documentId}/reindex")
    Map<String, Object> reindex(@PathVariable String documentId) {
        return service.reindex(documentId);
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String documentId) { service.delete(documentId); }

    @PostMapping("/search")
    List<Map<String, Object>> search(@RequestBody SearchRequest request) {
        return service.search(request.query(), request.limit());
    }

    @PostMapping("/answer")
    Map<String, Object> answer(@RequestBody AnswerRequest request) {
        return service.answer(request.question());
    }

    public record SearchRequest(String query, int limit) {}
    public record AnswerRequest(String question) {}
}
