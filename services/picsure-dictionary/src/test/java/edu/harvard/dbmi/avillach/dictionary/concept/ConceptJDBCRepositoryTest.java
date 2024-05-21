package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.InteriorConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.persistence.ConceptJDBCRepository;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
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
class ConceptJDBCRepositoryTest {

    @Autowired
    ConceptJDBCRepository subject;

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
    void shouldListConcepts() {
        List<Facet> facets = List.of(
            new Facet("bch", "display", "desc", null, "site"),
            new Facet("narnia", "display", "desc", null, "site")
        );
        List<Concept> actual = subject.getConcepts(new Filter(facets, ""));
        List<? extends Record> expected = List.of(
            new CategoricalConcept(
                "\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.5 Severe persistent asthma\\\\J45.51 Severe persistent asthma with (acute) exacerbation\\\\", "J45.51 Severe persistent asthma with (acute) exacerbation",
                "J45.51 Severe p", "invalid.invalid", List.of("true", "false"), Map.of()
            ),
            new CategoricalConcept(
                "\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.5 Severe persistent asthma\\\\J45.52 Severe persistent asthma with status asthmaticus\\\\", "J45.52 Severe persistent asthma with status asthmaticus",
                "J45.52 Severe p", "invalid.invalid", List.of("true", "false"), Map.of()
            ),
            new InteriorConcept(
                "\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.9 Other and unspecified asthma\\\\J45.90 Unspecified asthma\\\\",
                "J45.90 Unspecified asthma", "J45.90 Unspecif", "invalid.invalid", null, Map.of()
            ),
            new InteriorConcept(
                "\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.9 Other and unspecified asthma\\\\J45.90 Unspecified asthma\\\\",
                "J45.90 Unspecified asthma", "J45.90 Unspecif", "invalid.invalid", null, Map.of()
            )
        );
        
        Assertions.assertEquals(expected, actual);
    }
}