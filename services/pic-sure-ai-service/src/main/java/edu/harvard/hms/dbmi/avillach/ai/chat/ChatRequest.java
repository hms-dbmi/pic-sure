package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
 * search text, which must be a JSON string (kept as a node so a number or boolean is rejected rather than coerced to text) (the UI sends it
 * to {@code /dictionary} together with {@code facets}).
 *
 * @param message the user's chat message
 * @param conversationId the UI-generated conversation id this turn belongs to
 * @param requestId the UI-generated idempotency key for this turn
 * @param query the researcher's current query object, if any
 * @param facets the researcher's current facet selection, in the slimmed {@link FacetSelection} shape
 * @param search the researcher's current dictionary search text, if any
 * @param openAccess true when the UI is on an open-access page, so the assistant should answer with open-access tools only. Blank or false
 *        means the caller's own access type decides. This can only narrow what a caller gets, never widen it: it is not an authorization
 *        signal, and an open caller who sends false still gets no authorized tool.
 */
@Schema(description = "One researcher chat turn, plus the query, facet selection and dictionary-search state the turn starts from.")
public record ChatRequest(
    @Schema(
        description = "The researcher's chat message.", example = "Which studies have blood pressure measurements?",
        requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ChatRequest.MAX_MESSAGE_LENGTH
    ) @NotBlank @Size(max = ChatRequest.MAX_MESSAGE_LENGTH) String message,
    @Schema(
        description = "Client-generated id of the conversation this turn belongs to.", example = "c4b1f7e2-6a0d-4a7e-9d1b-2f3c8e5a9b10",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String conversationId,
    @Schema(
        description = "Client-generated idempotency key for this turn.", example = "9f2d6c1a-3b8e-4c57-a1d4-7e0b5f6c2a83",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String requestId,
    @Schema(
        description = "The researcher's current query object, if any: a full v3 query. Fields the assistant does not model "
            + "(expectedResultType, authorizationFilters, ...) are accepted and ignored; the rest is validated."
    ) JsonNode query, @Schema(description = "The researcher's current facet selection, if any.") List<FacetSelection> facets,
    @Schema(description = "The researcher's current dictionary search text, if any.", example = "blood pressure") JsonNode search,
    @Schema(
        description = "Set to true when the researcher is on an open-access page: the assistant then uses open-access tools only, even "
            + "for a logged-in researcher. Leave out otherwise. It never grants access."
    ) Boolean openAccess
) {

    /** A request that does not say it is on an open-access page. */
    public ChatRequest(
        String message, String conversationId, String requestId, JsonNode query, List<FacetSelection> facets, JsonNode search
    ) {
        this(message, conversationId, requestId, query, facets, search, null);
    }

    /** @return whether the UI asked for open-access tools only; blank counts as no */
    public boolean openAccessRequested() {
        return Boolean.TRUE.equals(openAccess);
    }

    /** Upper bound on {@code message}, so an oversized prompt is rejected by validation before it reaches the model. */
    public static final int MAX_MESSAGE_LENGTH = 4000;
}
