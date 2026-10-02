package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Detail about one study, shown when its dashboard row is opened.")
public record DashboardDrawer(
    @Schema(
        description = "Numeric id of the dataset in the dictionary database.", example = "17", requiredMode = Schema.RequiredMode.REQUIRED
    ) int datasetId, @Schema(description = "Full name of the study.", example = "Framingham Cohort") String studyFullname,
    @Schema(description = "Short name of the study.", example = "FHS") String studyAbbreviation,
    @Schema(
        description = "Descriptions of the study's consent groups. Empty when it has none.",
        example = "[\"Health/Medical/Biomedical (IRB, MDS) (HMB-IRB-MDS)\"]", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<String> consentGroups,
    @Schema(
        description = "Summary of the study.",
        example = "The Framingham Heart Study follows cardiovascular disease across three generations of participants."
    ) String studySummary,
    @Schema(
        description = "The conditions or topics the study focuses on. Empty when none are recorded.",
        example = "[\"Cardiovascular Disease\"]", requiredMode = Schema.RequiredMode.REQUIRED
    ) List<String> studyFocus,
    @Schema(
        description = "Design of the study. Null when none is recorded.", example = "Prospective Longitudinal Cohort"
    ) String studyDesign,
    @Schema(
        description = "Organization that sponsors the study. Null when none is recorded.",
        example = "National Heart, Lung, and Blood Institute"
    ) String sponsor
) {
}
