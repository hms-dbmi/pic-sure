package edu.harvard.dbmi.avillach.dump.remote.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.dump.entities.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@SpringBootTest
class RemoteDictionaryAPITest {

    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    CloseableHttpClient client;

    @Autowired
    RemoteDictionaryAPI subject;

    @Test
    void shouldFetchTimestamp() throws IOException {
        LocalDateTime now = LocalDateTime.parse(LocalDateTime.now().format(dateTimeFormatter), dateTimeFormatter);
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        BasicHttpEntity body = new BasicHttpEntity();
        body.setContent(new ByteArrayInputStream(now.format(dateTimeFormatter).getBytes()));
        Mockito.when(response.getEntity()).thenReturn(body);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<LocalDateTime> actual = subject.fetchUpdateTimestamp("bch");
        Optional<LocalDateTime> expected = Optional.of(now);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFetchDatabaseVersion() throws IOException {
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        BasicHttpEntity body = new BasicHttpEntity();
        Integer version = 3;
        body.setContent(new ByteArrayInputStream(version.toString().getBytes()));
        Mockito.when(response.getEntity()).thenReturn(body);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<Integer> actual = subject.fetchDatabaseVersion("bch");
        Optional<Integer> expected = Optional.of(version);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldFetchConceptNodes() throws IOException {
        List<ConceptNodeDump> concepts = List.of(new ConceptNodeDump("a", "b", "c", "d", "e", 1, 2));
        CloseableHttpResponse response = mockResponseWithBody(concepts);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<List<ConceptNodeDump>> actual = subject.fetchConcepts("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(concepts, actual.get());
    }

    @Test
    void shouldFetchFacetCategories() throws IOException {
        List<FacetCategoryDump> facetCats = List.of(new FacetCategoryDump("foo", "bar", "desc"));
        CloseableHttpResponse response = mockResponseWithBody(facetCats);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<List<FacetCategoryDump>> actual = subject.fetchFacetCategories("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(facetCats, actual.get());
    }

    @Test
    void shouldFetchFacets() throws IOException {
        FacetDump child = new FacetDump("1", "1", "1", "1", List.of(), 2, 1);
        List<FacetDump> facets = List.of(new FacetDump("foo", "bar", "baz", "qux", List.of(child), 1, null));
        CloseableHttpResponse response = mockResponseWithBody(facets);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<List<FacetDump>> actual = subject.fetchFacets("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(facets, actual.get());
    }

    @Test
    void shouldFetchPairs() throws IOException {
        List<FacetConceptPair> pairs = List.of(new FacetConceptPair("name", "cat", "path"));
        CloseableHttpResponse response = mockResponseWithBody(pairs);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<List<FacetConceptPair>> actual = subject.fetchFacetConceptPairs("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(pairs, actual.get());
    }

    @Test
    void shouldFetchConceptMetas() throws IOException {
        List<ConceptNodeMetaDump> metas = List.of(new ConceptNodeMetaDump("path", "k", "v"));
        CloseableHttpResponse response = mockResponseWithBody(metas);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<List<ConceptNodeMetaDump>> actual = subject.fetchConceptMetas("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(metas, actual.get());
    }

    @Test
    void shouldFetchFacetCategoryMetas() throws IOException {
        List<FacetCategoryMetaDump> metas = List.of(new FacetCategoryMetaDump("facet cat", "k", "v"));
        CloseableHttpResponse response = mockResponseWithBody(metas);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<List<FacetCategoryMetaDump>> actual = subject.fetchFacetCategoryMetas("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(metas, actual.get());
    }

    @Test
    void shouldFetchFacetMetas() throws IOException {
        List<FacetMetaDump> metas = List.of(new FacetMetaDump("facet", "cat", "k", "v"));
        CloseableHttpResponse response = mockResponseWithBody(metas);
        Mockito.when(client.execute(Mockito.any())).thenReturn(response);

        Optional<List<FacetMetaDump>> actual = subject.fetchFacetMetas("bch");

        Assertions.assertTrue(actual.isPresent());
        Assertions.assertEquals(metas, actual.get());
    }

    private CloseableHttpResponse mockResponseWithBody(Object jsonBody) {
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        BasicHttpEntity body = new BasicHttpEntity();
        Mockito.when(response.getEntity()).thenReturn(body);
        try {
            body.setContent(new ByteArrayInputStream(mapper.writeValueAsBytes(jsonBody)));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            Assertions.fail();
        }
        return response;
    }
}
