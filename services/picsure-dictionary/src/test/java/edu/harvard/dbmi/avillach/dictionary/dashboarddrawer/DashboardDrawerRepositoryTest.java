package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Optional;

@Testcontainers
@SpringBootTest
class DashboardDrawerRepositoryTest {
    @Autowired
    DashboardDrawerRepository subject;

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Test
    void shouldGetDashboardDrawers() {
        Optional<List<DashboardDrawer>> result = subject.getDashboardDrawerRows();

        Assertions.assertTrue(result.isPresent(), "Expected dashboard drawers to be present.");
    }

    @Test
    void shouldGetDashboardDrawersByDatasetId() {
        int expectedDatasetId = 17;
        Optional<DashboardDrawer> result = subject.getDashboardDrawerRowsByDatasetId(expectedDatasetId);

        Assertions.assertTrue(result.isPresent(), "Expected a DashboardDrawer to be present");
        Assertions.assertEquals(expectedDatasetId, result.get().datasetId(), "Expected the dataset id to be " + expectedDatasetId);
    }
}
