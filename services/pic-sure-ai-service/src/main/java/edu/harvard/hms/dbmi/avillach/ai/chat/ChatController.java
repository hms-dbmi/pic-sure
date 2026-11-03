package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.ai.chat.TurnResultStore.TurnResult;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
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
 *
 * <p>Each {@code requestId} runs at most once per caller ({@link TurnResultStore}): sending it again returns the stored response without
 * calling the model, and {@code GET /ai/chat/{requestId}} fetches it. Both find only the caller's own turns, keyed by
 * {@link CallerContext#owner()}.
 */
@Tag(name = "AI chat", description = "AI-assisted dictionary search")
@RestController
public class ChatController {

    private final ChatOrchestrator orchestrator;
    private final TurnResultStore results;

    public ChatController(ChatOrchestrator orchestrator, TurnResultStore results) {
        this.orchestrator = orchestrator;
        this.results = results;
    }

    @Operation(summary = "Send a chat message")
    @ApiResponse(responseCode = "200", description = "The model's reply and any query, facet or search state the turn changed")
    @ApiResponse(responseCode = "400", description = "A required field is missing or blank")
    @ApiResponse(responseCode = "401", description = "The Authorization header is missing or is not a bearer token")
    @ApiResponse(responseCode = "409", description = "A message with this requestId is still being answered; fetch it with GET")
    @AuditEvent(type = "SEARCH", action = "ai.chat")
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, CallerContext caller) {
        TurnResultStore.Key key = new TurnResultStore.Key(caller.owner(), request.requestId());
        Optional<TurnResult> earlier = results.claim(key);
        if (earlier.isPresent()) {
            if (earlier.get().running()) {
                throw new PicsureException(
                    HttpStatus.CONFLICT, "turn_in_progress",
                    "This message is still being answered. Fetch the reply with GET /chat/{requestId}."
                );
            }
            return earlier.get().response();
        }
        try {
            ChatResponse response = orchestrator.handle(request, caller);
            results.complete(key, response);
            return response;
        } catch (RuntimeException e) {
            results.release(key);
            throw e;
        }
    }

    @Operation(summary = "Fetch the reply to a chat message sent earlier")
    @ApiResponse(responseCode = "200", description = "The reply, as the original request would have returned it")
    @ApiResponse(responseCode = "202", description = "The message is still being answered; try again shortly")
    @ApiResponse(responseCode = "401", description = "The Authorization header is missing or is not a bearer token")
    @ApiResponse(responseCode = "404", description = "No reply is stored: the message never arrived, failed, or its reply has expired")
    @AuditEvent(type = "SEARCH", action = "ai.chat.result")
    @GetMapping("/chat/{requestId}")
    public ResponseEntity<ChatResponse> result(@PathVariable String requestId, CallerContext caller) {
        TurnResult result = results.find(new TurnResultStore.Key(caller.owner(), requestId)).orElseThrow(
            () -> new PicsureException(HttpStatus.NOT_FOUND, "turn_not_found", "No reply is stored for this message. Send it again.")
        );
        return result.running() ? ResponseEntity.accepted().build() : ResponseEntity.ok(result.response());
    }
}
