package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.operations.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;
import edu.harvard.hms.dbmi.avillach.query.operations.UpdateQueryRequest;

/**
 * DB-free port of the legacy WAR's {@code PicsureQueryServiceTest}: every place the brief expected a local
 * {@code QueryRepository}/{@code Query} entity now goes through {@link OperationsClient} instead (create/sync persist via
 * {@code operationsClient.save}/{@code update}; read ops load via {@code operationsClient.get}).
 *
 * <p>The v3 surface takes a BARE {@code Query} and returns {@link QueryStatusResponse}: there is no {@code QueryRequest} envelope on the
 * way in and no {@code resourceID} echo on the way out. The {@code QueryRequest}-shaped {@code query}/{@code queryV3} overloads that remain
 * are the aggregate service's (retyped in Task 10).
 */
class QueryServiceTest {

    OperationsClient operationsClient = mock(OperationsClient.class);
    ResourceWebClient hpds = mock(ResourceWebClient.class);
    HpdsProperties props = props();
    HpdsBackendSelector selector = new HpdsBackendSelector(props);
    QueryService service = new QueryService(operationsClient, hpds, selector);

    private static HpdsProperties props() {
        HpdsProperties p = new HpdsProperties();
        p.setAuthUrl("http://hpds/PIC-SURE");
        p.setOpenUrl("http://hpds/PIC-SURE");
        return p;
    }

    private Query query() {
        return new Query(List.of("\\age\\"), null, null, null, ResultType.COUNT, null, null);
    }

    /** The aggregate service's (still enveloped) call shape -- Task 10 retypes it. */
    private QueryRequest legacyReq() {
        return new GeneralQueryRequest().setQuery(Map.of("expectedResultType", "COUNT"));
    }

    private QueryStatus hpdsStatus(String rrid) {
        QueryStatus s = new QueryStatus();
        s.setResourceResultId(rrid);
        s.setStatus(PicSureStatus.PENDING);
        return s;
    }

    // --- create (typed v3 ingress) ---

    @Test
    void v3CreateReturnsATypedResponseCarryingThePicsureId() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatusResponse out = service.queryV3("auth", query());

        assertThat(out.picsureId()).isEqualTo(picsureId);
        assertThat(out.resourceResultId()).isEqualTo("rr-3");
        assertThat(out.status()).isEqualTo(edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus.PENDING);
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "3".equals(r.version())));
        verify(hpds).query(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), any()); // v3 base
    }

    @Test
    void v3CreatePersistsTheBareQueryUnderTheStoredEnvelopesQueryField() {
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        service.queryV3("auth", query());

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> r.query().contains("\"select\"") && r.query().contains("\\\\age\\\\")));
    }

    @Test
    void v3CreateFallsBackToThePicsureIdWhenHpdsReturnsNoResourceResultId() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus(null));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatusResponse out = service.queryV3("auth", query());

        assertThat(out.resourceResultId()).isEqualTo(picsureId.toString());
        verify(operationsClient)
            .update(eq(picsureId), argThat((UpdateQueryRequest u) -> picsureId.toString().equals(u.resourceResultId())));
    }

    @Test
    void v3CreateRejectsAMissingQuery() {
        assertThatThrownBy(() -> service.queryV3("auth", (Query) null))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- the aggregate service's enveloped overloads stay until Task 10 ---

    @Test
    void legacyEnvelopedCreateStillPersistsAndDispatchesForTheAggregatePath() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-1"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatus out = service.query("auth", legacyReq());

        assertThat(out.getPicsureResultId()).isEqualTo(picsureId);
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "rr-1".equals(r.resourceResultId()) && r.version() == null));
        verify(hpds).query(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), any()); // v1 base
    }

    // --- sync ---

    @Test
    void syncFallsBackToPicsureIdWhenNoMetadataHeader() {
        UUID picsureId = UUID.randomUUID();
        when(operationsClient.save(any())).thenReturn(picsureId);
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), null));

        var resp = service.querySync("auth", query(), "UI");

        assertThat(new String(resp.body())).isEqualTo("body");
        // resourceResultId persisted = the picsureId when no header (maintain WAR behavior)
        verify(operationsClient)
            .update(eq(picsureId), argThat((UpdateQueryRequest u) -> picsureId.toString().equals(u.resourceResultId())));
    }

    @Test
    void syncUsesMetadataHeaderAsResourceResultIdWhenPresent() {
        UUID picsureId = UUID.randomUUID();
        when(operationsClient.save(any())).thenReturn(picsureId);
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), "hpds-meta-id"));

        service.querySync("auth", query(), "UI");

        verify(operationsClient).update(eq(picsureId), argThat((UpdateQueryRequest u) -> "hpds-meta-id".equals(u.resourceResultId())));
    }

    // --- read ops: id only, no request body ---

    @Test
    void unknownQueryIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenThrow(new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Query not found: " + id));

        assertThatThrownBy(() -> service.status("auth", id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void statusUsesStoredResourceResultIdAndV1ForNullVersionAndPersistsNewStatus() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-7", "PENDING", null, null));
        QueryStatus s = hpdsStatus("rr-7");
        s.setStatus(PicSureStatus.AVAILABLE);
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-7"), any())).thenReturn(s);

        QueryStatusResponse out = service.status("auth", id);

        assertThat(out.picsureId()).isEqualTo(id);
        assertThat(out.status()).isEqualTo(edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus.AVAILABLE);
        verify(hpds).queryStatus(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), eq("rr-7"), any()); // v1 base
        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> "AVAILABLE".equals(u.status())));
    }

    @Test
    void resultDispatchesV1WhenStoredVersionIsNull() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-2", "PENDING", null, null));
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"), any())).thenReturn(ResponseEntity.ok(new byte[] {1}));

        service.result("auth", id);

        verify(hpds).queryResult(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), eq("rr-2"), any());
    }

    @Test
    void signedUrlDispatchesV3WhenStoredVersionIs3() { // THE BUG FIX
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", "PENDING", "3", null));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-1"), any()))
            .thenReturn(ResponseEntity.ok("{\"signedUrl\":\"https://s3/x\"}"));

        SignedUrlResponse out = service.signedUrl("auth", id);

        assertThat(out.signedUrl()).isEqualTo("https://s3/x");
        verify(hpds).queryResultSignedUrl(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), eq("rr-1"), any());
    }

    /** A downstream body that is not a {@code {"signedUrl": ...}} object is an upstream contract violation, not a null-carrying 200. */
    @Test
    void signedUrlWithoutASignedUrlFieldSurfacesAsAnUpstreamFailure() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", "PENDING", "3", null));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-1"), any())).thenReturn(ResponseEntity.ok("not json"));

        assertThatThrownBy(() -> service.signedUrl("auth", id)).isInstanceOf(HpdsCommunicationException.class);
    }

    // --- metadata ---

    @Test
    void metadataBuildsResultMetadataShapeWithoutCallingHpds() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(
            id, "{\"query\":\"q\"}", "rr-1", "AVAILABLE", null,
            java.util.Base64.getEncoder().encodeToString("{\"commonAreaUUID\":\"x\"}".getBytes())
        );
        when(operationsClient.get(id)).thenReturn(stored);

        QueryStatusResponse out = service.metadata(id);

        assertThat(out.picsureId()).isEqualTo(id);
        assertThat(out.resourceResultId()).isEqualTo("rr-1");
        assertThat(out.status()).isEqualTo(edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus.AVAILABLE);
        assertThat(out.resultMetadata()).containsKey("queryJson").containsKey("queryResultMetadata");
        assertThat((String) out.resultMetadata().get("queryResultMetadata")).contains("commonAreaUUID");
        org.mockito.Mockito.verifyNoInteractions(hpds);
    }

    @Test
    void metadataUnknownIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenThrow(new PicsureException(HttpStatus.NOT_FOUND, "not_found", "nope"));

        assertThatThrownBy(() -> service.metadata(id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(operationsClient).get(id); // the one lookup metadata is allowed to make
        verifyNoMoreInteractions(operationsClient); // pins that no second (e.g. common-area) lookup is attempted
    }

    /**
     * A row written before the federated removal stores a serialized FederatedQueryRequest. metadata must still read it: it parses the
     * stored blob into a plain {@code Map} via {@code MAPPER.readValue(..., Object.class)}, so {@code "@type"} is just another map key and
     * any value — known, unknown, or garbage — parses fine.
     */
    @Test
    @SuppressWarnings("unchecked")
    void metadataReadsALegacyFederatedStoredRow() {
        UUID id = UUID.randomUUID();
        String legacyBlob =
            "{\"@type\":\"FederatedQueryRequest\"," + "\"query\":{\"expectedResultType\":\"COUNT\"}," + "\"commonAreaUUID\":\""
                + UUID.randomUUID() + "\"," + "\"institutionOfOrigin\":\"BCH\"," + "\"requesterEmail\":\"alice@harvard.edu\"}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, legacyBlob, "rr-legacy", "AVAILABLE", "3", null));

        QueryStatusResponse result = service.metadata(id);

        Map<String, Object> queryJson = (Map<String, Object>) result.resultMetadata().get("queryJson");
        assertThat(queryJson).isNotNull();
        assertThat(queryJson).containsKey("query");
        assertThat(result.picsureId()).isEqualTo(id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataTranslatesStoredV1QueryToV3Shape() {
        UUID id = UUID.randomUUID();
        String v1Blob = "{\"resourceUUID\":\"" + UUID.randomUUID() + "\",\"query\":{"
            + "\"expectedResultType\":\"COUNT\",\"categoryFilters\":{\"\\\\sex\\\\\":[\"M\"]}}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v1Blob, "rr-1", "AVAILABLE", null, null));

        QueryStatusResponse out = service.metadata(id);

        Map<String, Object> queryJson = (Map<String, Object>) out.resultMetadata().get("queryJson");
        assertThat(queryJson).isNotNull();
        assertThat(queryJson).containsKey("resourceUUID"); // wrapper preserved
        Map<String, Object> inner = (Map<String, Object>) queryJson.get("query");
        assertThat(inner).containsKey("phenotypicClause"); // v3 field present
        assertThat(inner).doesNotContainKey("categoryFilters"); // v1 field gone
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataFallsBackToUntranslatedOnUntranslatableV1Row() {
        UUID id = UUID.randomUUID();
        // two non-empty variantInfoFilters groups -> UntranslatableQueryException -> fall back to raw
        String v1Blob =
            "{\"query\":{\"expectedResultType\":\"COUNT\",\"categoryFilters\":{\"\\\\sex\\\\\":[\"M\"]}," + "\"variantInfoFilters\":["
                + "{\"categoryVariantInfoFilters\":{\"Gene_with_variant\":[\"A\"]},\"numericVariantInfoFilters\":{}},"
                + "{\"categoryVariantInfoFilters\":{\"Gene_with_variant\":[\"B\"]},\"numericVariantInfoFilters\":{}}]}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v1Blob, "rr-1", "AVAILABLE", null, null));

        QueryStatusResponse out = service.metadata(id);

        Map<String, Object> queryJson = (Map<String, Object>) out.resultMetadata().get("queryJson");
        Map<String, Object> inner = (Map<String, Object>) queryJson.get("query");
        assertThat(inner).containsKey("variantInfoFilters"); // untranslated v1 body preserved
        assertThat(inner).containsKey("categoryFilters"); // raw v1 body returned in full, not partially translated
        assertThat(inner).doesNotContainKey("phenotypicClause"); // no translation output leaked in
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataLeavesV3StoredRowUntranslated() {
        UUID id = UUID.randomUUID();
        String v3Blob = "{\"query\":{" + "\"expectedResultType\":\"COUNT\",\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\","
            + "\"conceptPath\":\"\\\\x\\\\\"},\"genomicFilters\":[]}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v3Blob, "rr-1", "AVAILABLE", "3", null));

        QueryStatusResponse out = service.metadata(id);

        Map<String, Object> queryJson = (Map<String, Object>) out.resultMetadata().get("queryJson");
        Map<String, Object> inner = (Map<String, Object>) queryJson.get("query");
        // non-null structural assertion: a wrongly-applied v1 translation would produce a different/empty clause,
        // so asserting the exact conceptPath survives discriminates "left alone" from "translated".
        assertThat(inner.get("phenotypicClause")).isNotNull();
        Map<String, Object> clause = (Map<String, Object>) inner.get("phenotypicClause");
        assertThat(clause.get("conceptPath")).isEqualTo("\\x\\");
    }
}
