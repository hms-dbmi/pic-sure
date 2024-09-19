package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Optional;


@SpringBootTest
class ConceptDecoratorServiceTest {

    @MockBean
    ConceptService conceptService;

    @Autowired
    ConceptDecoratorService subject;

    @Test
    void shouldPopulateCompliantStudy() {
        CategoricalConcept concept = new CategoricalConcept("\\study\\table\\idk\\concept\\", "dataset");
        CategoricalConcept table = new CategoricalConcept("\\study\\table\\", "dataset");
        CategoricalConcept study = new CategoricalConcept("\\study\\", "dataset");

        Mockito.when(conceptService.conceptDetail("dataset", table.dataset()))
            .thenReturn(Optional.of(table));
        Mockito.when(conceptService.conceptDetail("dataset", study.dataset()))
            .thenReturn(Optional.of(study));

        Concept actual = subject.populateParentConcepts(concept);
        Concept expected = concept.withStudy(study).withTable(table);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldPopulateNonCompliantTabledStudy() {
        CategoricalConcept concept = new CategoricalConcept("\\study\\table\\concept\\", "dataset");
        CategoricalConcept table = new CategoricalConcept("\\study\\table\\", "dataset");
        CategoricalConcept study = new CategoricalConcept("\\study\\", "dataset");

        Mockito.when(conceptService.conceptDetail("dataset", table.dataset()))
            .thenReturn(Optional.of(table));
        Mockito.when(conceptService.conceptDetail("dataset", study.dataset()))
            .thenReturn(Optional.of(study));

        Concept actual = subject.populateParentConcepts(concept);
        Concept expected = concept.withStudy(study).withTable(table);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldPopulateNonCompliantUnTabledStudy() {
        CategoricalConcept concept = new CategoricalConcept("\\study\\concept\\", "dataset");
        CategoricalConcept study = new CategoricalConcept("\\study\\", "dataset");

        Mockito.when(conceptService.conceptDetail("dataset", study.dataset()))
            .thenReturn(Optional.of(study));

        Concept actual = subject.populateParentConcepts(concept);
        Concept expected = concept.withStudy(study);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldNotPopulateWeirdConcept() {
        CategoricalConcept concept = new CategoricalConcept("\\1\\2\\3\\4\\5\\6\\", "dataset");
        Concept actual = subject.populateParentConcepts(concept);

        Assertions.assertEquals(concept, actual);
    }
}