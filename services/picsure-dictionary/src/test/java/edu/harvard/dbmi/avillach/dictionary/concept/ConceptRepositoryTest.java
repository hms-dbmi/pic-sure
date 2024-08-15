package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
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
class ConceptRepositoryTest {

    @Autowired
    ConceptRepository subject;

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
    void shouldListAllConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), ""), Pageable.unpaged());
        
        Assertions.assertEquals(29, actual.size());
    }

    @Test
    void shouldListFirstTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), ""), Pageable.ofSize(2).first());
        List<? extends Record> expected = List.of(
            new ContinuousConcept("\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", 0, 3, null),
            new CategoricalConcept("\\Variant Data Type\\Low coverage WGS\\", "Low coverage WGS", "Low coverage WGS", "1", "Low coverage WGS", List.of("TRUE"), null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldListNextTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), ""), Pageable.ofSize(2).first().next());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\phs002385\\RACEG\\", "RACEG", "RACEG", "phs002385", "Race (regrouped)", List.of("Not Reported"), null, null),
            new CategoricalConcept("\\Variant Data Type\\Low coverage WGS\\", "Low coverage WGS", "Low coverage WGS", "1", "Low coverage WGS", List.of("TRUE"), null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterConceptsByFacet() {
        List<Concept> actual =
            subject.getConcepts(new Filter(List.of(new Facet("bch", "", "", 1, null, "site", null)), ""), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\\\\\\\A\\\\\\\\", "a", "A", "invalid.invalid", null, List.of("0", "1"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\", "1", "1", "invalid.invalid", null, List.of("X", "Z"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\", "0", "0", "invalid.invalid", null, List.of("X", "Y"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", null, List.of("foo", "bar"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\Y\\\\\\\\", "y", "Y", "invalid.invalid", null, List.of("foo", "bar", "baz"), null, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", null, 0, 0, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\Z\\\\\\\\", "z", "Z", "invalid.invalid", null, 0, 0, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterBySearch() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "COLA"), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new ContinuousConcept("\\phs000007\\pht000022\\phv00004260\\FM219\\", "phv00004260", "FM219", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", 0, 1, null),
            new ContinuousConcept("\\phs000007\\pht000021\\phv00003844\\FL200\\", "phv00003844", "FL200", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA / DAY", 0, 3, null),
            new ContinuousConcept("\\phs000007\\pht000033\\phv00008849\\D080\\", "phv00008849", "D080", "phs000007", "# 12 OZ CUPS OF CAFFEINATED COLA/DAY", 0, 5, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterByBothSearchAndFacet() {
        List<Concept> actual =
            subject.getConcepts(new Filter(List.of(new Facet("phs002715", "", "", 1, null, "study_ids_dataset_ids", null)), "phs002715"), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\phs002715\\age\\", "AGE_CATEGORY", "age", "phs002715", "Participant's age (category)", List.of("21"), null, null),
            new CategoricalConcept("\\phs002715\\nsrr_ever_smoker\\", "nsrr_ever_smoker", "nsrr_ever_smoker", "phs002715", "Smoker status", List.of("yes"), null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetCount() {
        long actual = subject.countConcepts(new Filter(List.of(), ""));

        Assertions.assertEquals(29L, actual);
    }

    @Test
    void shouldGetCountWithFilter() {
        Long actual = subject.countConcepts(new Filter(List.of(new Facet("phs002715", "", "", 1, null, "study_ids_dataset_ids", null)), ""));
        Assertions.assertEquals(2L, actual);
    }

    @Test
    void shouldGetDetailForConcept() {
        ContinuousConcept expected =
            new ContinuousConcept("\\\\\\\\B\\\\\\\\2\\\\\\\\Z\\\\\\\\", "z", "Z", "invalid.invalid", null, 0, 0, null);
        Optional<Concept> actual = subject.getConcept("AGE_CATEGORY", "\\phs002715\\age\\");

        Assertions.assertEquals(Optional.of(expected), actual);
    }

    @Test
    void shouldGetMetaForConcept() {
        Map<String, String> actual = subject.getConceptMeta("AGE_CATEGORY", "\\phs002715\\age\\");
        Map<String, String> expected = Map.of();

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotGetConceptThatDNE() {
        Optional<Concept> actual = subject.getConcept("invalid.invalid", "fake");
        Assertions.assertEquals(Optional.empty(), actual);

        actual = subject.getConcept("fake", "\\\\\\\\B\\\\\\\\2\\\\\\\\Z\\\\\\\\");
        Assertions.assertEquals(Optional.empty(), actual);
    }
}