package com.sitepilot.controlplane.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitepilot.contracts.GraphDelta;
import com.sitepilot.controlplane.context.RequestContextHolder;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/investigation-tasks/{taskId}/graph")
public class GraphController {
    private final GraphMergeService graphs;
    public GraphController(GraphMergeService graphs) { this.graphs = graphs; }

    @GetMapping
    Map<String, Object> read(@PathVariable String taskId) {
        return graphs.read(RequestContextHolder.get(), taskId);
    }

    @PostMapping("/deltas")
    Map<String, Object> merge(@PathVariable String taskId, @RequestParam int expectedRevision,
                              @RequestBody GraphDelta delta) {
        return graphs.merge(RequestContextHolder.get(), taskId, expectedRevision, delta);
    }

    @PostMapping("/completion-gate")
    Map<String, Object> gate(@PathVariable String taskId, @RequestBody JsonNode request) {
        return graphs.completionGate(RequestContextHolder.get(), taskId, request);
    }
}
