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
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
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
    void continuousCrossCountSuppressedWhenStudyConsentsIsRawBelowThresholdCount() {
        // regression: the consents lookup returns the RAW backend body; a raw "5" (below threshold, nonzero) must suppress the
        // whole continuous response, not just floor the individual bins
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"5\"}"));
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
        when(backend.binContinuous(any(), eq(AggregateVariant.V1))).thenReturn("{\"bins\":{\"\\\\age\\\\\":{\"0-10\":100}}}");
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        AggregateService svc = service(backend, props);

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).contains("\"0-10\"");
        verify(backend).binContinuous(any(), eq(AggregateVariant.V1));
    }

    /**
     * The open continuous path answers with exactly the body it produced when the visualization service sent the bins as a bare map. The
     * expected text was captured from that code for the same binning input: four bins in ascending order, the last below the threshold.
     */
    @Test
    void continuousCrossCountOutputIsUnchangedByTheBinningResponseRecord() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V3))).thenReturn(
            ResponseEntity.ok("{\"\\\\demographics\\\\AGE\\\\\":{\"20\":5,\"30\":12,\"40\":25,\"50\":40,\"60\":31,\"70\":18,\"80\":6}}")
        ).thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any(), eq(AggregateVariant.V3))).thenReturn(
            "{\"bins\":{\"\\\\demographics\\\\AGE\\\\\":{\"20.0 - 40.0\":17,\"40.0 - 60.0\":65,\"60.0 - 80.0\":49,\"80.0 +\":6}}}"
        );
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        AggregateService svc = service(backend, props);

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V3);

        assertThat(out.getBody()).isEqualTo(
            "{\"\\\\demographics\\\\AGE\\\\\":{\"20.0 - 40.0\":{\"count\":15,\"display\":\"15 ±3\",\"variance\":3},"
                + "\"40.0 - 60.0\":{\"count\":63,\"display\":\"63 ±3\",\"variance\":3},"
                + "\"60.0 - 80.0\":{\"count\":47,\"display\":\"47 ±3\",\"variance\":3},"
                + "\"80.0 +\":{\"count\":0,\"display\":\"< 10\",\"variance\":9}}}"
        );
    }

    /**
     * A visualization service older than the response record answers with the bare map, which parses to a record with no bins. That is
     * rejected as a failed upstream call and never answered as an empty chart.
     */
    @Test
    void continuousCrossCountRejectsABinningResponseWithoutBins() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V3)))
            .thenReturn(ResponseEntity.ok("{\"\\\\demographics\\\\AGE\\\\\":{\"20\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any(), eq(AggregateVariant.V3))).thenReturn("{\"\\\\demographics\\\\AGE\\\\\":{\"20.0\":100}}");
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        AggregateService svc = service(backend, props);

        assertThatThrownBy(() -> svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V3))
            .isInstanceOf(HpdsCommunicationException.class).hasMessage("Visualization bin/continuous response carried no bins");
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
