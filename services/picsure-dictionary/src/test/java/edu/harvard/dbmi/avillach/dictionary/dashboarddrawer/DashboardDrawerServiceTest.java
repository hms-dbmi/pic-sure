package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class DashboardDrawerServiceTest {

    @Autowired
    DashboardDrawerService service;

    @MockBean
    DashboardDrawerRepository repository;

    @Test
    void shouldFindAllDashboardDrawers() {
        // Arrange: Mock the repository to return a sample dataset
        when(repository.getDashboardDrawerRows()).thenReturn(Optional.of(List.of(
                new DashboardDrawer(1, "Test Data Set 1", "TDS1", List.of("group1", "group2"), "Test Study for Unit / Integration Testing", List.of("Study Focus One", "Study Focus Two"), "Test Study Design", "Test Study Sponsor"),
                new DashboardDrawer(2, "Test Data Set 2", "TDS2", List.of("group2"), "Test Study for Unit / Integration Testing", List.of("Study Focus One"), "Test Study Design", "Test Study Sponsor")
        )));

        // Act: Call the service method
        Optional<List<DashboardDrawer>> result = service.findAll();

        Assertions.assertTrue(result.isPresent());
        List<DashboardDrawer> dashboardDrawers = result.get();
        Assertions.assertEquals(2, dashboardDrawers.size());

        DashboardDrawer firstDrawer = dashboardDrawers.getFirst();
        Assertions.assertEquals(1, firstDrawer.datasetId());
        Assertions.assertEquals("Test Data Set 1", firstDrawer.studyFullname());
        Assertions.assertEquals("TDS1", firstDrawer.studyAbbreviation());
        Assertions.assertEquals(List.of("group1", "group2"), firstDrawer.consentGroups());
        Assertions.assertEquals("Test Study for Unit / Integration Testing", firstDrawer.studySummary());
        Assertions.assertEquals(List.of("Study Focus One", "Study Focus Two"), firstDrawer.studyFocus());
        Assertions.assertEquals("Test Study Design", firstDrawer.studyDesign());
        Assertions.assertEquals("Test Study Sponsor", firstDrawer.sponsor());

        DashboardDrawer secondDrawer = dashboardDrawers.get(1);
        Assertions.assertEquals(2, secondDrawer.datasetId());
        Assertions.assertEquals("Test Data Set 2", secondDrawer.studyFullname());
        Assertions.assertEquals("TDS2", secondDrawer.studyAbbreviation());
        Assertions.assertEquals(List.of("group2"), secondDrawer.consentGroups());
        Assertions.assertEquals("Test Study for Unit / Integration Testing", firstDrawer.studySummary());
        Assertions.assertEquals(List.of("Study Focus One"), secondDrawer.studyFocus());
        Assertions.assertEquals("Test Study Design", secondDrawer.studyDesign());
        Assertions.assertEquals("Test Study Sponsor", secondDrawer.sponsor());
    }

    @Test
    void shouldFindADashboardDrawerByDataasetId() {
        // Arrange: Mock the repository to return a sample dataset
        when(repository.getDashboardDrawerRowsByDatasetId(1)).thenReturn(Optional.of(
                new DashboardDrawer(1, "Test Data Set 1", "TDS1", List.of("group1", "group2"), "Test Study for Unit / Integration Testing", List.of("Study Focus One", "Study Focus Two"), "Test Study Design", "Test Study Sponsor")
        ));

        // Act: Call the service method
        Optional<DashboardDrawer> result = service.findByDatasetId(1);

        Assertions.assertTrue(result.isPresent());
        DashboardDrawer drawer = result.get();

        Assertions.assertEquals(1, drawer.datasetId());
        Assertions.assertEquals("Test Data Set 1", drawer.studyFullname());
        Assertions.assertEquals("TDS1", drawer.studyAbbreviation());
        Assertions.assertEquals(List.of("group1", "group2"), drawer.consentGroups());
        Assertions.assertEquals("Test Study for Unit / Integration Testing", drawer.studySummary());
        Assertions.assertEquals(List.of("Study Focus One", "Study Focus Two"), drawer.studyFocus());
        Assertions.assertEquals("Test Study Design", drawer.studyDesign());
        Assertions.assertEquals("Test Study Sponsor", drawer.sponsor());

    }
}
