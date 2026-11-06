package com.sitepilot.controlplane.action;

import com.fasterxml.jackson.databind.JsonNode;
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
@RequestMapping("/api/v1/actions")
public class ActionController {
    private final ActionService actions;
    public ActionController(ActionService actions) { this.actions = actions; }
    @PostMapping Map<String, Object> propose(@RequestBody JsonNode value) { return actions.propose(RequestContextHolder.get(), value); }
    @GetMapping("/{id}") Map<String, Object> read(@PathVariable String id) { return actions.read(RequestContextHolder.get(), id); }
    @PostMapping("/{id}/approve") Map<String, Object> approve(@PathVariable String id, @RequestParam long expectedVersion) { return actions.approve(RequestContextHolder.get(), id, expectedVersion); }
    @PostMapping("/{id}/execute") Map<String, Object> execute(@PathVariable String id, @RequestParam long expectedVersion) { return actions.execute(RequestContextHolder.get(), id, expectedVersion); }
    @PostMapping("/{id}/observe") Map<String, Object> observe(@PathVariable String id, @RequestParam long expectedVersion, @RequestBody JsonNode value) { return actions.observeAndVerify(RequestContextHolder.get(), id, expectedVersion, value); }
}
