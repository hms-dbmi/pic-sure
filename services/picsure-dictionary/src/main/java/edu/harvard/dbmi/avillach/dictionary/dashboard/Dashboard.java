package edu.harvard.dbmi.avillach.dictionary.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "The study dashboard table: its columns in display order and one row per study.")
public record Dashboard(
    @Schema(
        description = "The table's columns in display order.", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<DashboardColumn> columns,
    @Schema(
        description = "One map per study. The keys of each map are the dataElement values of columns, such as abbreviation, name and clinvars, and each value is that study's cell text, empty when the study has none. Deployments configure the columns, so the key set is not fixed.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) List<Map<String, String>> rows
) {
}
