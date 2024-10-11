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
    void shouldGetDashboardRows() {
        List<Map<String, String>> actual = subject.getRows();
        List<Map<String, String>> expected = List.of(
            Map.of(
                "name", "DEMENTIA-SEQ: WGS in Lewy Body Dementia and Frontotemporal Dementia", "abbreviation", "DEMENTIA-SEQ", "melast", "",
                "clinvars", "653", "participants", "65"
            ), Map.of("name", "Framingham Cohort", "abbreviation", "FHS", "melast", "", "clinvars", "12546", "participants", "3435"),
            Map.of(
                "name", "Hematopoietic Cell Transplant for Sickle Cell Disease (HCT for SCD)", "abbreviation", "HCT_for_SCD", "melast", "",
                "clinvars", "7567", "participants", "33"
            ),
            Map.of(
                "name", "National Sleep Research Resource (NSRR): Cleveland Family Study (CFS)", "abbreviation", "NSRR CFS", "melast", "",
                "clinvars", "500", "participants", "23432"
            ),
            Map.of(
                "name", "National Sleep Research Resource (NSRR): (HSHC)", "abbreviation", "NSRR_HSHC", "melast", "", "clinvars", "434",
                "participants", "53435"
            ),
            Map.of(
                "name", "NHLBI Cleveland Family Study (CFS) Candidate Gene Association Resource (CARe)", "abbreviation", "CFS", "melast",
                "", "clinvars", "12321", "participants", "867876"
            ),
            Map.of(
                "name", "Nulliparous Pregnancy Outcomes Study: Monitoring Mothers-to-be Heart Health Study (nuMoM2b Heart Health Study)",
                "abbreviation", "nuMoM2b", "melast", "", "clinvars", "2", "participants", "111"
            ),
            Map.of(
                "name", "Researching COVID to Enhance Recovery (RECOVER): Adult Observational Cohort Study", "abbreviation",
                "RECOVER_Adult", "melast", "", "clinvars", "654645", "participants", "6654"
            ),
            Map.of(
                "name", "Systolic Blood Pressure Intervention Trial (SPRINT-Imaging)", "abbreviation", "SPRINT", "melast", "", "clinvars",
                "333", "participants", "2222"
            )
        );
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldDoBDCHack() {
        List<Map<String, String>> rows = subject.getHackyBDCRows();
        Assertions.assertNotNull(rows);
    }
}
