package edu.harvard.dbmi.avillach.dictionary.dataset;

import edu.harvard.dbmi.avillach.dictionary.facet.FacetRepository;
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

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class DatasetRepositoryTest {

    @Autowired
    DatasetRepository subject;

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
    void shouldGetDataset() {
        Optional<Dataset> actual = subject.getDataset("1");
        Dataset expected = new Dataset(
            "1", "Genomic Information Commons", "GIC",
            "The GIC utilizes the ACT ontology to ensure data alignment across the sites. This project also includes other variables of interest as defined by the Governance Committee, such as biosamples, consents, etc."
        );

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(expected, actual.get());
    }

    @Test
    void shouldNotGetDatasetThatDNE() {
        Optional<Dataset> actual = subject.getDataset(":)");

        Assertions.assertFalse(actual.isPresent());
    }

    @Test
    void shouldGetDatasetMeta() {
        Map<String, String> actual = subject.getDatasetMeta("phs002715");
        Map<String, String> expected = Map
            .of("focus", "Sleep Apnea Syndromes", "design", "Prospective Longitudinal Cohort", "clinvars", "500", "participants", "23432");

        Assertions.assertEquals(expected, actual);
    }
}
