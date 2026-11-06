package com.sitepilot.controlplane.application;

import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ContractApplicationController {
    private final ContractApplicationService service;

    public ContractApplicationController(ContractApplicationService service) {
        this.service = service;
    }

    @PostMapping("/review/templates")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createReviewTemplate(
            @RequestBody ContractApplicationService.ReviewTemplate request) {
        return service.createReviewTemplate(request);
    }

    @PostMapping("/review/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> review(@RequestBody ContractApplicationService.ReviewJob request) {
        return service.review(request);
    }

    @GetMapping("/review/jobs/{jobId}")
    Map<String, Object> reviewJob(@PathVariable String jobId) {
        return service.reviewJob(jobId);
    }

    @PostMapping("/reports/templates")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createReportTemplate(
            @RequestBody ContractApplicationService.ReportTemplate request) {
        return service.createReportTemplate(request);
    }

    @PostMapping("/reports/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> report(@RequestBody ContractApplicationService.ReportJob request) {
        return service.generateReport(request);
    }

    @GetMapping("/reports/jobs/{jobId}")
    Map<String, Object> reportJob(@PathVariable String jobId) {
        return service.reportJob(jobId);
    }

    @GetMapping("/reports/jobs/{jobId}/artifact")
    ResponseEntity<byte[]> artifact(@PathVariable String jobId) {
        ContractApplicationService.Artifact artifact = service.artifact(jobId);
        MediaType mediaType = artifact.name().endsWith(".pdf")
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.name()).build().toString())
                .body(artifact.content());
    }

    @PostMapping("/ocr/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> ocr(@RequestBody ContractApplicationService.OcrJob request) {
        return service.extract(request);
    }

    @GetMapping("/ocr/jobs/{jobId}")
    Map<String, Object> ocrJob(@PathVariable String jobId) {
        return service.ocrJob(jobId);
    }
}
