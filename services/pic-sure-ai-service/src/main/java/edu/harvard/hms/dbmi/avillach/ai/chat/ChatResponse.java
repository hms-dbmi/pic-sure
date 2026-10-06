package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

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
public record ChatResponse(String response, JsonNode query, List<FacetSelection> facets, JsonNode search) {

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
