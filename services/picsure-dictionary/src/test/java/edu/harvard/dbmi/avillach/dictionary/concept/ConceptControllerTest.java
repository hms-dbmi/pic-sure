package edu.harvard.dbmi.avillach.dictionary.concept;

import edu.harvard.dbmi.avillach.dictionary.concept.model.CategoricalConcept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.concept.model.ContinuousConcept;
import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@SpringBootTest(properties = {"concept.tree.max_depth=1"})
@ActiveProfiles("test")
class ConceptControllerTest {

    @MockBean
    ConceptService conceptService;

    @Autowired
    ConceptController subject;

    @Test
    void shouldListConcepts() {
        List<Concept> expected = List.of(
            new CategoricalConcept("/foo", "foo", "Foo", "my_dataset", "foo!", List.of(), true, "", null, Map.of()),
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of()),
            new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", "foo!", true, 0D, 100D, "", Map.of())
        );
        Filter filter =
            new Filter(List.of(new Facet("questionare", "Questionare", "?", "Questionare", 1, null, "category", null)), "foo", List.of());
        Mockito.when(conceptService.listConcepts(filter, Pageable.ofSize(10).withPage(1))).thenReturn(expected);
        Mockito.when(conceptService.countConcepts(filter)).thenReturn(100L);

        Page<Concept> actual = subject.listConcepts(filter, 1, 10).getBody();

        Assertions.assertEquals(expected, actual.get().toList());
        Assertions.assertEquals(100L, actual.getTotalElements());
    }

    @Test
    void shouldGetConceptDetails() {
        CategoricalConcept expected =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of());
        Mockito.when(conceptService.conceptDetail("my_dataset", "/foo//bar")).thenReturn(Optional.of(expected));

        ResponseEntity<Concept> actual = subject.conceptDetail("my_dataset", "/foo//bar");

        Assertions.assertEquals(expected, actual.getBody());
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }

    @Test
    void shouldNotGetConceptDetails() {
        Mockito.when(conceptService.conceptDetail("my_dataset", "/foo//asdsad")).thenReturn(Optional.empty());

        ResponseEntity<Concept> actual = subject.conceptDetail("my_dataset", "/foo//bar");

        Assertions.assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
    }

    @Test
    void shouldGetConceptTree() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of());
        Concept fooBaz = new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", "foo!", true, 0D, 100D, "", Map.of());
        CategoricalConcept foo =
            new CategoricalConcept("/foo", "foo", "Foo", "my_dataset", "foo!", List.of(), true, "", List.of(fooBar, fooBaz), Map.of());

        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", 1)).thenReturn(Optional.of(foo));

        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/foo", 1);

        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
        Assertions.assertEquals(foo, actual.getBody());
    }

    @Test
    void shouldGetNotConceptTreeForLargeDepth() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of());
        Concept fooBaz = new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", "foo!", true, 0D, 100D, "", Map.of());
        CategoricalConcept foo =
            new CategoricalConcept("/foo", "foo", "Foo", "my_dataset", "foo!", List.of(), true, "", List.of(fooBar, fooBaz), Map.of());

        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", 1)).thenReturn(Optional.of(foo));

        // concept.tree.max_depth=1
        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/foo//bar", 2);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, actual.getStatusCode());
    }

    @Test
    void shouldGetNotConceptTreeForNegativeDepth() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of());
        Concept fooBaz = new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", "foo!", true, 0D, 100D, "", Map.of());
        CategoricalConcept foo =
            new CategoricalConcept("/foo", "foo", "Foo", "my_dataset", "foo!", List.of(), true, "", List.of(fooBar, fooBaz), Map.of());
        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", -1)).thenReturn(Optional.of(foo));

        // concept.tree.max_depth=1
        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/foo//bar", 2);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, actual.getStatusCode());
    }

    @Test
    void shouldNotGetConceptTreeWhenConceptDNE() {
        Concept fooBar =
            new CategoricalConcept("/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of());
        Concept fooBaz = new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", "foo!", true, 0D, 100D, "", Map.of());
        CategoricalConcept foo =
            new CategoricalConcept("/foo", "foo", "Foo", "my_dataset", "foo!", List.of(), true, "", List.of(fooBar, fooBaz), Map.of());

        Mockito.when(conceptService.conceptTree("my_dataset", "/foo", 1)).thenReturn(Optional.of(foo));

        ResponseEntity<Concept> actual = subject.conceptTree("my_dataset", "/asdsadasd", 1);

        Assertions.assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
    }

    @Test
    void shouldDumpConcepts() {
        Concept fooBar = new CategoricalConcept(
            "/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of("key", "value")
        );
        Concept fooBaz = new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", "foo!", true, 0D, 100D, "", Map.of("key", "value"));
        List<Concept> concepts = List.of(fooBar, fooBaz);
        PageRequest page = PageRequest.of(0, 10);
        Mockito.when(conceptService.listDetailedConcepts(new Filter(List.of(), "", List.of()), page)).thenReturn(concepts);

        ResponseEntity<Page<Concept>> actual = subject.dumpConcepts(0, 10);

        Assertions.assertEquals(concepts, actual.getBody().getContent());
        Assertions.assertEquals(HttpStatus.OK, actual.getStatusCode());
    }

    @Test
    void shouldReturnConceptsWithMeta() {
        CategoricalConcept fooBar = new CategoricalConcept(
            "/foo//bar", "bar", "Bar", "my_dataset", "foo!", List.of("a", "b"), true, "", List.of(), Map.of("key", "value")
        );
        Concept fooBaz = new ContinuousConcept("/foo//baz", "baz", "Baz", "my_dataset", "foo!", true, 0D, 100D, "", Map.of("key", "value"));
        List<Concept> concepts = List.of(fooBar, fooBaz);
        List<String> conceptPaths = List.of("/foo//bar", "/foo//bar");
        Mockito.when(conceptService.conceptsWithDetail(conceptPaths)).thenReturn(concepts);
        ResponseEntity<List<Concept>> listResponseEntity = subject.conceptsDetail(conceptPaths);
        Assertions.assertEquals(HttpStatus.OK, listResponseEntity.getStatusCode());
        Assertions.assertEquals(concepts, listResponseEntity.getBody());
    }

    @Test
    void shouldGetAllTrees() {
        int depth = 1;
        List<Concept> concepts = List.of(new CategoricalConcept("\\Foo\\", "foo"), new CategoricalConcept("\\Bar\\", "bar"));
        Mockito.when(conceptService.allConceptTrees(depth)).thenReturn(concepts);

        ResponseEntity<List<Concept>> actual = subject.allConceptTrees(depth);

        Assertions.assertEquals(200, actual.getStatusCode().value());
        Assertions.assertEquals(concepts, actual.getBody());
    }
}
