package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * {@code POST /ai/chat} (reachable externally as {@code POST /picsure/ai/chat} once the outer {@code /picsure} prefix in front of the
 * gateway applies, the same way {@code /operations} on this service's sibling becomes {@code /picsure/operations} -- see
 * {@code pic-sure-gateway}'s {@code application.yml} route table). {@link CallerContext} resolution (401 on a missing/malformed
 * {@code Authorization} header) and {@code @Valid} field validation (400 on a missing required field) both run before
 * {@link ChatOrchestrator#handle} is ever called, so neither Bedrock nor the MCP gateway is touched on a rejected request.
 */
@Tag(name = "AI chat", description = "AI-assisted dictionary search")
@RestController
public class ChatController {

    private final ChatOrchestrator orchestrator;

    public ChatController(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Operation(summary = "Send a chat message")
    @ApiResponse(responseCode = "200", description = "The model's reply and any query, facet or search state the turn changed")
    @ApiResponse(responseCode = "400", description = "A required field is missing or blank")
    @ApiResponse(responseCode = "401", description = "The Authorization header is missing or is not a bearer token")
    @AuditEvent(type = "SEARCH", action = "ai.chat")
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, CallerContext caller) {
        return orchestrator.handle(request, caller);
    }
}
