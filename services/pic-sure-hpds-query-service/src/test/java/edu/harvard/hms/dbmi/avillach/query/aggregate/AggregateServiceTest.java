package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.query.QueryService;

class AggregateServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObfuscationService obfuscation() {
        AggregateProperties p = new AggregateProperties();
        p.getObfuscation().setThreshold(10);
        p.getObfuscation().setVariance(3);
        p.getObfuscation().setSalt("fixed");
        return new ObfuscationService(p, new VisualizationFormatter());
    }

    /** obfuscation-path tests don't exercise persistence; a throwaway QueryService mock keeps their construction terse. */
    private AggregateService service(AggregateBackendClient backend, AggregateProperties props) {
        return new AggregateService(backend, obfuscation(), props, mock(QueryService.class));
    }

    private QueryRequest sync(String expectedResultType) {
        return new GeneralQueryRequest().setQuery(Map.of("expectedResultType", expectedResultType));
    }

    @Test
    void rejectsDisallowedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(sync("DATAFRAME"), AggregateVariant.V1)).isInstanceOf(PicsureException.class);
        verify(backend, never()).querySync(any(), any());
    }

    @Test
    void rejectsMissingExpectedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        QueryRequest noErt = new GeneralQueryRequest().setQuery(Map.of("fields", "x"));
        assertThatThrownBy(() -> svc.querySync(noErt, AggregateVariant.V1)).isInstanceOf(PicsureException.class);
    }

    @Test
    void rejectsNullQueryWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(new GeneralQueryRequest(), AggregateVariant.V1)).isInstanceOf(PicsureException.class);
    }

    @Test
    void countBelowThresholdIsFloored() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("5"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).isEqualTo("< 10");
    }

    @Test
    void countAtOrAboveThresholdIsVarianceRandomized() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("100"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).matches("\\d+ ±3");
    }

    @Test
    void crossCountObfuscatesEachEntry() throws Exception {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        // changeQueryToOpenCrossCount first searches consents, then the backend returns the cross counts
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1)))
            .thenReturn(ResponseEntity.ok("{\"\\\\study\\\\a\\\\\":\"5\",\"\\\\study\\\\b\\\\\":\"100\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CROSS_COUNT"), AggregateVariant.V1);
        Map<String, String> body = mapper.readValue(out.getBody(), Map.class);
        assertThat(body.get("\\study\\a\\")).isEqualTo("< 10");
        assertThat(body.get("\\study\\b\\")).matches("\\d+ ±3");
    }

    @Test
    void crossCountUsesCrossCountFieldsForV1() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{}"));
        AggregateService svc = service(backend, new AggregateProperties());

        svc.querySync(sync("CROSS_COUNT"), AggregateVariant.V1);

        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(backend).querySync(cap.capture(), eq(AggregateVariant.V1));
        @SuppressWarnings("unchecked")
        Map<String, Object> query = mapper.convertValue(cap.getValue().getQuery(), Map.class);
        assertThat(query).containsKey("crossCountFields").doesNotContainKey("select");
    }

    @Test
    void crossCountUsesSelectFieldForV3() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V3))).thenReturn(ResponseEntity.ok("{}"));
        AggregateService svc = service(backend, new AggregateProperties());

        svc.querySync(sync("CROSS_COUNT"), AggregateVariant.V3);

        // capture the mutated request sent to the backend; assert it carries `select`, not `crossCountFields`
        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(backend).querySync(cap.capture(), eq(AggregateVariant.V3));
        @SuppressWarnings("unchecked")
        Map<String, Object> query = mapper.convertValue(cap.getValue().getQuery(), Map.class);
        assertThat(query).containsKey("select").doesNotContainKey("crossCountFields");
    }

    @Test
    void categoricalCrossCountObfuscatesViaCrossCountLookup() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        // first querySync call returns the raw categorical payload; the CROSS_COUNT lookup (getCrossCountForQuery)
        // is a second call to querySync with the mutated (CROSS_COUNT) request
        when(backend.querySync(any(), eq(AggregateVariant.V1)))
            .thenReturn(ResponseEntity.ok("{\"\\\\gender\\\\\":{\"male\":5,\"female\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CATEGORICAL_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).contains("\"male\"").contains("< 10");
    }

    @Test
    void continuousCrossCountSuppressedWhenStudyConsentsBelowThreshold() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"< 10\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).isNull();
    }

    @Test
    void continuousCrossCountObfuscatesRawWhenNoVisualizationConfigured() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).contains("\"5\"").matches(s -> s.matches(".*±3.*"));
    }

    @Test
    void continuousCrossCountBinsViaVisualizationWhenConfigured() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any(), eq(AggregateVariant.V1))).thenReturn("{\"\\\\age\\\\\":{\"0-10\":100}}");
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        AggregateService svc = service(backend, props);

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).contains("\"0-10\"");
        verify(backend).binContinuous(any(), eq(AggregateVariant.V1));
    }

    // ---- async open submit (finding I6): CROSS_COUNT is consent-scoped, then persisted+dispatched via QueryService ----

    @Test
    void asyncOpenCrossCountIsRewrittenThenDispatchedViaQueryServiceV1() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        svc.query(sync("CROSS_COUNT"), AggregateVariant.V1);

        // The query handed to QueryService for persistence+dispatch is the REWRITTEN cross-count query (consent-scoped), never the raw one.
        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryService).query(eq("open"), cap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> query = mapper.convertValue(cap.getValue().getQuery(), Map.class);
        assertThat(query).containsKey("crossCountFields"); // full study-consents allow-list injected
        assertThat(query.get("expectedResultType")).isEqualTo("CROSS_COUNT");
    }

    @Test
    void asyncOpenCrossCountUsesSelectAndDispatchesViaQueryServiceV3() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        svc.query(sync("CROSS_COUNT"), AggregateVariant.V3);

        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryService).queryV3(eq("open"), cap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> query = mapper.convertValue(cap.getValue().getQuery(), Map.class);
        assertThat(query).containsKey("select").doesNotContainKey("crossCountFields");
    }

    @Test
    void asyncOpenNonCrossCountIsForwardedUnchanged() {
        // WAR parity: the async query() only rewrites CROSS_COUNT; other types pass through unchanged (and fetch no consents).
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        svc.query(sync("COUNT"), AggregateVariant.V1);

        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryService).query(eq("open"), cap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> query = mapper.convertValue(cap.getValue().getQuery(), Map.class);
        assertThat(query.get("expectedResultType")).isEqualTo("COUNT");
        assertThat(query).doesNotContainKey("crossCountFields");
        verify(backend, never()).search(any());
    }

    @Test
    void asyncOpenQueryRejectsMissingExpectedResultTypeWith400() {
        // WAR parity: the async query() rejected a missing expectedResultType (MISSING_DATA) before touching the backend.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        QueryRequest noErt = new GeneralQueryRequest().setQuery(Map.of("fields", "x"));
        assertThatThrownBy(() -> svc.query(noErt, AggregateVariant.V1)).isInstanceOf(PicsureException.class);
        verifyNoInteractions(queryService);
    }

    @Test
    void propagatesQueryMetadataHeaderUnderRealHpdsHeaderName() {
        // NON-TAUTOLOGICAL (finding I5): the stub uses the REAL HPDS header literal "queryMetadata", NOT the constant. Under the old
        // "resultMetadata" bug the service would read getFirst("resultMetadata") == null from this response and DROP the header, so this
        // assertion would fail. It passes only when the constant resolves to the real name.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok().header("queryMetadata", "rid").body("12"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"), AggregateVariant.V1);
        assertThat(out.getHeaders().getFirst("queryMetadata")).isEqualTo("rid");
        assertThat(AggregateBackendClient.QUERY_METADATA_FIELD).isEqualTo("queryMetadata");
    }

    private SearchResults consentsSearch() {
        Map<String, Object> phenotypes = new LinkedHashMap<>();
        phenotypes.put("\\study\\a\\consent\\", Map.of());
        phenotypes.put("\\study\\b\\consent\\", Map.of());
        return new SearchResults().setResults(Map.of("phenotypes", phenotypes));
    }
}
