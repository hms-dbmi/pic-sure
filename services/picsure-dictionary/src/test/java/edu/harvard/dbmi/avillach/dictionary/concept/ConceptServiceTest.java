package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ConceptShell;
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

    @MockBean
    ConceptDecoratorService decoratorService;

    @Autowired
    ConceptService subject;

    @Test
    void shouldListConcepts() {
        List<Concept> expected = List.of(
            new CategoricalConcept("A", "a", "A", "invalid.invalid", null, List.of(), true, "", null, null)
        );
        Filter filter = new Filter(List.of(), "", List.of());
        Pageable page = Pageable.ofSize(10).first();
        Mockito.when(repository.getConcepts(filter, page))
            .thenReturn(expected);

        List<Concept> actual = subject.listConcepts(filter, page);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldCountConcepts() {
        Filter filter = new Filter(List.of(), "", List.of());
        Mockito.when(repository.countConcepts(filter))
            .thenReturn(1L);

        long actual = subject.countConcepts(filter);

        Assertions.assertEquals(1L, actual);
    }

    @Test
    void shouldShowDetailForContinuous() {
        ContinuousConcept concept = new ContinuousConcept("path", "", "", "dataset", null, true, 0F, 1F, "", null);
        Map<String, String> meta = Map.of("MIN", "0", "MAX", "1", "stigmatizing", "true");
        Mockito.when(repository.getConcept("dataset", "path"))
            .thenReturn(Optional.of(concept));
        Mockito.when(decoratorService.populateParentConcepts(Mockito.any()))
            .thenAnswer(i -> i.getArguments()[0]);
        Mockito.when(repository.getConceptMeta("dataset", "path"))
            .thenReturn(meta);

        Optional<Concept> actual = subject.conceptDetail("dataset", "path");
        Optional<Concept> expected = Optional.of(new ContinuousConcept(concept, meta));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldShowDetailForCategorical() {
        CategoricalConcept concept = new CategoricalConcept("path", "", "", "dataset", null, List.of("a"), true, "", List.of(), null);
        Map<String, String> meta = Map.of("VALUES", "a", "stigmatizing", "true");
        Mockito.when(repository.getConcept("dataset", "path"))
            .thenReturn(Optional.of(concept));
        Mockito.when(decoratorService.populateParentConcepts(Mockito.any()))
            .thenAnswer(i -> i.getArguments()[0]);
        Mockito.when(repository.getConceptMeta("dataset", "path"))
            .thenReturn(meta);

        Optional<Concept> actual = subject.conceptDetail("dataset", "path");
        Optional<Concept> expected = Optional.of(new CategoricalConcept(concept, meta));

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldShowDetailForMultiple() {
        ConceptShell shellA = new ConceptShell("pathA", "dataset");
        CategoricalConcept conceptA = new CategoricalConcept("pathA", "", "", "dataset", null, List.of("a"), true, "", List.of(), null);
        Map<String, String> metaA = Map.of("VALUES", "a", "stigmatizing", "true");

        ConceptShell shellB = new ConceptShell("pathB", "dataset");
        ContinuousConcept conceptB = new ContinuousConcept("pathB", "", "", "dataset", null, true, 0F, 1F, "", null);
        Map<String, String> metaB = Map.of("MIN", "0", "MAX", "1", "stigmatizing", "true");

        Map<Concept, Map<String, String>> metas = Map.of(shellA, metaA, shellB, metaB);
        List<Concept> concepts = List.of(conceptA, conceptB);
        Filter emptyFilter = new Filter(List.of(), "", List.of());


        Mockito.when(repository.getConceptMetaForConcepts(concepts))
            .thenReturn(metas);
        Mockito.when(repository.getConcepts(emptyFilter, Pageable.unpaged()))
            .thenReturn(concepts);

        List<Concept> actual = subject.listDetailedConcepts(emptyFilter, Pageable.unpaged());
        List<Concept> expected = List.of(
            new CategoricalConcept(conceptA, metaA),
            new ContinuousConcept(conceptB, metaB)
        );

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetTree() {
        CategoricalConcept concept = new CategoricalConcept("ds", "\\A\\B\\C\\").withChildren(
            List.of(new CategoricalConcept("ds", "\\A\\B\\C\\1\\"), new ContinuousConcept("ds", "\\A\\B\\C\\2\\"))
        );
        Mockito.when(repository.getConceptTree("ds", "\\A\\B\\C\\", 2))
            .thenReturn(Optional.of(concept));

        Optional<Concept> actual = subject.conceptTree("ds", "\\A\\B\\C\\", 2);

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(concept, actual.get());
    }
}