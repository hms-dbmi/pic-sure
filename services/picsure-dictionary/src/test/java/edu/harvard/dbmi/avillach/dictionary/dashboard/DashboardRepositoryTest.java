package edu.harvard.dbmi.avillach.dictionary.dashboard;

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
import java.util.Map;

@Testcontainers
@SpringBootTest
class DashboardRepositoryTest {

    @Autowired
    DashboardRepository subject;

    @Container
    static final PostgreSQLContainer<?> databaseContainer =
        new PostgreSQLContainer<>("postgres:16")
            .withReuse(true)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql"
            );

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Test
    void shouldGetDashboardRows() {
        List<Map<String, String>> actual = subject.getRows();
        List<Map<String, String>> expected = List.of(
            Map.of("name", "phs000007", "abbreviation", "FHS", "melast", "", "clinvars", "", "participants", ""),
            Map.of("name", "phs000284", "abbreviation", "CFS", "melast", "", "clinvars", "12546", "participants", "3435"),
            Map.of("name", "phs001963", "abbreviation", "DEMENTIA-SEQ", "melast", "", "clinvars", "12321", "participants", "867876"),
            Map.of("name", "phs002385", "abbreviation", "HCT_for_SCD", "melast", "", "clinvars", "653", "participants", "65"),
            Map.of("name", "phs002715", "abbreviation", "NSRR CFS", "melast", "", "clinvars", "7567", "participants", "33"),
            Map.of("name", "phs002808", "abbreviation", "nuMoM2b", "melast", "", "clinvars", "500", "participants", "23432"),
            Map.of("name", "phs003463", "abbreviation", "RECOVER_Adult", "melast", "", "clinvars", "2", "participants", "111"),
            Map.of("name", "phs003543", "abbreviation", "NSRR_HSHC", "melast", "", "clinvars", "654645", "participants", "6654"),
            Map.of("name", "phs003566", "abbreviation", "SPRINT", "melast", "", "clinvars", "434", "participants", "53435")
        );
        Assertions.assertEquals(expected, actual);
    }
}