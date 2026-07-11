package edu.harvard.dbmi.avillach.dump.remote.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dump.entities.*;
import edu.harvard.dbmi.avillach.dump.util.JacksonConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RemoteDictionaryAPITest {

    private static final String ROOT = "http://passthru:80/dictionary-dump/";
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    private MockRestServiceServer server;
    private RemoteDictionaryAPI subject;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        subject = new RemoteDictionaryAPI(builder.build(), mapper);
    }

    @Test
    void shouldFetchTimestamp() {
        LocalDateTime now = LocalDateTime.parse(LocalDateTime.now().format(dateTimeFormatter), dateTimeFormatter);
        server.expect(requestTo(ROOT + "bch/last-updated")).andRespond(withSuccess(now.format(dateTimeFormatter), MediaType.TEXT_PLAIN));

        Optional<LocalDateTime> actual = subject.fetchUpdateTimestamp("bch");
        Optional<LocalDateTime> expected = Optional.of(now);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFetchDatabaseVersion() {
        Integer version = 3;
        server.expect(requestTo(ROOT + "bch/database-version")).andRespond(withSuccess(version.toString(), MediaType.TEXT_PLAIN));

        Optional<Integer> actual = subject.fetchDatabaseVersion("bch");
        Optional<Integer> expected = Optional.of(version);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFetchConceptNodes() {
        List<ConceptNodeDump> concepts = List.of(new ConceptNodeDump("a", "b", "c", "d", "e", 1, 2));
        server.expect(requestTo(ROOT + "bch/dump/ConceptNode")).andRespond(json(concepts));

        Optional<List<ConceptNodeDump>> actual = subject.fetchConcepts("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(concepts, actual.get());
    }

    @Test
    void shouldFetchFacetCategories() {
        List<FacetCategoryDump> facetCats = List.of(new FacetCategoryDump("foo", "bar", "desc"));
        server.expect(requestTo(ROOT + "bch/dump/FacetCategory")).andRespond(json(facetCats));

        Optional<List<FacetCategoryDump>> actual = subject.fetchFacetCategories("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(facetCats, actual.get());
    }

    @Test
    void shouldFetchFacets() {
        FacetDump child = new FacetDump("1", "1", "1", "1", List.of(), 2, 1);
        List<FacetDump> facets = List.of(new FacetDump("foo", "bar", "baz", "qux", List.of(child), 1, null));
        server.expect(requestTo(ROOT + "bch/dump/Facet")).andRespond(json(facets));

        Optional<List<FacetDump>> actual = subject.fetchFacets("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(facets, actual.get());
    }

    @Test
    void shouldFetchPairs() {
        List<FacetConceptPair> pairs = List.of(new FacetConceptPair("name", "cat", "path"));
        server.expect(requestTo(ROOT + "bch/dump/FacetConceptNode")).andRespond(json(pairs));

        Optional<List<FacetConceptPair>> actual = subject.fetchFacetConceptPairs("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(pairs, actual.get());
    }

    @Test
    void shouldFetchConceptMetas() {
        List<ConceptNodeMetaDump> metas = List.of(new ConceptNodeMetaDump("path", "k", "v"));
        server.expect(requestTo(ROOT + "bch/dump/ConceptNodeMeta")).andRespond(json(metas));

        Optional<List<ConceptNodeMetaDump>> actual = subject.fetchConceptMetas("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(metas, actual.get());
    }

    @Test
    void shouldFetchFacetCategoryMetas() {
        List<FacetCategoryMetaDump> metas = List.of(new FacetCategoryMetaDump("facet cat", "k", "v"));
        server.expect(requestTo(ROOT + "bch/dump/FacetCategoryMeta")).andRespond(json(metas));

        Optional<List<FacetCategoryMetaDump>> actual = subject.fetchFacetCategoryMetas("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(metas, actual.get());
    }

    @Test
    void shouldFetchFacetMetas() {
        List<FacetMetaDump> metas = List.of(new FacetMetaDump("facet", "cat", "k", "v"));
        server.expect(requestTo(ROOT + "bch/dump/FacetMeta")).andRespond(json(metas));

        Optional<List<FacetMetaDump>> actual = subject.fetchFacetMetas("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(metas, actual.get());
    }

    private org.springframework.test.web.client.ResponseCreator json(Object jsonBody) {
        try {
            return withSuccess(mapper.writeValueAsString(jsonBody), MediaType.APPLICATION_JSON);
        } catch (JsonProcessingException e) {
            Assertions.fail(e);
            throw new IllegalStateException(e);
        }
    }
}
