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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import edu.harvard.dbmi.avillach.contracts.internal.SaveQueryRequest;
import edu.harvard.dbmi.avillach.contracts.internal.StoredQuery;
import edu.harvard.dbmi.avillach.contracts.internal.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import edu.harvard.dbmi.avillach.contracts.query.v3.QueryStatusResponse;
import edu.harvard.dbmi.avillach.contracts.query.v3.SignedUrlResponse;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * DB-free port of the legacy WAR's {@code PicsureQueryServiceTest}: every place the brief expected a local
 * {@code QueryRepository}/{@code Query} entity now goes through {@link OperationsClient} instead (create/sync persist via
 * {@code operationsClient.save}/{@code update}; read ops load via {@code operationsClient.get}).
 *
 * <p>Both ends are typed. The v3 surface takes a BARE {@code Query} and returns {@link QueryStatusResponse}; the downstream HPDS hop takes
 * the same bare {@code Query} and returns the same contract records; and the store DTOs are the shared
 * {@code edu.harvard.dbmi.avillach.contracts.internal} records, whose {@code status} is the typed {@link PicSureStatus} rather than a bare
 * string. Nothing on this service's own surface carries a {@code QueryRequest} envelope any more -- the aggregate (open) path shares the
 * same bare create hop -- and the wrapper survives only inside the PERSISTED blob, until Task 15 migrates it.
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

    private QueryStatusResponse hpdsStatus(String rrid) {
        return new QueryStatusResponse(null, PicSureStatus.PENDING, null, rrid, 0L, 0L, 0L, 0L, null);
    }

    // --- create (typed v3 ingress) ---

    @Test
    void v3CreateReturnsATypedResponseCarryingThePicsureId() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatusResponse out = service.queryV3("auth", query());

        assertThat(out.picsureId()).isEqualTo(picsureId);
        assertThat(out.resourceResultId()).isEqualTo("rr-3");
        assertThat(out.status()).isEqualTo(PicSureStatus.PENDING);
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "3".equals(r.version())));
        verify(hpds).query(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), any(Query.class)); // v3 base
    }

    /** The status the store is told about is the typed enum, not a stringly-typed name that only happened to parse. */
    @Test
    void v3CreatePersistsTheTypedStatusFromHpds() {
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        service.queryV3("auth", query());

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> r.status() == PicSureStatus.PENDING));
    }

    /** The BARE query is what goes downstream: no envelope is built for the HPDS hop any more. */
    @Test
    void v3CreateForwardsTheBareQueryDownstream() {
        Query q = query();
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        service.queryV3("auth", q);

        verify(hpds).query(any(HpdsTarget.class), eq(q));
    }

    @Test
    void v3CreatePersistsTheBareQueryUnderTheStoredEnvelopesQueryField() {
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        service.queryV3("auth", query());

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> r.query().contains("\"select\"") && r.query().contains("\\\\age\\\\")));
    }

    /**
     * Pins the PERSISTED wrapper byte-for-byte. It used to come from serializing api-model's {@code GeneralQueryRequest}, whose
     * {@code @JsonTypeInfo} produced the leading {@code "@type"} plus an empty {@code resourceCredentials} and a null {@code resourceUUID};
     * that module is retired but the stored rows and their readers (operations-service's {@code /dispatch} credential strip, {@code
     * /metadata}'s v1 translation) are not, so the exact field set and ORDER must survive the retirement unchanged.
     */
    @Test
    void v3CreatePersistsTheLegacyWrapperShapeByteForByte() {
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        service.queryV3("auth", query());

        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient).save(saved.capture());
        assertThat(saved.getValue().query()).startsWith("{\"@type\":\"GeneralQueryRequest\",\"resourceCredentials\":{},\"query\":{")
            .endsWith(",\"resourceUUID\":null}");
    }

    @Test
    void v3CreateFallsBackToThePicsureIdWhenHpdsReturnsNoResourceResultId() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(hpdsStatus(null));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatusResponse out = service.queryV3("auth", query());

        assertThat(out.resourceResultId()).isEqualTo(picsureId.toString());
        verify(operationsClient)
            .update(eq(picsureId), argThat((UpdateQueryRequest u) -> picsureId.toString().equals(u.resourceResultId())));
    }

    @Test
    void v3CreateRejectsAMissingQuery() {
        assertThatThrownBy(() -> service.queryV3("auth", null))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** A 200 with no parseable body is an upstream contract violation, not a persisted row with a null result id. */
    @Test
    void v3CreateWithNoDownstreamStatusSurfacesAsAnUpstreamFailure() {
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(null);

        assertThatThrownBy(() -> service.queryV3("auth", query())).isInstanceOf(HpdsCommunicationException.class);
        verify(operationsClient, org.mockito.Mockito.never()).save(any());
    }

    /** The aggregate (open) submit shares this same create path -- it is the same bare hop, differing only in the backend segment. */
    @Test
    void theOpenBackendCreateUsesTheSameBareV3Hop() {
        when(hpds.query(any(HpdsTarget.class), any(Query.class))).thenReturn(hpdsStatus("rr-open"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        QueryStatusResponse out = service.queryV3("open", query());

        assertThat(out.resourceResultId()).isEqualTo("rr-open");
        verify(hpds).query(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), any(Query.class));
    }

    // --- sync ---

    @Test
    void syncFallsBackToPicsureIdWhenNoMetadataHeader() {
        UUID picsureId = UUID.randomUUID();
        when(operationsClient.save(any())).thenReturn(picsureId);
        when(hpds.querySync(any(HpdsTarget.class), any(Query.class), any()))
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
        when(hpds.querySync(any(HpdsTarget.class), any(Query.class), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), "hpds-meta-id"));

        service.querySync("auth", query(), "UI");

        verify(operationsClient).update(eq(picsureId), argThat((UpdateQueryRequest u) -> "hpds-meta-id".equals(u.resourceResultId())));
    }

    @Test
    void syncForwardsTheBareQueryDownstream() {
        Query q = query();
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        when(hpds.querySync(any(HpdsTarget.class), any(Query.class), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), null));

        service.querySync("auth", q, "UI");

        verify(hpds).querySync(any(HpdsTarget.class), eq(q), eq("UI"));
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
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-7", PicSureStatus.PENDING, null, null));
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-7")))
            .thenReturn(new QueryStatusResponse(null, PicSureStatus.AVAILABLE, null, "rr-7", 0L, 0L, 0L, 0L, null));

        QueryStatusResponse out = service.status("auth", id);

        assertThat(out.picsureId()).isEqualTo(id);
        assertThat(out.status()).isEqualTo(PicSureStatus.AVAILABLE);
        verify(hpds).queryStatus(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), eq("rr-7")); // v1 base
        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> u.status() == PicSureStatus.AVAILABLE));
    }

    @Test
    void resultDispatchesV1WhenStoredVersionIsNull() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-2", PicSureStatus.PENDING, null, null));
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"))).thenReturn(ResponseEntity.ok(new byte[] {1}));

        service.result("auth", id);

        verify(hpds).queryResult(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), eq("rr-2"));
    }

    @Test
    void signedUrlDispatchesV3WhenStoredVersionIs3() { // THE BUG FIX
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", PicSureStatus.PENDING, "3", null));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-1"))).thenReturn(new SignedUrlResponse("https://s3/x"));

        SignedUrlResponse out = service.signedUrl("auth", id);

        assertThat(out.signedUrl()).isEqualTo("https://s3/x");
        verify(hpds).queryResultSignedUrl(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), eq("rr-1"));
    }

    /** A downstream body that carries no {@code signedUrl} is an upstream contract violation, not a null-carrying 200. */
    @Test
    void signedUrlWithoutASignedUrlFieldSurfacesAsAnUpstreamFailure() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", PicSureStatus.PENDING, "3", null));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-1"))).thenReturn(new SignedUrlResponse(null));

        assertThatThrownBy(() -> service.signedUrl("auth", id)).isInstanceOf(HpdsCommunicationException.class);
    }

    // --- metadata ---

    @Test
    void metadataBuildsResultMetadataShapeWithoutCallingHpds() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(
            id, "{\"query\":\"q\"}", "rr-1", PicSureStatus.AVAILABLE, null,
            java.util.Base64.getEncoder().encodeToString("{\"commonAreaUUID\":\"x\"}".getBytes())
        );
        when(operationsClient.get(id)).thenReturn(stored);

        QueryStatusResponse out = service.metadata(id);

        assertThat(out.picsureId()).isEqualTo(id);
        assertThat(out.resourceResultId()).isEqualTo("rr-1");
        assertThat(out.status()).isEqualTo(PicSureStatus.AVAILABLE);
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
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, legacyBlob, "rr-legacy", PicSureStatus.AVAILABLE, "3", null));

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
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v1Blob, "rr-1", PicSureStatus.AVAILABLE, null, null));

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
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v1Blob, "rr-1", PicSureStatus.AVAILABLE, null, null));

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
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v3Blob, "rr-1", PicSureStatus.AVAILABLE, "3", null));

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
