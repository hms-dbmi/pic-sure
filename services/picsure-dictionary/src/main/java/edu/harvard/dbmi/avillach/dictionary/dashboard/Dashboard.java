package edu.harvard.dbmi.avillach.dictionary.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * The rows stay untyped on purpose: which columns exist is deployment configuration ({@code dashboard.columns}), so a row's keys are the
 * configured {@link DashboardColumn#dataElement()} values and cannot be modelled as fields.
 */
@Schema(description = "A configuration-driven table of studies: the columns to render, and the rows to render in them")
public record Dashboard(
    @Schema(description = "Columns to render, in order") List<DashboardColumn> columns,
    @Schema(description = "One map per study, keyed by the dataElement of the columns above") List<Map<String, String>> rows
) {
}
