package edu.harvard.hms.dbmi.avillach.hpds.processing.dictionary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceTest {

    private static final String BASE_URL = "http://dictionary-api";
    private static final String EXPORT_URL = BASE_URL + "/internal/concepts/export-metadata";
    private static final String TOKEN = "shared-secret";

    @Mock
    private RestTemplate restTemplate;

    @Captor
    private ArgumentCaptor<HttpEntity<ConceptExportRequest>> requestCaptor;

    private static Concept concept(String path) {
        return new Concept(path, path, path, "dataset", "description", null);
    }

    private static List<String> paths(int count) {
        return IntStream.range(0, count).mapToObj(index -> "\\study\\concept" + index + "\\").toList();
    }

    private void stubEchoingDictionary(String url) {
        when(restTemplate.exchange(eq(url), eq(HttpMethod.POST), any(HttpEntity.class), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE)))
            .thenAnswer(invocation -> {
                HttpEntity<ConceptExportRequest> request = invocation.getArgument(2);
                return ResponseEntity.ok(request.getBody().conceptPaths().stream().map(DictionaryServiceTest::concept).toList());
            });
    }

    @Test
    void getConcepts_morePathsThanBatchSize_postsEachBatchAndConcatenates() {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, TOKEN, 1000, restTemplate);
        List<String> conceptPaths = paths(2500);
        stubEchoingDictionary(EXPORT_URL);

        List<Concept> concepts = dictionaryService.getConcepts(conceptPaths);

        verify(restTemplate, times(3))
            .exchange(eq(EXPORT_URL), eq(HttpMethod.POST), requestCaptor.capture(), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE));
        assertEquals(
            List.of(1000, 1000, 500), requestCaptor.getAllValues().stream().map(request -> request.getBody().conceptPaths().size()).toList()
        );
        assertEquals(conceptPaths, concepts.stream().map(Concept::conceptPath).toList());
    }

    @Test
    void getConcepts_everyBatch_asksOnlyForDrsUriAndSendsTheTokenHeader() {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, TOKEN, 2, restTemplate);
        List<String> conceptPaths = paths(3);
        stubEchoingDictionary(EXPORT_URL);

        dictionaryService.getConcepts(conceptPaths);

        verify(restTemplate, times(2))
            .exchange(eq(EXPORT_URL), eq(HttpMethod.POST), requestCaptor.capture(), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE));
        assertEquals(
            List.of(
                new ConceptExportRequest(conceptPaths.subList(0, 2), List.of("drs_uri")),
                new ConceptExportRequest(conceptPaths.subList(2, 3), List.of("drs_uri"))
            ), requestCaptor.getAllValues().stream().map(HttpEntity::getBody).toList()
        );
        for (HttpEntity<ConceptExportRequest> request : requestCaptor.getAllValues()) {
            assertEquals(List.of(TOKEN), request.getHeaders().get("X-PIC-SURE-INTERNAL-TOKEN"));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void getConcepts_blankToken_throwsUnavailableNamingPropertyWithoutCalling(String token) {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, token, 1000, restTemplate);

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains("picsure.internal.token"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("PICSURE_INTERNAL_TOKEN"), thrown.getMessage());
        verifyNoInteractions(restTemplate);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN"})
    void getConcepts_dictionaryRejectsTheToken_throwsUnavailablePointingAtTheTokenWithoutPrintingIt(HttpStatus status) {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, TOKEN, 1000, restTemplate);
        HttpClientErrorException cause = HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, null, null);
        when(
            restTemplate
                .exchange(eq(EXPORT_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE))
        ).thenThrow(cause);

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains("picsure.internal.token"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(String.valueOf(status.value())), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(EXPORT_URL), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(TOKEN), thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    @Test
    void getConcepts_otherClientError_throwsUnavailableNamingUrlNotTheToken() {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, TOKEN, 1000, restTemplate);
        when(
            restTemplate
                .exchange(eq(EXPORT_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE))
        ).thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, "response-body-text".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
            )
        );

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains(EXPORT_URL), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("rejected the request"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("400"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("response-body-text"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("picsure.internal.token"), thrown.getMessage());
    }

    @Test
    void getConcepts_restClientException_throwsUnavailableNamingUrl() {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, TOKEN, 1000, restTemplate);
        ResourceAccessException cause = new ResourceAccessException("connection refused");
        when(
            restTemplate
                .exchange(eq(EXPORT_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE))
        ).thenThrow(cause);

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains(EXPORT_URL), thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    @Test
    void getConcepts_nullBody_throwsUnavailableNamingUrl() {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, TOKEN, 1000, restTemplate);
        when(
            restTemplate
                .exchange(eq(EXPORT_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE))
        ).thenReturn(ResponseEntity.ok().build());

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains(EXPORT_URL), thrown.getMessage());
    }

    @Test
    void getConcepts_blankUrl_throwsUnavailableNamingPropertyWithoutCalling() {
        DictionaryService dictionaryService = new DictionaryService("  ", TOKEN, 1000, restTemplate);

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains("dictionary.url"), thrown.getMessage());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void getConcepts_urlWithoutScheme_throwsUnavailableNamingPropertyAndValueWithoutCalling() {
        DictionaryService dictionaryService = new DictionaryService("dictionary-api", TOKEN, 1000, restTemplate);

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains("dictionary.url"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("dictionary-api"), thrown.getMessage());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void getConcepts_urlWithUnsupportedScheme_throwsUnavailableNamingPropertyAndValueWithoutCalling() {
        DictionaryService dictionaryService = new DictionaryService("ftp://dictionary-api", TOKEN, 1000, restTemplate);

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains("dictionary.url"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("ftp://dictionary-api"), thrown.getMessage());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void getConcepts_urlThatIsNotAUri_throwsUnavailableNamingPropertyAndValueWithoutCalling() {
        DictionaryService dictionaryService = new DictionaryService("http://dictionary api", TOKEN, 1000, restTemplate);

        DictionaryUnavailableException thrown =
            assertThrows(DictionaryUnavailableException.class, () -> dictionaryService.getConcepts(paths(2)));

        assertTrue(thrown.getMessage().contains("dictionary.url"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("http://dictionary api"), thrown.getMessage());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void getConcepts_trailingSlashOnBaseUrl_isNormalized() {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL + "/", TOKEN, 1000, restTemplate);
        stubEchoingDictionary(EXPORT_URL);

        List<Concept> concepts = dictionaryService.getConcepts(paths(2));

        assertEquals(2, concepts.size());
    }

    @Test
    void constructor_batchSizeBelowOne_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DictionaryService(BASE_URL, TOKEN, 0, restTemplate));
    }

    @Test
    void constructor_batchSizeAboveTheDictionaryRequestCap_isRejectedNamingThePropertyAndTheCap() {
        IllegalArgumentException thrown =
            assertThrows(IllegalArgumentException.class, () -> new DictionaryService(BASE_URL, TOKEN, 5001, restTemplate));

        assertTrue(thrown.getMessage().contains("dictionary.batch-size"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("5000"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("5001"), thrown.getMessage());
    }

    @Test
    void constructor_batchSizeAtTheDictionaryRequestCap_isAccepted() {
        DictionaryService dictionaryService = new DictionaryService(BASE_URL, TOKEN, 5000, restTemplate);
        stubEchoingDictionary(EXPORT_URL);

        assertEquals(5000, dictionaryService.getConcepts(paths(5000)).size());
        verify(restTemplate, times(1))
            .exchange(eq(EXPORT_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(DictionaryService.CONCEPT_LIST_TYPE_REFERENCE));
    }
}
