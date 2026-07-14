package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;



@SpringBootTest
@ActiveProfiles("test")
class DashboardDrawerControllerTest {

    @Autowired
    private DashboardDrawerController subject;

    @MockBean
    private DashboardDrawerService dashboardDrawerService;

    @Test
    void shouldFindAllDashboardDrawers() {
        DashboardDrawer sampleData1 = new DashboardDrawer(
            1, // dataset id
            "Test Data Set 1", // study full name
            "TDS1", // study abbreviation
            List.of("group1", "group2"), // consent groups
            "Test Study for Unit / Integration Testing", // study summary
            List.of("Study Focus One", "Study Focus Two"), // study focus
            "Test Study Design", // study design
            "Test Study Sponsor" // study sponsor
        );

        DashboardDrawer sampleData2 = new DashboardDrawer(
            2, "Test Data Set 2", "TDS2", List.of("group2"), "Test Study for Unit / Integration Testing", List.of("Study Focus One"),
            "Test Study Design", "Test Study Sponsor"
        );

        when(dashboardDrawerService.findAll()).thenReturn(Optional.of(List.of(sampleData1, sampleData2)));

        ResponseEntity<List<DashboardDrawer>> result = subject.findAll();

        Assertions.assertEquals(HttpStatus.OK, result.getStatusCode());

        List<DashboardDrawer> actualDrawerData = result.getBody();

        Assertions.assertNotNull(actualDrawerData, "Expected non-null list of DashboardDrawer");
        Assertions.assertEquals(2, actualDrawerData.size(), "Expected two drawers in the result");
    }

    @Test
    void shouldFindDashboardDrawerById() {
        DashboardDrawer expectedDrawer = new DashboardDrawer(
            1, "Test Data Set 1", "TDS1", List.of("group1", "group2"), "Test Study for Unit / Integration Testing",
            List.of("Study Focus One", "Study Focus Two"), "Test Study Design", "Test Study Sponsor"
        );

        when(dashboardDrawerService.findByDatasetId(1)).thenReturn(Optional.of(expectedDrawer));

        ResponseEntity<DashboardDrawer> actualDrawer = subject.findByDatasetId(1);

        Assertions.assertEquals(expectedDrawer, actualDrawer.getBody());

    }
}
