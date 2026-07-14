package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import java.util.List;

public record DashboardDrawer(
    int datasetId, String studyFullname, String studyAbbreviation, List<String> consentGroups, String studySummary, List<String> studyFocus,
    String studyDesign, String sponsor
) {
}
