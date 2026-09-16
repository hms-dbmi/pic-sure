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
        p.getObfuscation().setThreshold(5); // what deployed environments are configured for
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
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("3"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).isEqualTo("< 5");
    }

    @Test
    void countAtOrAboveThresholdIsReportedExactly() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("100"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).isEqualTo("100");
    }

    @Test
    void crossCountObfuscatesEachEntry() throws Exception {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        // changeQueryToOpenCrossCount first searches consents, then the backend returns the cross counts
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1)))
            .thenReturn(ResponseEntity.ok("{\"\\\\study\\\\a\\\\\":\"3\",\"\\\\study\\\\b\\\\\":\"100\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CROSS_COUNT"), AggregateVariant.V1);
        Map<String, String> body = mapper.readValue(out.getBody(), Map.class);
        assertThat(body.get("\\study\\a\\")).isEqualTo("< 5");
        assertThat(body.get("\\study\\b\\")).isEqualTo("100");
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
        // chart thresholds derive from the count threshold at 2x, so male=5 is suppressed against 10, not 5
        assertThat(out.getBody()).contains("\"male\"").contains("< 10");
    }

    @Test
    void continuousCrossCountSuppressedWhenStudyConsentsBelowThreshold() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"< 5\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).isNull();
    }

    @Test
    void continuousCrossCountSuppressedWhenStudyConsentsIsRawBelowThresholdCount() {
        // regression: the consents lookup returns the RAW backend body; a raw "5" (below threshold, nonzero) must suppress the
        // whole continuous response, not just floor the individual bins
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"3\"}"));
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
        // continuous threshold is 2 x 5 = 10, so raw 100 sits in [100, 110) -> midpoint 105, band 5
        assertThat(out.getBody()).contains("\"5\"").contains("\"count\":105").contains("\"variance\":5");
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

    // ---- async open submit: scope CROSS_COUNT before persistence and dispatch ----

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
        // Async submissions rewrite only CROSS_COUNT; other types pass through without fetching consents.
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
        // Reject a missing expectedResultType before touching the backend.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        QueryRequest noErt = new GeneralQueryRequest().setQuery(Map.of("fields", "x"));
        assertThatThrownBy(() -> svc.query(noErt, AggregateVariant.V1)).isInstanceOf(PicsureException.class);
        verifyNoInteractions(queryService);
    }

    @Test
    void propagatesQueryMetadataHeaderUnderRealHpdsHeaderName() {
        // Stub the literal HPDS header rather than the constant so this remains a contract check.
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
