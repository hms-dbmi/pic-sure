package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchRequest(@Schema(description = "Free-text search term") String query) {
}
