package com.sitepilot.controlplane.platform;

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
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService service;

    public ConversationController(ConversationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody ConversationService.CreateConversation request) {
        return service.create(request);
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> message(@PathVariable String conversationId,
                                @RequestBody ConversationService.CreateMessage request) {
        return service.message(conversationId, request);
    }

    @GetMapping("/{conversationId}")
    Map<String, Object> read(@PathVariable String conversationId) {
        return service.read(conversationId);
    }
}
