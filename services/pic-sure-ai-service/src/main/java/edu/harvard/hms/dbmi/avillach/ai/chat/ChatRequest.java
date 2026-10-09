package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * <p>{@code query} is kept as an opaque {@link JsonNode} and validated by {@link ChatStateValidator}; {@code search} is the dictionary
 * search text, a plain string (the UI sends it to {@code /dictionary} together with {@code facets}).
 *
 * @param message the user's chat message
 * @param conversationId the UI-generated conversation id this turn belongs to
 * @param requestId the UI-generated idempotency key for this turn
 * @param query the researcher's current query object, if any
 * @param facets the researcher's current facet selection, in the slimmed {@link FacetSelection} shape
 * @param search the researcher's current dictionary search text, if any
 */
@Schema(description = "One researcher chat turn, plus the query, facet selection and dictionary-search state the turn starts from.")
public record ChatRequest(
    @Schema(
        description = "The researcher's chat message.", example = "Which studies have blood pressure measurements?",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String message,
    @Schema(
        description = "Client-generated id of the conversation this turn belongs to.", example = "c4b1f7e2-6a0d-4a7e-9d1b-2f3c8e5a9b10",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String conversationId,
    @Schema(
        description = "Client-generated idempotency key for this turn.", example = "9f2d6c1a-3b8e-4c57-a1d4-7e0b5f6c2a83",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String requestId,
    @Schema(description = "The researcher's current query object, if any. Passed through unvalidated.") JsonNode query,
    @Schema(description = "The researcher's current facet selection, if any.") List<FacetSelection> facets,
    @Schema(description = "The researcher's current dictionary-search state, if any. Passed through unvalidated.") String search
) {
}
