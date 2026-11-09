package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code POST /ai/chat}'s response body: {@code { response, query?, facets?, search? }}. Each of
 * {@code query}/{@code facets}/{@code search} is present only when this turn actually changed it -- most turns are dictionary Q&A with no
 * query involved at all, per {@code PLAN.md}'s Goal section.
 *
 * @param response the model's final chat reply
 * @param query the updated query object, or null if this turn didn't change it
 * @param facets the updated facet selection, or null if this turn didn't change it
 * @param search the updated dictionary-search state, or null if this turn didn't change it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The model's reply to a chat turn, plus any query, facet or search state the turn changed.")
public record ChatResponse(
    @Schema(
        description = "The model's final chat reply.", example = "Three studies include blood pressure measurements.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String response,
    @Schema(description = "The updated query object. Absent when the turn did not change it.") JsonNode query,
    @Schema(description = "The updated facet selection. Absent when the turn did not change it.") List<FacetSelection> facets,
    @Schema(description = "The updated dictionary-search state. Absent when the turn did not change it.") JsonNode search
) {

    /**
     * A reply with no query/facets/search change -- the common case (prose-only dictionary Q&A).
     *
     * @param response the model's final chat reply
     * @return the response with every optional field null
     */
    public static ChatResponse textOnly(String response) {
        return new ChatResponse(response, null, null, null);
    }
}
