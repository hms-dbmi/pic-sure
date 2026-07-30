package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.contracts.query.v3.SearchRequest;
import edu.harvard.hms.dbmi.avillach.query.search.SearchResults;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
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

    private Query query(ResultType type) {
        return new Query(null, null, null, null, type, null, null);
    }

    @Test
    void rejectsDisallowedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(query(ResultType.DATAFRAME))).isInstanceOf(PicsureException.class);
        verify(backend, never()).querySync(any());
    }

    @Test
    void rejectsMissingExpectedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(query(null))).isInstanceOf(PicsureException.class);
    }

    @Test
    void rejectsNullQueryWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = service(backend, new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(null)).isInstanceOf(PicsureException.class);
    }

    @Test
    void countBelowThresholdIsFloored() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("5"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(query(ResultType.COUNT));
        assertThat(out.getBody()).isEqualTo("< 10");
    }

    @Test
    void countAtOrAboveThresholdIsVarianceRandomized() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("100"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(query(ResultType.COUNT));
        assertThat(out.getBody()).matches("\\d+ ±3");
    }

    @Test
    void crossCountObfuscatesEachEntry() throws Exception {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        // the cross-count rewrite first searches consents, then the backend returns the cross counts
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\study\\\\a\\\\\":\"5\",\"\\\\study\\\\b\\\\\":\"100\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(query(ResultType.CROSS_COUNT));
        Map<String, String> body = mapper.readValue(out.getBody(), Map.class);
        assertThat(body.get("\\study\\a\\")).isEqualTo("< 10");
        assertThat(body.get("\\study\\b\\")).matches("\\d+ ±3");
    }

    /**
     * The typed rebuild, end to end: the query that reaches the backend carries the consent allow-list in {@code select} and
     * {@code CROSS_COUNT}, while every other component of the caller's query survives VERBATIM.
     *
     * <p>The authorization and genomic filters are deliberately NON-EMPTY. Both components null-coalesce to {@code List.of()} inside the
     * {@link Query} record, so an empty fixture would let a rebuild that dropped either positional argument still satisfy every assertion
     * here -- and these two are precisely the components whose silent loss WIDENS access: the gateway body-replaces the submission with
     * PSAMA's consent-mutated query, which carries injected {@code authorizationFilters}. Losing them would hand the open backend an
     * unrestricted query.
     */
    @Test
    void crossCountRebuildsTheQueryWithTheConsentAllowListInSelectAndKeepsEveryOtherComponent() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{}"));
        AggregateService svc = service(backend, new AggregateProperties());

        UUID picsureId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        PhenotypicFilter filter = new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\age\\", null, null, null, null);
        List<AuthorizationFilter> authFilters =
            List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c1")), new AuthorizationFilter("\\_topmed\\", Set.of("c2")));
        List<GenomicFilter> genomicFilters = List.of(new GenomicFilter("Gene_with_variant", List.of("APOE"), null, null));
        Query in = new Query(List.of("\\dropped\\"), authFilters, filter, genomicFilters, ResultType.CROSS_COUNT, picsureId, id);

        svc.querySync(in);

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(backend).querySync(cap.capture());
        Query sent = cap.getValue();
        assertThat(sent.select()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");
        assertThat(sent.expectedResultType()).isEqualTo(ResultType.CROSS_COUNT);
        // the access-narrowing components must survive the rebuild EXACTLY -- losing them would widen what the open backend answers
        assertThat(sent.authorizationFilters()).containsExactlyElementsOf(authFilters);
        assertThat(sent.genomicFilters()).containsExactlyElementsOf(genomicFilters);
        assertThat(sent.phenotypicClause()).isEqualTo(filter);
        assertThat(sent.picsureId()).isEqualTo(picsureId);
        assertThat(sent.id()).isEqualTo(id);
    }

    /** Same guarantee on the async submit, which is the path whose REWRITTEN query is what gets persisted and later re-read. */
    @Test
    void asyncCrossCountRebuildKeepsTheAccessNarrowingFilters() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        List<AuthorizationFilter> authFilters = List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c1")));
        List<GenomicFilter> genomicFilters = List.of(new GenomicFilter("Variant_frequency_as_text", null, 0.1f, 0.9f));
        svc.query(new Query(null, authFilters, null, genomicFilters, ResultType.CROSS_COUNT, null, null));

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(queryService).queryV3(org.mockito.ArgumentMatchers.eq("open"), cap.capture());
        assertThat(cap.getValue().authorizationFilters()).containsExactlyElementsOf(authFilters);
        assertThat(cap.getValue().genomicFilters()).containsExactlyElementsOf(genomicFilters);
    }

    /**
     * PRIVACY GUARD: every allow-listed type must have an EXPLICIT obfuscation treatment. The response dispatch has no raw-return default
     * -- an allow-listed type with no case throws rather than leaking its unobfuscated payload -- so widening
     * {@link AggregateService#ALLOWED_RESULT_TYPES} without deciding the new type's treatment fails HERE instead of shipping a leak.
     */
    @Test
    void everyAllowListedResultTypeHasAnExplicitObfuscationTreatment() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        // shape the stub to the type actually being dispatched, so each branch gets a body it can parse
        when(backend.querySync(any())).thenAnswer(inv -> {
            Query sent = inv.getArgument(0);
            return switch (sent.expectedResultType()) {
                case COUNT -> ResponseEntity.ok("12");
                // also serves the internal CROSS_COUNT lookup the categorical/continuous branches make
                case CROSS_COUNT -> ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}");
                default -> ResponseEntity.ok("{\"\\\\gender\\\\\":{\"male\":100}}");
            };
        });
        AggregateService svc = service(backend, new AggregateProperties());

        assertThat(AggregateService.ALLOWED_RESULT_TYPES).isNotEmpty();
        for (ResultType type : AggregateService.ALLOWED_RESULT_TYPES) {
            assertThatCode(() -> svc.querySync(query(type))).as("allow-listed type %s has no obfuscation treatment", type)
                .doesNotThrowAnyException();
        }
    }

    /** The consents lookup is a typed {@link SearchRequest} carrying the studies-consents path -- never an untyped envelope. */
    @Test
    void consentLookupIsATypedSearchRequestForTheStudiesConsentsPath() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{}"));
        AggregateService svc = service(backend, new AggregateProperties());

        svc.querySync(query(ResultType.CROSS_COUNT));

        verify(backend).search(new SearchRequest("\\_studies_consents\\"));
    }

    @Test
    void categoricalCrossCountObfuscatesViaCrossCountLookup() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        // first querySync call returns the raw categorical payload; the CROSS_COUNT lookup (getCrossCountForQuery)
        // is a second call to querySync with the rebuilt (CROSS_COUNT) query
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\gender\\\\\":{\"male\":5,\"female\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(query(ResultType.CATEGORICAL_CROSS_COUNT));
        assertThat(out.getBody()).contains("\"male\"").contains("< 10");
    }

    /** The categorical/continuous cross-count LOOKUP is rebuilt; the caller's own query keeps its original result type. */
    @Test
    void categoricalCrossCountLookupIsRebuiltButTheFirstHopKeepsTheCallersType() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\gender\\\\\":{\"male\":5}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        svc.querySync(query(ResultType.CATEGORICAL_CROSS_COUNT));

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(backend, org.mockito.Mockito.times(2)).querySync(cap.capture());
        assertThat(cap.getAllValues().get(0).expectedResultType()).isEqualTo(ResultType.CATEGORICAL_CROSS_COUNT);
        assertThat(cap.getAllValues().get(0).select()).isEmpty();
        assertThat(cap.getAllValues().get(1).expectedResultType()).isEqualTo(ResultType.CROSS_COUNT);
        assertThat(cap.getAllValues().get(1).select()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");
    }

    @Test
    void continuousCrossCountSuppressedWhenStudyConsentsBelowThreshold() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"< 10\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(query(ResultType.CONTINUOUS_CROSS_COUNT));
        assertThat(out.getBody()).isNull();
    }

    @Test
    void continuousCrossCountObfuscatesRawWhenNoVisualizationConfigured() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(query(ResultType.CONTINUOUS_CROSS_COUNT));
        assertThat(out.getBody()).contains("\"5\"").matches(s -> s.matches(".*±3.*"));
    }

    @Test
    void continuousCrossCountBinsViaVisualizationWhenConfigured() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        // viz answers with the named BinnedDistribution wrapper; the bins live under "bins", not at the root.
        when(backend.binContinuous(any())).thenReturn("{\"bins\":{\"\\\\age\\\\\":{\"0-10\":100}}}");
        AggregateProperties props = new AggregateProperties();
        props.setVisualizationUrl("http://viz.example");
        AggregateService svc = service(backend, props);

        ResponseEntity<String> out = svc.querySync(query(ResultType.CONTINUOUS_CROSS_COUNT));
        assertThat(out.getBody()).contains("\"0-10\"");
        // the binning hop carries the continuous counts themselves -- no envelope, no resourceUUID
        verify(backend).binContinuous(Map.of("\\age\\", Map.of("5", 100)));
    }

    // ---- async open submit (finding I6): CROSS_COUNT is consent-scoped, then persisted+dispatched via QueryService ----

    /** The query handed to QueryService for persistence+dispatch is the REWRITTEN cross-count query (consent-scoped), never the raw one. */
    @Test
    void asyncOpenCrossCountUsesSelectAndDispatchesViaQueryServiceV3() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        svc.query(query(ResultType.CROSS_COUNT));

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(queryService).queryV3(org.mockito.ArgumentMatchers.eq("open"), cap.capture());
        assertThat(cap.getValue().select()).containsExactly("\\study\\a\\consent\\", "\\study\\b\\consent\\");
        assertThat(cap.getValue().expectedResultType()).isEqualTo(ResultType.CROSS_COUNT);
    }

    @Test
    void asyncOpenNonCrossCountIsForwardedUnchanged() {
        // WAR parity: the async query() only rewrites CROSS_COUNT; other types pass through unchanged (and fetch no consents).
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        Query in = query(ResultType.COUNT);
        svc.query(in);

        verify(queryService).queryV3("open", in);
        verify(backend, never()).search(any());
    }

    @Test
    void asyncOpenQueryRejectsMissingExpectedResultTypeWith400() {
        // WAR parity: the async query() rejected a missing expectedResultType (MISSING_DATA) before touching the backend.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        QueryService queryService = mock(QueryService.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties(), queryService);

        assertThatThrownBy(() -> svc.query(query(null))).isInstanceOf(PicsureException.class);
        verifyNoInteractions(queryService);
    }

    @Test
    void propagatesQueryMetadataHeaderUnderRealHpdsHeaderName() {
        // NON-TAUTOLOGICAL (finding I5): the stub uses the REAL HPDS header literal "queryMetadata", NOT the constant. Under the old
        // "resultMetadata" bug the service would read getFirst("resultMetadata") == null from this response and DROP the header, so this
        // assertion would fail. It passes only when the constant resolves to the real name.
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any())).thenReturn(ResponseEntity.ok().header("queryMetadata", "rid").body("12"));
        AggregateService svc = service(backend, new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(query(ResultType.COUNT));
        assertThat(out.getHeaders().getFirst("queryMetadata")).isEqualTo("rid");
        assertThat(AggregateBackendClient.QUERY_METADATA_FIELD).isEqualTo("queryMetadata");
    }

    private SearchResults consentsSearch() {
        Map<String, Object> phenotypes = new LinkedHashMap<>();
        phenotypes.put("\\study\\a\\consent\\", Map.of());
        phenotypes.put("\\study\\b\\consent\\", Map.of());
        return new SearchResults(Map.of("phenotypes", phenotypes), null);
    }
}
