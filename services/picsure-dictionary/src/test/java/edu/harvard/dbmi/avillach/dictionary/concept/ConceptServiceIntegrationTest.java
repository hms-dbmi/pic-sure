package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
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
import java.util.Optional;

@Testcontainers
@SpringBootTest
class ConceptServiceIntegrationTest {

    @Autowired
    ConceptService subject;

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
    void shouldGetDetails() {
        Optional<Concept> actual = subject.conceptDetail("phs000007", "\\phs000007\\pht000021\\phv00003844\\FL200\\");

        CategoricalConcept table = new CategoricalConcept(
            "\\phs000007\\pht000021\\", "pht000021", "ex0_19s", "phs000007",
            "Clinic Exam, Original Cohort Exam 19",
            List.of(), true, null, Map.of("description", "Clinic Exam, Original Cohort Exam 19"), null, null
        );
        CategoricalConcept study = new CategoricalConcept(
            "\\phs000007\\", "", "", "phs000007", null, List.of(), true, null, Map.of(), null, null
        );
        ContinuousConcept expected = new ContinuousConcept(
            "\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007",
            "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", true, 0, 3,
            Map.of(
                "unique_identifier", "no",
                "stigmatizing", "no",
                "bdc_open_access", "yes",
                "values", "[0, 3]",
                "description", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY",
                "free_text", "no"
            ),
            null, table, study
        );

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(expected, actual.get());
    }
}