package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardDrawerTest {

    @Test
    void testDashboardDrawerInstantiation() {
        DashboardDrawer dashboardDrawer = new DashboardDrawer(
            1, "Full Name", "Abbr", List.of("Consent1", "Consent2"), "Summary", List.of("Focus1", "Focus2"), "Design", "Sponsor"
        );

        assertEquals(1, dashboardDrawer.datasetId());
        assertEquals("Full Name", dashboardDrawer.studyFullname());
        assertEquals("Abbr", dashboardDrawer.studyAbbreviation());
        assertEquals(List.of("Consent1", "Consent2"), dashboardDrawer.consentGroups());
        assertEquals("Summary", dashboardDrawer.studySummary());
        assertEquals(List.of("Focus1", "Focus2"), dashboardDrawer.studyFocus());
        assertEquals("Design", dashboardDrawer.studyDesign());
        assertEquals("Sponsor", dashboardDrawer.sponsor());
    }
}
