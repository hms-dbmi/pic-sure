package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * {@code POST /ai/chat} (reachable externally as {@code POST /picsure/ai/chat} once the outer {@code /picsure} prefix in front of the
 * gateway applies, the same way {@code /operations} on this service's sibling becomes {@code /picsure/operations} -- see
 * {@code pic-sure-gateway}'s {@code application.yml} route table). {@link CallerContext} resolution (401 on a missing/malformed
 * {@code Authorization} header) and {@code @Valid} field validation (400 on a missing required field) both run before
 * {@link ChatOrchestrator#handle} is ever called, so neither Bedrock nor the MCP gateway is touched on a rejected request.
 */
@RestController
public class ChatController {

    private final ChatOrchestrator orchestrator;

    public ChatController(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, CallerContext caller) {
        return orchestrator.handle(request, caller);
    }
}
