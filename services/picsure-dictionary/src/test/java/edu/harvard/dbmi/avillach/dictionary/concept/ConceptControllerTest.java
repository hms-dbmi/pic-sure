package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.InteriorConcept;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@SpringBootTest(properties = {"concept.tree.max_depth=1"})
class ConceptControllerTest {

    @MockBean
    ConceptService conceptService;

    @Autowired
    ConceptController subject;

    @Test
    void shouldListConcepts() {
        List<Concept> expected = List.of(
            new InteriorConcept("/foo", "foo", "Foo", "my_dataset", null, Map.of()),
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", List.of("a", "b"), Map.of()),
            new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", 0, 100, Map.of())
        );
        Filter filter = new Filter(
            List.of(new Facet("questionare", "Questionare", "?", null, "category")),
            "foo"
        );
        Mockito.when(conceptService.listConcepts(filter))
            .thenReturn(expected);

        ResponseEntity<List<Concept>> actual = subject.listConcepts(filter);

        Assertions.assertEquals(expected, actual.getBody());
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }

    @Test
    void shouldGetConceptDetails() {
        CategoricalConcept expected =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", List.of("a", "b"), Map.of());
        Mockito.when(conceptService.conceptDetail("my_dataset", "/foo//bar"))
            .thenReturn(Optional.of(expected));

        ResponseEntity<Concept> actual = subject.conceptDetail("my_dataset", "/foo//bar");

        Assertions.assertEquals(expected, actual.getBody());
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }

    @Test
    void shouldNotGetConceptDetails() {
        Mockito.when(conceptService.conceptDetail("my_dataset", "/foo//asdsad"))
            .thenReturn(Optional.empty());

        ResponseEntity<Concept> actual = subject.conceptDetail("my_dataset", "/foo//bar");

        Assertions.assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
    }

    @Test
    void shouldGetConceptTree() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", List.of("a", "b"), Map.of());
        Concept fooBaz =
            new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", 0, 100, Map.of());
        InteriorConcept foo =
            new InteriorConcept("/foo", "foo", "Foo", "my_dataset", List.of(fooBar, fooBaz), Map.of());

        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", 1))
            .thenReturn(Optional.of(foo));

        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/foo", 1);

        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
        Assertions.assertEquals(foo, actual.getBody());
    }

    @Test
    void shouldGetNotConceptTreeForLargeDepth() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", List.of("a", "b"), Map.of());
        Concept fooBaz =
            new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", 0, 100, Map.of());
        InteriorConcept foo =
            new InteriorConcept("/foo", "foo", "Foo", "my_dataset", List.of(fooBar, fooBaz), Map.of());

        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", 1))
            .thenReturn(Optional.of(foo));

        // concept.tree.max_depth=1
        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/foo//bar", 2);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, actual.getStatusCode());
    }

    @Test
    void shouldGetNotConceptTreeForNegativeDepth() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", List.of("a", "b"), Map.of());
        Concept fooBaz =
            new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", 0, 100, Map.of());
        InteriorConcept foo =
            new InteriorConcept("/foo", "foo", "Foo", "my_dataset", List.of(fooBar, fooBaz), Map.of());

        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", -1))
            .thenReturn(Optional.of(foo));

        // concept.tree.max_depth=1
        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/foo//bar", 2);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, actual.getStatusCode());
    }

    @Test
    void shouldNotGetConceptTreeWhenConceptDNE() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", List.of("a", "b"), Map.of());
        Concept fooBaz =
            new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", 0, 100, Map.of());
        InteriorConcept foo =
            new InteriorConcept("/foo", "foo", "Foo", "my_dataset", List.of(fooBar, fooBaz), Map.of());

        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", 1))
            .thenReturn(Optional.of(foo));

        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/asdsadasd", 1);

        Assertions.assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
    }
}