package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /ai/chat}'s request body, per {@code PLAN.md}'s Transport section: {@code { message, conversationId, requestId, query?,
 * facets?, search? }}.
 *
 * <p>There is deliberately no {@code consents} field. The UI's own {@code DictionarySearchRequest.consents} is fine for the UI's direct
 * dictionary call, but access control here must stay solely a function of the caller's JWT (see {@link CallerContext}) -- a client-supplied
 * consents list must never be accepted as an authorization signal. Omitting the field from this type means Jackson drops it from the
 * request body on arrival (Spring Boot leaves {@code FAIL_ON_UNKNOWN_PROPERTIES} off by default), so "removed before any downstream call"
 * is a consequence of this shape, not something a filter has to strip.
 *
 * <p>{@code query} and {@code search} are kept as opaque {@link JsonNode} for now: validating them against a real schema is Story 4's job,
 * out of scope while the proposal tool (see {@code McpToolGateway}) is still a stub.
 *
 * @param message the user's chat message
 * @param conversationId the UI-generated conversation id this turn belongs to
 * @param requestId the UI-generated idempotency key for this turn
 * @param query the researcher's current query object, if any
 * @param facets the researcher's current facet selection, in the slimmed {@link FacetSelection} shape
 * @param search the researcher's current dictionary-search state, if any
 */
public record ChatRequest(
    @NotBlank String message,
    @NotBlank String conversationId,
    @NotBlank String requestId,
    JsonNode query,
    List<FacetSelection> facets,
    JsonNode search
) {
}
