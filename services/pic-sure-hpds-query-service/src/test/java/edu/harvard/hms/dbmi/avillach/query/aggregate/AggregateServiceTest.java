package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.query.QueryService;

class AggregateServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObfuscationService obfuscation() {
        return new ObfuscationService(new AggregateProperties(), new VisualizationFormatter());
    }

    /** obfuscation-path tests don't exercise persistence; a throwaway QueryService mock keeps their construction terse. */
    private AggregateService service(AggregateBackendClient backend, AggregateProperties props) {
        return new AggregateService(backend, obfuscation(), props, mock(QueryService.class), new ConceptMetadataLookup(backend));
    }

    private Query sync(String expectedResultType) {
        return new Query(null, null, null, null, ResultType.valueOf(expectedResultType), null, null);
    }

    private Query withoutResultType() {
        return new Query(List.of("\\demographics\\SEX\\"), null, null, null, null, null, null);
    }

    @Test
    void rejectsDisallowedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(sync("DATAFRAME"))).isInstanceOf(PicsureException.class);
        verify(backend, never()).querySync(any());
    }

    @Test
    void rejectsMissingExpectedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(withoutResultType())).isInstanceOf(PicsureException.class);
    }

    @Test
    void rejectsNullQueryWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(null)).isInstanceOf(PicsureException.class);
    }

    @Test
    void countReturnsObfuscatedStudyConsentsTotal() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any()))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"101\",\"\\\\_studies_consents\\\\phs1\\\\HMB\\\\\":\"3\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"));
        assertThat(out.getBody()).isEqualTo("105 ±10");

        // HPDS is asked for the study-consents CROSS_COUNT, never a raw COUNT
        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(backend).querySync(cap.capture());
        Query sent = (Query) cap.getValue().getQuery();
        assertThat(sent.expectedResultType()).isEqualTo(ResultType.CROSS_COUNT);
        assertThat(sent.select()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");
    }

    @Test
    void countBelowFiveIsSuppressed() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"4\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        assertThat(svc.querySync(sync("COUNT")).getBody()).isEqualTo("< 5");
    }

    @Test
    void crossCountObfuscatesEachEntry() throws Exception {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        // changeQueryToOpenCrossCount first searches consents, then the backend returns the cross counts
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any()))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"101\",\"\\\\_studies_consents\\\\phs1\\\\HMB\\\\\":\"3\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CROSS_COUNT"));
        Map<String, String> body = mapper.readValue(out.getBody(), Map.class);
        assertThat(body.get("\\_studies_consents\\")).isEqualTo("105 ±10");
        assertThat(body.get("\\_studies_consents\\phs1\\HMB\\")).isEqualTo("< 5");
    }

    @Test
    void crossCountUsesSelectField() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{}"));
        AggregateService svc = service(backend, new AggregateProperties());

        svc.querySync(sync("CROSS_COUNT"));

        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(backend).querySync(cap.capture());
        Query sent = (Query) cap.getValue().getQuery();
        assertThat(sent.select()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");
        assertThat(sent.expectedResultType()).isEqualTo(ResultType.CROSS_COUNT);
    }

    @Test
    void categoricalCrossCountObfuscatesViaCrossCountLookup() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        // first querySync call returns the raw categorical payload; the CROSS_COUNT lookup (getCrossCountForQuery)
        // is a second call to querySync with the mutated (CROSS_COUNT) request
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\gender\\\\\":{\"male\":5,\"female\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CATEGORICAL_CROSS_COUNT"));
        // male (5) is folded into the grouped bucket rather than named
        assertThat(out.getBody()).doesNotContain("\"male\"").contains("\"female\"").contains("Other (grouped)").contains("< 10");
    }

    @Test
    void categoricalChartOmitsTheGroupedBucketWhenEveryValueIsNamed() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenAnswer(inv -> {
            QueryRequest req = inv.getArgument(0);
            if ("\\sex\\".equals(req.getQuery())) {
                return new SearchResults()
                    .setResults(Map.of("phenotypes", Map.of("\\sex\\", Map.of("categoryValues", List.of("Female", "Male")))));
            }
            return consentsSearch();
        });
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\sex\\\\\":{\"Female\":212,\"Male\":190}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));

        ResponseEntity<String> out = service(backend, new AggregateProperties()).querySync(sync("CATEGORICAL_CROSS_COUNT"));
        assertThat(out.getBody()).contains("Female").contains("Male").doesNotContain("Other (grouped)");
    }

    @Test
    void categoricalCrossCountSuppressedWhenCohortBelowFifty() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\gender\\\\\":{\"male\":20,\"female\":29}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"49\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        assertThat(svc.querySync(sync("CATEGORICAL_CROSS_COUNT")).getBody()).isNull();
    }

    @Test
    void continuousCrossCountSuppressedWhenStudyConsentsBelowThreshold() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"< 10\"}"));
        AggregateService svc = service(backend, withVisualization());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"));
        assertThat(out.getBody()).isNull();
        verify(backend, never()).binContinuous(any());
    }

    @Test
    void continuousCrossCountSuppressedWhenStudyConsentsIsRawBelowThresholdCount() {
        // regression: the consents lookup returns the RAW backend body; a raw "5" (below threshold, nonzero) must suppress the
        // whole continuous response, not just floor the individual bins
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"5\"}"));
        AggregateService svc = service(backend, withVisualization());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"));
        assertThat(out.getBody()).isNull();
        verify(backend, never()).binContinuous(any());
    }

    @Test
    void continuousChartIsDisabledWhenNoVisualizationIsConfigured() {
        // Unbinned counts would be keyed by every observed value, so nothing is returned instead.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"19.3\":100,\"63.8\":1}}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"));
        assertThat(out.getBody()).isNull();
        verify(backend).querySync(any()); // the continuous query only: no cross-count lookup
        verify(backend, never()).search(any());
        verify(backend, never()).binContinuous(any());
    }

    @Test
    void continuousCrossCountBinsViaVisualizationWhenConfigured() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        stubSearchWithRange(backend, "\\age\\");
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any())).thenReturn("{\"bins\":{\"\\\\age\\\\\":{\"0-10\":100}}}");
        AggregateService svc = service(backend, withVisualization());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"));
        assertThat(out.getBody()).contains("\"0-10\"");
        verify(backend).binContinuous(argThat(r -> r.ranges().equals(Map.of("\\age\\", new ChartRange(0, 120))) && r.maxBins() == 50));
    }

    @Test
    void continuousChartSpansTheConceptsOverallRangeNotTheQueryRange() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        stubSearchWithRange(backend, "\\age\\");
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"30\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any())).thenReturn("{\"bins\":{\"\\\\age\\\\\":{\"0.0 - 120.0\":100}}}");
        AggregateService svc = service(backend, withVisualization());

        PhenotypicFilter ageFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\age\\", null, 18.0, 65.0, false);
        svc.querySync(new Query(List.of("\\age\\"), null, ageFilter, null, ResultType.CONTINUOUS_CROSS_COUNT, null, null));

        verify(backend).binContinuous(argThat(r -> r.ranges().equals(Map.of("\\age\\", new ChartRange(0, 120)))));
    }

    @Test
    void continuousConceptWithNoKnownRangeIsLeftOut() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"30\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any())).thenReturn("{\"bins\":{}}");

        service(backend, withVisualization()).querySync(sync("CONTINUOUS_CROSS_COUNT"));
        verify(backend).binContinuous(argThat(r -> r.query().isEmpty() && r.ranges().isEmpty()));
    }

    /**
     * The open continuous path answers with exactly the body it produced when the visualization service sent the bins as a bare map. The
     * expected text was captured from that code for the same binning input: four bins in ascending order, the last below the threshold.
     */
    @Test
    void continuousCrossCountOutputIsUnchangedByTheBinningResponseRecord() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        stubSearchWithRange(backend, "\\demographics\\AGE\\");
        when(backend.querySync(any())).thenReturn(
            ResponseEntity.ok("{\"\\\\demographics\\\\AGE\\\\\":{\"20\":5,\"30\":12,\"40\":25,\"50\":40,\"60\":31,\"70\":18,\"80\":6}}")
        ).thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any())).thenReturn(
            "{\"bins\":{\"\\\\demographics\\\\AGE\\\\\":{\"20.0 - 40.0\":17,\"40.0 - 60.0\":65,\"60.0 - 80.0\":49,\"80.0 +\":6}}}"
        );
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        AggregateService svc = service(backend, props);

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"));

        assertThat(out.getBody()).isEqualTo(
            "{\"\\\\demographics\\\\AGE\\\\\":{\"20.0 - 40.0\":{\"count\":20,\"display\":\"20 ±5\",\"variance\":5},"
                + "\"40.0 - 60.0\":{\"count\":65,\"display\":\"65 ±5\",\"variance\":5},"
                + "\"60.0 - 80.0\":{\"count\":50,\"display\":\"50 ±5\",\"variance\":5},"
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
        stubSearchWithRange(backend, "\\demographics\\AGE\\");
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\demographics\\\\AGE\\\\\":{\"20\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        when(backend.binContinuous(any())).thenReturn("{\"\\\\demographics\\\\AGE\\\\\":{\"20.0\":100}}");
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        AggregateService svc = service(backend, props);

        assertThatThrownBy(() -> svc.querySync(sync("CONTINUOUS_CROSS_COUNT"))).isInstanceOf(HpdsCommunicationException.class)
            .hasMessage("Visualization bin/continuous response carried no bins");
    }

    // ---- async open submit: scope CROSS_COUNT before persistence and dispatch ----

    @Test
    void asyncOpenCrossCountUsesSelectAndDispatchesViaQueryServiceV3() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        QueryService queryService = mock(QueryService.class);
        AggregateService svc =
            new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService, new ConceptMetadataLookup(backend));

        svc.query(sync("CROSS_COUNT"));

        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryService).query(eq("open"), cap.capture());
        Query sent = (Query) cap.getValue().getQuery();
        assertThat(sent.select()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");
        assertThat(sent.expectedResultType()).isEqualTo(ResultType.CROSS_COUNT);
    }

    @Test
    void asyncOpenNonCrossCountIsForwardedUnchanged() {
        // Async submissions rewrite only CROSS_COUNT; other types pass through without fetching consents.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        QueryService queryService = mock(QueryService.class);
        AggregateService svc =
            new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService, new ConceptMetadataLookup(backend));

        Query submitted = sync("COUNT");
        svc.query(submitted);

        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryService).query(eq("open"), cap.capture());
        assertThat(cap.getValue().getQuery()).isSameAs(submitted);
        verify(backend, never()).search(any());
    }

    @Test
    void asyncOpenQueryRejectsMissingExpectedResultTypeWith400() {
        // Reject a missing expectedResultType before touching the backend.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        QueryService queryService = mock(QueryService.class);
        AggregateService svc =
            new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService, new ConceptMetadataLookup(backend));

        assertThatThrownBy(() -> svc.query(withoutResultType())).isInstanceOf(PicsureException.class);
        verifyNoInteractions(queryService);
    }

    @Test
    void propagatesQueryMetadataHeaderUnderRealHpdsHeaderName() {
        // Stub the literal HPDS header rather than the constant so this remains a contract check.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok().header("queryMetadata", "rid").body("[]"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("INFO_COLUMN_LISTING"));
        assertThat(out.getHeaders().getFirst("queryMetadata")).isEqualTo("rid");
        assertThat(AggregateBackendClient.QUERY_METADATA_FIELD).isEqualTo("queryMetadata");
    }

    private static AggregateProperties withVisualization() {
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        return props;
    }

    /** Answers the study-consents search, and a concept search for {@code concept} with an overall range of 0 to 120. */
    private void stubSearchWithRange(AggregateBackendClient backend, String concept) {
        when(backend.search(any())).thenAnswer(inv -> {
            QueryRequest req = inv.getArgument(0);
            if (concept.equals(req.getQuery())) {
                return new SearchResults().setResults(Map.of("phenotypes", Map.of(concept, Map.of("min", 0, "max", 120))));
            }
            return consentsSearch();
        });
    }

    private SearchResults consentsSearch() {
        Map<String, Object> phenotypes = new LinkedHashMap<>();
        phenotypes.put("\\study\\a\\consent\\", Map.of());
        phenotypes.put("\\study\\b\\consent\\", Map.of());
        return new SearchResults().setResults(Map.of("phenotypes", phenotypes));
    }
}
