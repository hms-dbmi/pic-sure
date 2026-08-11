package edu.harvard.hms.dbmi.avillach.hpds.service;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * HPDS's dictionary-search response: {@code {"results": ..., "searchQuery": "..."}}. Identical on the wire to the retired
 * {@code pic-sure-api-model} {@code SearchResults}, which this replaces.
 *
 * <p><b>Deliberately service-local, and deliberately {@code Object}-typed.</b> {@code results} is a heterogeneous map keyed by
 * {@code "phenotypes"}/{@code "info"} whose values are HPDS's own metadata objects ({@code ColumnMeta}, info-store descriptors) -- a shape
 * only HPDS can produce and only HPDS's dictionary consumers interpret. Modelling it in {@code pic-sure-contracts} would put an untyped
 * {@code Object} into the shared contract module and pull HPDS's internal metadata into everyone else's compile path; the query-service
 * ingress that fronts this endpoint treats the payload as passthrough and has its own equally-local mirror of this shape.
 */
@Schema(description = "Results of a dictionary search, plus the term that produced them")
public record SearchResults(
    @Schema(description = "Matching concepts, keyed by \"phenotypes\" and \"info\"") Object results,
    @Schema(description = "The search term these results answer") String searchQuery
) {
}
