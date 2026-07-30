package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A free-text search over the concepts exposed by a resource")
public record SearchRequest(@Schema(description = "Free-text search term") String query) {
}
