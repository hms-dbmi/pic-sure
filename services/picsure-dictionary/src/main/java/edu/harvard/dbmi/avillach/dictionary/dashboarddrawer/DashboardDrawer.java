package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The expanded detail panel for a single study on the dashboard")
public record DashboardDrawer(
    @Schema(description = "Internal dataset id this drawer describes") int datasetId,
    @Schema(description = "Full study name") String studyFullname,
    @Schema(description = "Short study abbreviation, e.g. COPDGene") String studyAbbreviation,
    @Schema(description = "Consent groups available within this study") List<String> consentGroups,
    @Schema(description = "Prose summary of the study") String studySummary,
    @Schema(description = "Research areas the study focuses on") List<String> studyFocus,
    @Schema(description = "Study design, e.g. Case-Control") String studyDesign,
    @Schema(description = "Funding or sponsoring organization") String sponsor
) {
}
