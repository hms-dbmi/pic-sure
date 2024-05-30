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
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\\\\\\\A\\\\\\\\", "a", "A", "invalid.invalid", List.of("0", "1"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\", "1", "1", "invalid.invalid", List.of("X", "Z"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\", "0", "0", "invalid.invalid", List.of("X", "Y"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", List.of("foo", "bar"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\Y\\\\\\\\", "y", "Y", "invalid.invalid", List.of("foo", "bar", "baz"), null, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", 0, 0, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\Z\\\\\\\\", "z", "Z", "invalid.invalid", 0, 0, null),
            new CategoricalConcept("\\\\\\\\B\\\\\\\\", "b", "B", "invalid.invalid", List.of("0", "2"), null, null),
            new CategoricalConcept("\\\\\\\\B\\\\\\\\0\\\\\\\\", "0", "0", "invalid.invalid", List.of("X", "Y", "Z"), null, null),
            new CategoricalConcept("\\\\\\\\B\\\\\\\\2\\\\\\\\", "2", "2", "invalid.invalid", List.of("Y", "Z"), null, null),
            new CategoricalConcept("\\\\\\\\B\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", List.of("bar", "baz"), null, null),
            new CategoricalConcept("\\\\\\\\B\\\\\\\\0\\\\\\\\Y\\\\\\\\", "y", "Y", "invalid.invalid", List.of("bar", "baz", "qux"), null, null),
            new CategoricalConcept("\\\\\\\\B\\\\\\\\0\\\\\\\\Z\\\\\\\\", "z", "Z", "invalid.invalid", List.of("foo", "bar", "baz", "qux"), null, null),
            new ContinuousConcept("\\\\\\\\B\\\\\\\\2\\\\\\\\Y\\\\\\\\", "y", "Y", "invalid.invalid", 0, 0, null),
            new ContinuousConcept("\\\\\\\\B\\\\\\\\2\\\\\\\\Z\\\\\\\\", "z", "Z", "invalid.invalid", 0, 0, null)
        );
        
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldListFirstTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), ""), Pageable.ofSize(2).first());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\\\\\\\A\\\\\\\\", "a", "A", "invalid.invalid", List.of("0", "1"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\", "1", "1", "invalid.invalid", List.of("X", "Z"), null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldListNextTwoConcepts() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), ""), Pageable.ofSize(2).first().next());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\", "0", "0", "invalid.invalid", List.of("X", "Y"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", List.of("foo", "bar"), null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterConceptsByFacet() {
        List<Concept> actual =
            subject.getConcepts(new Filter(List.of(new Facet("bch", "", "", 1, null, "site")), ""), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\\\\\\\A\\\\\\\\", "a", "A", "invalid.invalid", List.of("0", "1"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\", "1", "1", "invalid.invalid", List.of("X", "Z"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\", "0", "0", "invalid.invalid", List.of("X", "Y"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", List.of("foo", "bar"), null, null),
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\Y\\\\\\\\", "y", "Y", "invalid.invalid", List.of("foo", "bar", "baz"), null, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", 0, 0, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\Z\\\\\\\\", "z", "Z", "invalid.invalid", 0, 0, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterBySearch() {
        List<Concept> actual = subject.getConcepts(new Filter(List.of(), "X"), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", List.of("foo", "bar"), null, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", 0, 0, null),
            new CategoricalConcept("\\\\\\\\B\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", List.of("bar", "baz"), null, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFilterByBothSearchAndFacet() {
        List<Concept> actual =
            subject.getConcepts(new Filter(List.of(new Facet("bch", "", "", 1, null, "site")), "X"), Pageable.unpaged());
        List<? extends Record> expected = List.of(
            new CategoricalConcept("\\\\\\\\A\\\\\\\\0\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", List.of("foo", "bar"), null, null),
            new ContinuousConcept("\\\\\\\\A\\\\\\\\1\\\\\\\\X\\\\\\\\", "x", "X", "invalid.invalid", 0, 0, null)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetCount() {
        long actual = subject.countConcepts(new Filter(List.of(), ""));

        Assertions.assertEquals(15L, actual);
    }

    @Test
    void shouldGetCountWithFilter() {
        Long actual = subject.countConcepts(new Filter(List.of(new Facet("bch", "", "", 1, null, "site")), "X"));
        Assertions.assertEquals(2L, actual);
    }

    @Test
    void shouldGetDetailForConcept() {
        ContinuousConcept expected =
            new ContinuousConcept("\\\\\\\\B\\\\\\\\2\\\\\\\\Z\\\\\\\\", "z", "Z", "invalid.invalid", 0, 0, null);
        Optional<Concept> actual = subject.getConcept(expected.dataset(), expected.conceptPath());

        Assertions.assertEquals(Optional.of(expected), actual);
    }

    @Test
    void shouldGetMetaForConcept() {
        Map<String, String> actual = subject.getConceptMeta("invalid.invalid", "\\\\\\\\B\\\\\\\\2\\\\\\\\Z\\\\\\\\");
        Map<String, String> expected = Map.of("MIN", "0", "MAX", "0");

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