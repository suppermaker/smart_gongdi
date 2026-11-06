package com.sitepilot.controlplane.platform;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tasks")
public class PlatformTaskController {
    private final PlatformTaskService service;
    private final PlatformExecutionService execution;

    public PlatformTaskController(PlatformTaskService service,
                                  PlatformExecutionService execution) {
        this.service = service;
        this.execution = execution;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PlatformContracts.PlatformTaskResponse create(
            @Valid @RequestBody PlatformContracts.CreatePlatformTask request) {
        return service.create(request);
    }

    @GetMapping("/{taskId}")
    PlatformContracts.PlatformTaskResponse read(@PathVariable String taskId) {
        return service.read(taskId);
    }

    @PostMapping("/{taskId}/execute")
    PlatformContracts.PlatformTaskResponse execute(
            @PathVariable String taskId,
            @RequestBody PlatformExecutionService.ExecutionRequest request) {
        return execution.execute(taskId, request, service);
    }
}
