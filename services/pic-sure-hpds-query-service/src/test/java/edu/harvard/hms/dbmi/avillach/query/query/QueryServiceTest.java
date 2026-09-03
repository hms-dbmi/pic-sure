package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.mockito.InOrder;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.operations.SaveQueryRequest;
import edu.harvard.hms.dbmi.avillach.query.operations.StoredQuery;
import edu.harvard.hms.dbmi.avillach.query.operations.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * DB-free port of the legacy WAR's {@code PicsureQueryServiceTest}: every place the brief expected a local
 * {@code QueryRepository}/{@code Query} entity now goes through {@link OperationsClient} instead (create/sync persist via
 * {@code operationsClient.save}/{@code update}; read ops load via {@code operationsClient.get}).
 */
class QueryServiceTest {

    OperationsClient operationsClient = mock(OperationsClient.class);
    ResourceWebClient hpds = mock(ResourceWebClient.class);
    HpdsProperties props = props();
    HpdsBackendSelector selector = new HpdsBackendSelector(props);
    ConsentAuthorizationService consent = mock(ConsentAuthorizationService.class);
    QueryService service = new QueryService(operationsClient, hpds, selector, consent);

    private static HpdsProperties props() {
        HpdsProperties p = new HpdsProperties();
        p.setAuthUrl("http://hpds/PIC-SURE");
        p.setOpenUrl("http://hpds/PIC-SURE");
        return p;
    }

    private QueryRequest req() {
        GeneralQueryRequest r = new GeneralQueryRequest();
        r.setQuery("q");
        r.setResourceUUID(UUID.randomUUID());
        return r;
    }

    private QueryStatus hpdsStatus(String rrid) {
        QueryStatus s = new QueryStatus();
        s.setResourceResultId(rrid);
        s.setStatus(PicSureStatus.PENDING);
        return s;
    }

    // --- create ---

    @Test
    void createPersistsViaOperationsClientAndTranslatesIds() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-1"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatus out = service.query("auth", req());

        assertThat(out.getPicsureResultId()).isEqualTo(picsureId);
        assertThat(out.getResourceResultId()).isEqualTo("rr-1");
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "rr-1".equals(r.resourceResultId()) && r.version() == null));
        verify(hpds).query(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), any()); // v1 base
    }

    @Test
    void createFallbackCopiesPicsureIdWhenHpdsHasNoResourceResultId() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus(null)); // HPDS returned no id
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatus out = service.query("auth", req());

        assertThat(out.getResourceResultId()).isEqualTo(out.getPicsureResultId().toString()); // fallback
        verify(operationsClient)
            .update(eq(picsureId), argThat((UpdateQueryRequest u) -> picsureId.toString().equals(u.resourceResultId())));
    }

    @Test
    void createV3StampsVersion3() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        service.queryV3("auth", req());

        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "3".equals(r.version())));
        verify(hpds).query(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), any()); // v3 base
    }

    @Test
    void createScopesBeforeCallingHpdsOrPersistence() {
        ConsentAuthorizationService consent = mock(ConsentAuthorizationService.class);
        QueryService scopedService = new QueryService(operationsClient, hpds, selector, consent);
        QueryRequest request = req();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        scopedService.queryV3("auth", request, "Bearer caller-token");

        InOrder order = inOrder(consent, hpds, operationsClient);
        order.verify(consent).scopeQuery("auth", request, "Bearer caller-token");
        order.verify(hpds).query(any(HpdsTarget.class), eq(request));
        order.verify(operationsClient).save(any());
    }

    @Test
    void createNeverPersistsResourceCredentials() throws Exception {
        // SECURITY: QueryRequest no longer models a credential map, and a legacy body that still sends one must not survive into the
        // payload stored by operations-service (it would otherwise sit at rest and echo back through /metadata queryJson).
        UUID picsureId = UUID.randomUUID();
        QueryRequest request =
            new ObjectMapper().readValue("{\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}", QueryRequest.class);
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-1"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        service.query("auth", request);

        verify(operationsClient)
            .save(argThat((SaveQueryRequest r) -> !r.query().contains("resourceCredentials") && !r.query().contains("secret")));
    }

    @Test
    void createEchoesResourceUuidFromRequest() {
        UUID picsureId = UUID.randomUUID();
        QueryRequest request = req();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-1"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatus out = service.query("auth", request);

        assertThat(out.getResourceID()).isEqualTo(request.getResourceUUID());
    }

    // --- sync ---

    @Test
    void syncFallsBackToPicsureIdWhenNoMetadataHeader() {
        UUID picsureId = UUID.randomUUID();
        when(operationsClient.save(any())).thenReturn(picsureId);
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), null));

        var resp = service.querySync("auth", req(), "UI");

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

        service.querySync("auth", req(), "UI");

        verify(operationsClient).update(eq(picsureId), argThat((UpdateQueryRequest u) -> "hpds-meta-id".equals(u.resourceResultId())));
    }

    @Test
    void syncScopesBeforePersistenceOrHpds() {
        ConsentAuthorizationService consent = mock(ConsentAuthorizationService.class);
        QueryService scopedService = new QueryService(operationsClient, hpds, selector, consent);
        QueryRequest request = req();
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), "rr-9"));

        scopedService.querySync("auth", request, "UI", "Bearer caller-token");

        InOrder order = inOrder(consent, operationsClient, hpds);
        order.verify(consent).scopeQuery("auth", request, "Bearer caller-token");
        order.verify(operationsClient).save(any());
        order.verify(hpds).querySync(any(HpdsTarget.class), eq(request), eq("UI"));
    }

    // --- read ops ---

    @Test
    void unknownQueryIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenThrow(
            new edu.harvard.hms.dbmi.avillach.commons.error.PicsureException(
                org.springframework.http.HttpStatus.NOT_FOUND, "not_found", "Query not found: " + id
            )
        );

        org.junit.jupiter.api.Assertions
            .assertThrows(edu.harvard.hms.dbmi.avillach.commons.error.PicsureException.class, () -> service.queryStatus("auth", id, req()));
    }

    @Test
    void signedUrlDispatchesV3WhenStoredVersionIs3() { // THE BUG FIX
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-1", "PENDING", "3", null); // v1-path request, v3-stored query
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-1"), any()))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"url\":\"x\"}"));

        service.queryResultSignedUrl("auth", id, req());

        verify(hpds).queryResultSignedUrl(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), eq("rr-1"), any()); // /v3
                                                                                                                                        // base,
                                                                                                                                        // not
                                                                                                                                        // v1
    }

    @Test
    void resultDispatchesV1WhenStoredVersionIsNull() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-2", "PENDING", null, null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"), any()))
            .thenReturn(org.springframework.http.ResponseEntity.ok(new byte[] {1}));

        service.queryResult("auth", id, req());

        verify(hpds).queryResult(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), eq("rr-2"), any());
    }

    @Test
    void resultVerifiesSavedConsentAfterLoadingAndBeforeHpds() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-2", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"), any()))
            .thenReturn(org.springframework.http.ResponseEntity.ok(new byte[] {1}));

        service.queryResult("auth", id, req(), "Bearer caller-token");

        InOrder order = inOrder(operationsClient, consent, hpds);
        order.verify(operationsClient).get(id);
        order.verify(consent).verifyReadAccess("auth", stored, "Bearer caller-token");
        order.verify(hpds).queryResult(any(HpdsTarget.class), eq("rr-2"), any());
    }

    @Test
    void signedUrlVerifiesSavedConsentAfterLoadingAndBeforeHpds() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-2", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-2"), any()))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{}"));

        service.queryResultSignedUrl("auth", id, req(), "Bearer caller-token");

        InOrder order = inOrder(operationsClient, consent, hpds);
        order.verify(operationsClient).get(id);
        order.verify(consent).verifyReadAccess("auth", stored, "Bearer caller-token");
        order.verify(hpds).queryResultSignedUrl(any(HpdsTarget.class), eq("rr-2"), any());
    }

    @Test
    void statusUsesStoredResourceResultIdAndV1ForNullVersionAndPersistsNewStatus() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{\"resourceUUID\":\"" + UUID.randomUUID() + "\"}", "rr-7", "PENDING", null, null);
        when(operationsClient.get(id)).thenReturn(stored);
        QueryStatus s = hpdsStatus("rr-7");
        s.setStatus(PicSureStatus.AVAILABLE);
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-7"), any())).thenReturn(s);

        QueryStatus out = service.queryStatus("auth", id, req());

        assertThat(out.getPicsureResultId()).isEqualTo(id);
        verify(hpds).queryStatus(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE".equals(t.baseUrl())), eq("rr-7"), any()); // v1 base
        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> "AVAILABLE".equals(u.status())));
    }

    @Test
    void statusEchoesResourceUuidParsedFromStoredQueryJson() {
        UUID id = UUID.randomUUID();
        UUID resourceUuid = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{\"resourceUUID\":\"" + resourceUuid + "\"}", "rr-7", "PENDING", null, null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-7"), any())).thenReturn(hpdsStatus("rr-7"));

        QueryStatus out = service.queryStatus("auth", id, req());

        assertThat(out.getResourceID()).isEqualTo(resourceUuid);
        verify(consent, never()).verifyReadAccess(any(), any(), any());
    }

    // --- metadata ---

    @Test
    void metadataBuildsResultMetadataShapeWithoutCallingHpds() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(
            id, "{\"resourceUUID\":\"" + UUID.randomUUID() + "\",\"query\":\"q\"}", "rr-1", "AVAILABLE", null,
            java.util.Base64.getEncoder().encodeToString("{\"commonAreaUUID\":\"x\"}".getBytes())
        );
        when(operationsClient.get(id)).thenReturn(stored);

        QueryStatus out = service.queryMetadata(id);

        assertThat(out.getResultMetadata()).containsKey("queryJson");
        assertThat(out.getResultMetadata()).containsKey("queryResultMetadata");
        assertThat((String) out.getResultMetadata().get("queryResultMetadata")).contains("commonAreaUUID");
        org.mockito.Mockito.verifyNoInteractions(hpds);
    }

    @Test
    void metadataVerifiesSavedConsentAfterLoading() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-1", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);

        service.queryMetadata("auth", id, "Bearer caller-token");

        InOrder order = inOrder(operationsClient, consent);
        order.verify(operationsClient).get(id);
        order.verify(consent).verifyReadAccess("auth", stored, "Bearer caller-token");
    }

    @Test
    void metadataUnknownIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenThrow(new PicsureException(HttpStatus.NOT_FOUND, "not_found", "nope"));

        assertThatThrownBy(() -> service.queryMetadata(id))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(operationsClient).get(id); // the one lookup queryMetadata is allowed to make
        verifyNoMoreInteractions(operationsClient); // pins that no second (e.g. common-area) lookup is attempted
    }

    /**
     * A row written before the federated removal stores a serialized FederatedQueryRequest. queryMetadata must still read it: it parses the
     * stored blob into a plain {@code Map} via {@code MAPPER.readValue(..., Object.class)}, so {@code "@type"} is just another map key and
     * any value — known, unknown, or garbage — parses fine. That's why this test would still pass if the blob's {@code "@type"} were
     * replaced with a nonsense value: it pins the parse path, not the subtype registry. The subtype-registry fallback (via
     * {@code QueryRequest}'s {@code defaultImpl}) is separately pinned by
     * {@code QueryRequestTest.shouldDeserializeRemovedFederatedTypeAsGeneralQueryRequest} in pic-sure-api-model.
     */
    @Test
    @SuppressWarnings("unchecked")
    void metadataReadsALegacyFederatedStoredRow() {
        UUID id = UUID.randomUUID();
        String legacyBlob =
            "{\"@type\":\"FederatedQueryRequest\"," + "\"query\":{\"expectedResultType\":\"COUNT\"}," + "\"commonAreaUUID\":\""
                + UUID.randomUUID() + "\"," + "\"institutionOfOrigin\":\"BCH\"," + "\"requesterEmail\":\"alice@harvard.edu\"}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, legacyBlob, "rr-legacy", "AVAILABLE", "3", null));

        QueryStatus result = service.queryMetadata(id);

        Map<String, Object> resultMetadata = result.getResultMetadata();
        Map<String, Object> queryJson = (Map<String, Object>) resultMetadata.get("queryJson");
        assertThat(queryJson).isNotNull();
        assertThat(queryJson).containsKey("query");
        assertThat(result.getPicsureResultId()).isEqualTo(id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataTranslatesStoredV1QueryToV3Shape() {
        UUID id = UUID.randomUUID();
        String v1Blob = "{\"resourceUUID\":\"" + UUID.randomUUID() + "\",\"query\":{"
            + "\"expectedResultType\":\"COUNT\",\"categoryFilters\":{\"\\\\sex\\\\\":[\"M\"]}}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v1Blob, "rr-1", "AVAILABLE", null, null));

        QueryStatus out = service.queryMetadata(id);

        Map<String, Object> queryJson = (Map<String, Object>) out.getResultMetadata().get("queryJson");
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

        QueryStatus out = service.queryMetadata(id);

        Map<String, Object> queryJson = (Map<String, Object>) out.getResultMetadata().get("queryJson");
        Map<String, Object> inner = (Map<String, Object>) queryJson.get("query");
        assertThat(inner).containsKey("variantInfoFilters"); // untranslated v1 body preserved
        assertThat(inner).containsKey("categoryFilters"); // raw v1 body returned in full, not partially translated
        assertThat(inner).doesNotContainKey("phenotypicClause"); // no translation output leaked in
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataLeavesV3StoredRowUntranslated() {
        UUID id = UUID.randomUUID();
        String v3Blob = "{\"resourceUUID\":\"" + UUID.randomUUID() + "\",\"query\":{"
            + "\"expectedResultType\":\"COUNT\",\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\","
            + "\"conceptPath\":\"\\\\x\\\\\"},\"genomicFilters\":[]}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v3Blob, "rr-1", "AVAILABLE", "3", null));

        QueryStatus out = service.queryMetadata(id);

        Map<String, Object> queryJson = (Map<String, Object>) out.getResultMetadata().get("queryJson");
        Map<String, Object> inner = (Map<String, Object>) queryJson.get("query");
        // non-null structural assertion: a wrongly-applied v1 translation would produce a different/empty clause,
        // so asserting the exact conceptPath survives discriminates "left alone" from "translated".
        assertThat(inner.get("phenotypicClause")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> clause = (Map<String, Object>) inner.get("phenotypicClause");
        assertThat(clause.get("conceptPath")).isEqualTo("\\x\\");
    }
}
