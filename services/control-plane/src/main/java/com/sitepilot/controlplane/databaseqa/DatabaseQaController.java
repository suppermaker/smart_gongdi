package com.sitepilot.controlplane.databaseqa;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/data-sources")
public class DatabaseQaController {
    private final DatabaseQaService service;

    public DatabaseQaController(DatabaseQaService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@RequestBody DatabaseQaService.RegisterDataSource request) {
        return service.register(request);
    }

    @GetMapping("/{dataSourceId}")
    Map<String, Object> source(@PathVariable String dataSourceId) {
        return service.source(dataSourceId);
    }

    @PostMapping("/{dataSourceId}/schema-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> snapshot(@PathVariable String dataSourceId,
                                 @RequestBody DatabaseQaService.SchemaSnapshot request) {
        return service.snapshot(dataSourceId, request);
    }

    @PostMapping("/query-plan")
    Map<String, Object> plan(@RequestBody DatabaseQaService.QueryQuestion request) {
        return service.plan(request);
    }

    @PostMapping("/query")
    Map<String, Object> execute(@RequestBody DatabaseQaService.QueryQuestion request) {
        return service.execute(request);
    }

    @PostMapping("/sql-plan/execute")
    Map<String, Object> executePlan(@RequestBody DatabaseQaService.SqlPlan request) {
        return service.executePlan(request);
    }
}
