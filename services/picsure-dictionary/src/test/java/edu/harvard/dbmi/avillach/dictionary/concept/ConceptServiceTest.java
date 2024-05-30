package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;


@SpringBootTest
class ConceptServiceTest {

    @MockBean
    ConceptRepository repository;

    @Autowired
    ConceptService subject;

    @Test
    void shouldListConcepts() {
        List<Concept> expected = List.of(
            new CategoricalConcept("A", "a", "A", "invalid.invalid", List.of(), null, null)
        );
        Filter filter = new Filter(List.of(), "");
        Pageable page = Pageable.ofSize(10).first();
        Mockito.when(repository.getConcepts(filter, page))
            .thenReturn(expected);

        List<Concept> actual = subject.listConcepts(filter, page);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountConcepts() {
        Filter filter = new Filter(List.of(), "");
        Mockito.when(repository.countConcepts(filter))
            .thenReturn(1L);

        long actual = subject.countConcepts(filter);

        Assertions.assertEquals(1L, actual);
    }

    @Test
    void shouldShowDetailForContinuous() {
        ContinuousConcept concept = new ContinuousConcept("path", "", "", "dataset", 0, 1, null);
        Map<String, String> meta = Map.of("MIN", "0", "MAX", "1", "stigmatizing", "true");
        Mockito.when(repository.getConcept("dataset", "path"))
            .thenReturn(Optional.of(concept));
        Mockito.when(repository.getConceptMeta("dataset", "path"))
            .thenReturn(meta);

        Optional<Concept> actual = subject.conceptDetail("dataset", "path");
        Optional<Concept> expected = Optional.of(new ContinuousConcept(concept, meta));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldShowDetailForCategorical() {
        CategoricalConcept concept = new CategoricalConcept("path", "", "", "dataset", List.of("a"), List.of(), null);
        Map<String, String> meta = Map.of("VALUES", "a", "stigmatizing", "true");
        Mockito.when(repository.getConcept("dataset", "path"))
            .thenReturn(Optional.of(concept));
        Mockito.when(repository.getConceptMeta("dataset", "path"))
            .thenReturn(meta);

        Optional<Concept> actual = subject.conceptDetail("dataset", "path");
        Optional<Concept> expected = Optional.of(new CategoricalConcept(concept, meta));

        Assertions.assertEquals(expected, actual);
    }
}