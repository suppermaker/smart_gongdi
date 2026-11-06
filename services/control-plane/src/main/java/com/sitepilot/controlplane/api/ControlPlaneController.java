package com.sitepilot.controlplane.api;

import com.sitepilot.controlplane.api.ApiResponses.ProjectResponse;
import com.sitepilot.controlplane.api.Requests.CreateProject;
import com.sitepilot.controlplane.api.Requests.CreateTask;
import com.sitepilot.controlplane.api.Requests.IngestEvent;
import com.sitepilot.controlplane.api.Requests.RegisterEvidence;
import com.sitepilot.controlplane.api.Requests.TransitionRisk;
import com.sitepilot.controlplane.domain.EvidenceEntity;
import com.sitepilot.controlplane.domain.InvestigationTaskEntity;
import com.sitepilot.controlplane.domain.RiskEntity;
import com.sitepilot.controlplane.service.ControlPlaneService;
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
@RequestMapping("/api/v1")
public class ControlPlaneController {
    private final ControlPlaneService service;

    public ControlPlaneController(ControlPlaneService service) {
        this.service = service;
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse createProject(@Valid @RequestBody CreateProject request) {
        return ProjectResponse.from(service.createProject(request));
    }

    @GetMapping("/projects/current")
    ProjectResponse project() { return ProjectResponse.from(service.project()); }

    @PostMapping("/events/simulated")
    RiskEntity ingest(@Valid @RequestBody IngestEvent request) { return service.ingest(request); }

    @GetMapping("/risks/{id}")
    RiskEntity risk(@PathVariable String id) { return service.risk(id); }

    @PostMapping("/risks/{id}/transitions")
    RiskEntity transition(@PathVariable String id, @Valid @RequestBody TransitionRisk request) {
        return service.transition(id, request);
    }

    @PostMapping("/evidence")
    EvidenceEntity evidence(@Valid @RequestBody RegisterEvidence request) {
        return service.register(request);
    }

    @PostMapping("/investigation-tasks")
    InvestigationTaskEntity task(@Valid @RequestBody CreateTask request) {
        return service.createTask(request);
    }

    @GetMapping("/investigation-tasks/{id}")
    InvestigationTaskEntity task(@PathVariable String id) { return service.task(id); }
}
