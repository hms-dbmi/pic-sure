package edu.harvard.dbmi.avillach.contracts.info;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

public record QueryFormat(
    @Schema(description = "Name of this query format") String name,
    @Schema(description = "What this query format is for") String description,
    @Schema(description = "Free-form resource documentation") Map<String, Object> specification,
    @Schema(description = "Example queries in this format") List<Map<String, Object>> examples
) {
}
