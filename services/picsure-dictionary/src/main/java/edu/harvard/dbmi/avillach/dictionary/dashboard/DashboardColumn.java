package edu.harvard.dbmi.avillach.dictionary.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One column of the study dashboard")
public record DashboardColumn(
    @Schema(description = "Key this column's value is stored under in each dashboard row") String dataElement,
    @Schema(description = "Human-readable column heading") String label
) {
}
