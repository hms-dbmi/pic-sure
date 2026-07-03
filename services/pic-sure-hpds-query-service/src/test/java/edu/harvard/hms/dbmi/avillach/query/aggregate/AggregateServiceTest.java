package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

class AggregateServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObfuscationService obfuscation() {
        AggregateProperties p = new AggregateProperties();
        p.getObfuscation().setThreshold(10);
        p.getObfuscation().setVariance(3);
        p.getObfuscation().setSalt("fixed");
        return new ObfuscationService(p, new VisualizationFormatter());
    }

    private QueryRequest sync(String expectedResultType) {
        return new GeneralQueryRequest().setQuery(Map.of("expectedResultType", expectedResultType));
    }

    @Test
    void rejectsDisallowedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(sync("DATAFRAME"), AggregateVariant.V1)).isInstanceOf(PicsureException.class);
        verify(backend, never()).querySync(any(), any());
    }

    @Test
    void rejectsMissingExpectedResultTypeWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());
        QueryRequest noErt = new GeneralQueryRequest().setQuery(Map.of("fields", "x"));
        assertThatThrownBy(() -> svc.querySync(noErt, AggregateVariant.V1)).isInstanceOf(PicsureException.class);
    }

    @Test
    void rejectsNullQueryWith400() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());
        assertThatThrownBy(() -> svc.querySync(new GeneralQueryRequest(), AggregateVariant.V1)).isInstanceOf(PicsureException.class);
    }

    @Test
    void countBelowThresholdIsFloored() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("5"));
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).isEqualTo("< 10");
    }

    @Test
    void countAtOrAboveThresholdIsVarianceRandomized() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("100"));
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

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
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

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
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

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
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

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
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CATEGORICAL_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).contains("\"male\"").contains("< 10");
    }

    @Test
    void continuousCrossCountSuppressedWhenStudyConsentsBelowThreshold() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":1}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"< 10\"}"));
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).isNull();
    }

    @Test
    void continuousCrossCountObfuscatesRawWhenNoVisualizationConfigured() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        when(backend.querySync(any(), eq(AggregateVariant.V1))).thenReturn(ResponseEntity.ok("{\"\\\\age\\\\\":{\"5\":100}}"))
            .thenReturn(ResponseEntity.ok("{\"\\\\_studies_consents\\\\\":\"500\"}"));
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

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
        AggregateService svc = new AggregateService(backend, obfuscation(), props);

        ResponseEntity<String> out = svc.querySync(sync("CONTINUOUS_CROSS_COUNT"), AggregateVariant.V1);
        assertThat(out.getBody()).contains("\"0-10\"");
        verify(backend).binContinuous(any(), eq(AggregateVariant.V1));
    }

    @Test
    void queryWithCrossCountAlteredButNotObfuscated() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenReturn(consentsSearch());
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

        svc.query(sync("CROSS_COUNT"));

        ArgumentCaptor<QueryRequest> cap = ArgumentCaptor.forClass(QueryRequest.class);
        verify(backend).query(cap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> query = mapper.convertValue(cap.getValue().getQuery(), Map.class);
        assertThat(query).containsKey("crossCountFields");
    }

    @Test
    void resultIsRawNotObfuscated() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.result(eq("rid"), any())).thenReturn("5");
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

        String out = svc.result("rid", sync("COUNT"));
        assertThat(out).isEqualTo("5");
    }

    @Test
    void propagatesResultMetadataHeader() {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.querySync(any(), eq(AggregateVariant.V1)))
            .thenReturn(ResponseEntity.ok().header(AggregateBackendClient.QUERY_METADATA_FIELD, "rid").body("12"));
        AggregateService svc = new AggregateService(backend, obfuscation(), new AggregateProperties());

        ResponseEntity<String> out = svc.querySync(sync("COUNT"), AggregateVariant.V1);
        assertThat(out.getHeaders().getFirst(AggregateBackendClient.QUERY_METADATA_FIELD)).isEqualTo("rid");
    }

    private SearchResults consentsSearch() {
        Map<String, Object> phenotypes = new LinkedHashMap<>();
        phenotypes.put("\\study\\a\\consent\\", Map.of());
        phenotypes.put("\\study\\b\\consent\\", Map.of());
        return new SearchResults().setResults(Map.of("phenotypes", phenotypes));
    }
}
