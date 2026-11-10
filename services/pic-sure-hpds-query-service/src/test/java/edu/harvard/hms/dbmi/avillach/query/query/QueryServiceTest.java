package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentFilterBuilder;
import edu.harvard.hms.dbmi.avillach.query.consent.PsamaConsentClient;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.config.SampleProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * Covers the DB-free query lifecycle. Create and sync operations persist through {@link OperationsClient#save} and update; read operations
 * load through {@link OperationsClient#get}.
 */
class QueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    OperationsClient operationsClient = mock(OperationsClient.class);
    ResourceWebClient hpds = mock(ResourceWebClient.class);
    HpdsProperties props = props();
    HpdsBackendSelector selector = new HpdsBackendSelector(props);
    ConsentAuthorizationService consent = mock(ConsentAuthorizationService.class);
    SampleProperties sampleProperties = new SampleProperties();
    SampleRules sampleRules = new SampleRules(sampleProperties);
    QueryService service = new QueryService(operationsClient, hpds, selector, consent, sampleRules);

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
    void createPersistsVersion3ViaOperationsClientAndCallsTheV3Base() {
        UUID picsureId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-1"));
        when(operationsClient.save(any())).thenReturn(picsureId);

        QueryStatus out = service.query("auth", req());

        assertThat(out.getPicsureResultId()).isEqualTo(picsureId);
        assertThat(out.getResourceResultId()).isEqualTo("rr-1");
        verify(operationsClient).save(argThat((SaveQueryRequest r) -> "rr-1".equals(r.resourceResultId()) && "3".equals(r.version())));
        verify(hpds).query(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), any());
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

    private static QueryRequest sampleRequest(ResultType type) {
        return new GeneralQueryRequest().setQuery(new Query(List.of(), List.of(), null, List.of(), type, null, null, true));
    }

    private void assertRefusedBeforeAnyCall(Runnable call, String errorType) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(PicsureException.class, refused -> {
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getErrorType()).isEqualTo(errorType);
        });
        verifyNoInteractions(consent, hpds, operationsClient);
    }

    @Test
    void createRefusesSampleBeforeConsentScopingWhenSamplingIsOff() {
        assertRefusedBeforeAnyCall(
            () -> service.query("auth", sampleRequest(ResultType.DATAFRAME), "Bearer caller-token"), "sample_disabled"
        );
    }

    @Test
    void createRefusesSampleOnTheOpenBackend() {
        sampleProperties.setEnabled(true);

        assertRefusedBeforeAnyCall(() -> service.query("open", sampleRequest(ResultType.DATAFRAME)), "bad_request");
    }

    @Test
    void createSubmitsADataframeSampleWhenSamplingIsOn() {
        sampleProperties.setEnabled(true);
        QueryRequest request = sampleRequest(ResultType.DATAFRAME_PFB);
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-sample"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        service.query("auth", request, "Bearer caller-token");

        verify(consent).scopeQuery("auth", request, "Bearer caller-token");
        verify(operationsClient).save(argThat((SaveQueryRequest saved) -> saved.query().contains("\"sample\":true")));
    }

    @Test
    void syncRefusesSampleBeforeConsentScopingWhenSamplingIsOff() {
        assertRefusedBeforeAnyCall(
            () -> service.querySync("auth", sampleRequest(ResultType.COUNT), null, "Bearer caller-token"), "sample_disabled"
        );
    }

    @Test
    void syncRefusesSampleOnAResultTypeItServes() {
        sampleProperties.setEnabled(true);

        assertRefusedBeforeAnyCall(
            () -> service.querySync("auth", sampleRequest(ResultType.COUNT), null, "Bearer caller-token"), "bad_request"
        );
    }

    @Test
    void createScopesBeforeCallingHpdsOrPersistence() {
        ConsentAuthorizationService consent = mock(ConsentAuthorizationService.class);
        QueryService scopedService = new QueryService(operationsClient, hpds, selector, consent, sampleRules);
        QueryRequest request = req();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-3"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        scopedService.query("auth", request, "Bearer caller-token");

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

    // --- create for a caller the gateway identified by UUID on the auth backend ---

    private static final String TOKEN = "Bearer caller-token";
    private static final String CONSENT_PATH = "\\_consents\\";
    private static final UUID ALICE = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID BOB = UUID.fromString("0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77");

    PsamaConsentClient psama = mock(PsamaConsentClient.class);
    QueryService ownedService = new QueryService(
        operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), true), sampleRules
    );
    List<Object> queriesSentToHpds = new ArrayList<>();

    private static GatewayUser user(UUID uuid) {
        return new GatewayUser(uuid.toString(), null, null, null, Set.of());
    }

    private static Query cohort() {
        return new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, null, null);
    }

    private static QueryRequest envelope(Query query) {
        return new GeneralQueryRequest().setQuery(query);
    }

    private static StoredQuery ownedRow(UUID id, UUID owner, String status, Map<String, List<String>> consents) {
        return new StoredQuery(id, "{}", id.toString(), status, "3", null, 1L, null, owner, consents, UUID.randomUUID());
    }

    private void callerHoldsConsents(String... values) {
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, Set.of(values)));
    }

    private void hpdsAcceptsOwnedSubmits() {
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenAnswer(submit -> {
            queriesSentToHpds.add(((QueryRequest) submit.getArgument(1)).getQuery());
            return hpdsStatus(submit.getArgument(2).toString());
        });
    }

    private SaveQueryRequest onlySave() {
        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void createSendsTheDerivedIdAsPicsureIdAndSavesItWithOwnerAndConsents() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c2", "phs001.c1");
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        verify(hpds, never()).query(any(HpdsTarget.class), any());
        assertThat(((Query) queriesSentToHpds.get(0)).authorizationFilters())
            .containsExactly(new AuthorizationFilter(CONSENT_PATH, Set.of("phs001.c1", "phs001.c2")));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(saved.userUuid()).isEqualTo(ALICE);
        assertThat(saved.consents()).isEqualTo(Map.of(CONSENT_PATH, List.of("phs001.c1", "phs001.c2")));
        assertThat(saved.trigger()).isEqualTo("FIRST_SUBMIT");
        assertThat(saved.version()).isEqualTo("3");
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        verify(psama, times(1)).fetch(TOKEN);
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void createUsesTheDerivedIdAsResourceResultId() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenReturn(hpdsStatus(null));

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        assertThat(onlySave().resourceResultId()).isEqualTo(id.toString());
        assertThat(out.getResourceResultId()).isEqualTo(id.toString());
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void repeatedSubmitReturnsTheSameIdWithoutCallingHpds() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1", "phs001.c2");
        when(operationsClient.find(id))
            .thenReturn(Optional.of(ownedRow(id, ALICE, "AVAILABLE", Map.of(CONSENT_PATH, List.of("phs001.c2", "phs001.c1")))));
        QueryRequest request = envelope(cohort());
        request.setResourceUUID(UUID.randomUUID());

        QueryStatus out = ownedService.query("auth", request, TOKEN, user(ALICE));

        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertThat(out.getResourceResultId()).isEqualTo(id.toString());
        assertThat(out.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(out.getResourceID()).isEqualTo(request.getResourceUUID());
        verifyNoInteractions(hpds);
        verify(operationsClient).find(id);
        verifyNoMoreInteractions(operationsClient);
    }

    @Test
    void submitWithChangedConsentsResubmitsAndRecordsConsentChange() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id))
            .thenReturn(Optional.of(ownedRow(id, ALICE, "AVAILABLE", Map.of(CONSENT_PATH, List.of("phs001.c1", "phs001.c2")))));
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        assertThat(((Query) queriesSentToHpds.get(0)).authorizationFilters())
            .containsExactly(new AuthorizationFilter(CONSENT_PATH, Set.of("phs001.c1")));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(saved.userUuid()).isEqualTo(ALICE);
        assertThat(saved.consents()).isEqualTo(Map.of(CONSENT_PATH, List.of("phs001.c1")));
        assertThat(saved.trigger()).isEqualTo("CONSENT_CHANGE");
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertThat(out.getStatus()).isEqualTo(PicSureStatus.PENDING);
    }

    @Test
    void submitUnderAGrownConsentSetAlsoRecordsConsentChange() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1", "phs001.c2");
        when(operationsClient.find(id))
            .thenReturn(Optional.of(ownedRow(id, ALICE, "AVAILABLE", Map.of(CONSENT_PATH, List.of("phs001.c1")))));
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        assertThat(onlySave().trigger()).isEqualTo("CONSENT_CHANGE");
    }

    @Test
    void submitOfAnotherUsersQueryCreatesTheCallersOwnRow() {
        UUID alicesId = QueryIdentity.idFor(ALICE, cohort());
        UUID bobsId = QueryIdentity.idFor(BOB, cohort());
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        QueryStatus alices = ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));
        QueryStatus bobs = ownedService.query("auth", envelope(cohort().setPicsureId(alicesId)), TOKEN, user(BOB));

        assertThat(alicesId).isNotEqualTo(bobsId);
        assertThat(alices.getPicsureResultId()).isEqualTo(alicesId);
        assertThat(bobs.getPicsureResultId()).isEqualTo(bobsId);
        assertThat(bobs.getResourceResultId()).isEqualTo(bobsId.toString());
        verify(operationsClient, times(1)).find(alicesId);
        verify(operationsClient, times(1)).find(bobsId);
        verify(operationsClient, never()).get(any());
        verify(hpds).query(any(HpdsTarget.class), any(), eq(alicesId));
        verify(hpds).query(any(HpdsTarget.class), any(), eq(bobsId));
        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(SaveQueryRequest::picsureId).containsExactly(alicesId, bobsId);
        assertThat(saved.getAllValues()).extracting(SaveQueryRequest::userUuid).containsExactly(ALICE, BOB);
        assertThat(saved.getAllValues()).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT", "FIRST_SUBMIT");
        assertThat(saved.getAllValues().get(1).query()).doesNotContain(alicesId.toString());
    }

    @Test
    void aClientSuppliedPicsureIdNeverBecomesTheRowIdTheHpdsKeyOrAStoredResultId() {
        UUID fromTheClient = UUID.fromString("11111111-2222-4333-8444-555555555555");
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.query("auth", envelope(cohort().setPicsureId(fromTheClient)), TOKEN, user(ALICE));

        verify(operationsClient).find(id);
        verify(operationsClient, never()).find(fromTheClient);
        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        assertThat(((Query) queriesSentToHpds.get(0)).picsureId()).isEqualTo(id);
        SaveQueryRequest saved = onlySave();
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(saved.resourceResultId()).isEqualTo(id.toString());
        assertThat(saved.query()).doesNotContain(fromTheClient.toString()).contains("\"picsureId\":\"" + id + "\"");
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertThat(out.getResourceResultId()).isEqualTo(id.toString());
    }

    @Test
    void storedBodyCarriesNoAuthorizationFilters() throws Exception {
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsAcceptsOwnedSubmits();
        Query withClientFilters = new Query(
            List.of("\\demographics\\AGE\\"), List.of(new AuthorizationFilter("\\attacker\\", Set.of("grant"))), null, List.of(),
            ResultType.DATAFRAME, null, null
        );

        ownedService.query("auth", envelope(withClientFilters), TOKEN, user(ALICE));

        SaveQueryRequest saved = onlySave();
        JsonNode storedQuery = MAPPER.readTree(saved.query()).get("query");
        assertThat(storedQuery.get("authorizationFilters")).isEmpty();
        assertThat(saved.query()).doesNotContain("phs001").doesNotContain("attacker").doesNotContain(ALICE.toString());
        assertThat(storedQuery.get("select").get(0).asText()).isEqualTo("\\demographics\\AGE\\");
        assertThat(((Query) queriesSentToHpds.get(0)).authorizationFilters()).hasSize(1);
        assertThat(saved.picsureId()).isEqualTo(QueryIdentity.idFor(ALICE, cohort()));
    }

    @Test
    void ownedSubmitKeepsSampleInTheIdTheHpdsQueryAndTheStoredBody() {
        sampleProperties.setEnabled(true);
        Query sample = new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME, null, null, true);
        Query full = new Query(List.of(), List.of(), null, List.of(), ResultType.DATAFRAME, null, null, false);
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.query("auth", envelope(sample), TOKEN, user(ALICE));

        assertThat(out.getPicsureResultId()).isEqualTo(QueryIdentity.idFor(ALICE, sample)).isNotEqualTo(QueryIdentity.idFor(ALICE, full));
        assertThat(((Query) queriesSentToHpds.get(0)).sample()).isTrue();
        assertThat(onlySave().query()).contains("\"sample\":true");
    }

    @Test
    void ownedSubmitRunsTheSampleRulesBeforeAnythingIsLookedUpSentOrSaved() {
        assertThatThrownBy(() -> ownedService.query("auth", sampleRequest(ResultType.DATAFRAME), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(refused.getErrorType()).isEqualTo("sample_disabled");
            });

        verifyNoInteractions(psama, hpds, operationsClient);
    }

    @Test
    void ownedSubmitWithAWrongSampleResultTypeIsRefusedBeforeAnythingIsLookedUpSentOrSaved() {
        sampleProperties.setEnabled(true);

        assertThatThrownBy(() -> ownedService.query("auth", sampleRequest(ResultType.COUNT), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> assertThat(refused.getErrorType()).isEqualTo("bad_request"));

        verifyNoInteractions(psama, hpds, operationsClient);
    }

    @Test
    void ownedSubmitThatHpdsRejectsSavesNothing() {
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class)))
            .thenThrow(new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "HPDS refused the query"));

        assertThatThrownBy(() -> ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE))).isInstanceOf(PicsureException.class)
            .hasMessageContaining("HPDS refused the query");

        verify(operationsClient, never()).save(any());
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void ownedSubmitByACallerWithNoConsentsIs403BeforeAnythingIsLookedUpSentOrSaved() {
        when(psama.fetch(TOKEN)).thenReturn(Map.of());

        assertThatThrownBy(() -> ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(refused.getErrorType()).isEqualTo("consent_denied");
            });

        verifyNoInteractions(hpds, operationsClient);
    }

    @Test
    void ownedSubmitWithConsentAuthorizationOffSavesAnEmptyConsentMapAndReusesTheRow() {
        QueryService consentOff = new QueryService(
            operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), false), sampleRules
        );
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        hpdsAcceptsOwnedSubmits();

        consentOff.query("auth", envelope(cohort()), null, user(ALICE));

        SaveQueryRequest saved = onlySave();
        assertThat(saved.consents()).isNotNull().isEmpty();
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(saved.trigger()).isEqualTo("FIRST_SUBMIT");

        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, ALICE, "PENDING", Map.of())));
        consentOff.query("auth", envelope(cohort()), null, user(ALICE));

        verify(operationsClient, times(1)).save(any());
        verify(hpds, times(1)).query(any(HpdsTarget.class), any(), any(UUID.class));
        verifyNoInteractions(psama);
    }

    @Test
    void aRowUnderTheDerivedIdThatBelongsToSomeoneElseIsAServerFaultThatNamesNoOwner() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, BOB, "AVAILABLE", Map.of(CONSENT_PATH, List.of("phs001.c1")))));

        assertThatThrownBy(() -> ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, fault -> {
                assertThat(fault.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(fault.getMessage()).doesNotContain(BOB.toString()).doesNotContain(ALICE.toString()).doesNotContain("owner")
                    .doesNotContain("phs001");
            });

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).save(any());
    }

    @Test
    void aRowUnderTheDerivedIdWithNoOwnerIsAServerFaultToo() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id)).thenReturn(Optional.of(new StoredQuery(id, "{}", "rr-old", "AVAILABLE", "3", null)));

        assertThatThrownBy(() -> ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE))).isInstanceOfSatisfying(
            PicsureException.class, fault -> assertThat(fault.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        );

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).save(any());
    }

    private void assertSavedWithoutAnOwner(String resourceResultId) {
        SaveQueryRequest saved = onlySave();
        assertThat(saved.picsureId()).isNull();
        assertThat(saved.userUuid()).isNull();
        assertThat(saved.consents()).isNull();
        assertThat(saved.trigger()).isNull();
        assertThat(saved.resourceResultId()).isEqualTo(resourceResultId);
        verify(operationsClient, never()).find(any());
        verify(hpds, never()).query(any(HpdsTarget.class), any(), any(UUID.class));
    }

    @Test
    void openBackendCreateKeepsTheLegacyPath() {
        UUID rowId = UUID.randomUUID();
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-open"));
        when(operationsClient.save(any())).thenReturn(rowId);

        QueryStatus out = ownedService.query("open", envelope(cohort()), TOKEN, user(ALICE));

        assertThat(out.getPicsureResultId()).isEqualTo(rowId);
        assertSavedWithoutAnOwner("rr-open");
        verifyNoInteractions(psama);
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth0|alice", "OPEN_ACCESS:picsure.example.org", "5f0c1f0e6f0a4a579d6e0d1c6a3b9f11", " "})
    void createByACallerWhoseIdIsNotAUuidKeepsTheLegacyPath(String userId) {
        UUID rowId = UUID.randomUUID();
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-legacy"));
        when(operationsClient.save(any())).thenReturn(rowId);

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, new GatewayUser(userId, null, null, null, Set.of()));

        assertThat(out.getPicsureResultId()).isEqualTo(rowId);
        assertSavedWithoutAnOwner("rr-legacy");
    }

    @Test
    void createWithoutAGatewayUserKeepsTheLegacyPath() {
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-legacy"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        ownedService.query("auth", envelope(cohort()), TOKEN, null);
        assertSavedWithoutAnOwner("rr-legacy");
    }

    @Test
    void createWithoutAQueryKeepsTheLegacyPath() {
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-legacy"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        QueryService consentOff = new QueryService(
            operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), false), sampleRules
        );

        consentOff.query("auth", envelope(null), TOKEN, user(ALICE));

        assertSavedWithoutAnOwner("rr-legacy");
    }

    @Test
    void syncByACallerIdentifiedByUuidKeepsTheLegacyPath() {
        callerHoldsConsents("phs001.c1");
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("7".getBytes(), "hpds-sync-id"));
        Query count = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null);

        ownedService.querySync("auth", envelope(count), "UI", TOKEN, user(ALICE));

        assertSavedWithoutAnOwner("hpds-sync-id");
    }

    // --- sync ---

    /** HPDS omits the header only for INFO_COLUMN_LISTING, which is a listing rather than a query, so there is nothing to record. */
    @Test
    void syncPersistsNothingWhenHpdsReturnsNoQueryId() {
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), null));

        var resp = service.querySync("auth", req(), "UI");

        assertThat(new String(resp.body())).isEqualTo("body");
        verifyNoInteractions(operationsClient);
    }

    @Test
    void syncSavesMetadataHeaderAsResourceResultIdInOneWrite() {
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), "hpds-meta-id"));

        service.querySync("auth", req(), "UI");

        verify(operationsClient).save(argThat((SaveQueryRequest s) -> "hpds-meta-id".equals(s.resourceResultId())));
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void syncScopesThenCallsHpdsBeforePersisting() {
        ConsentAuthorizationService consent = mock(ConsentAuthorizationService.class);
        QueryService scopedService = new QueryService(operationsClient, hpds, selector, consent, sampleRules);
        QueryRequest request = req();
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("body".getBytes(), "rr-9"));

        scopedService.querySync("auth", request, "UI", "Bearer caller-token");

        InOrder order = inOrder(consent, operationsClient, hpds);
        order.verify(consent).scopeQuery("auth", request, "Bearer caller-token");
        order.verify(hpds).querySync(any(HpdsTarget.class), eq(request), eq("UI"));
        order.verify(operationsClient).save(any());
    }

    @Test
    void syncDoesNotPersistAQueryHpdsRejected() {
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenThrow(new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Result type DATAFRAME is served asynchronously."));

        assertThatThrownBy(() -> service.querySync("auth", req(), "UI")).isInstanceOf(PicsureException.class)
            .hasMessageContaining("served asynchronously");

        verify(operationsClient, never()).save(any());
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
            .assertThrows(edu.harvard.hms.dbmi.avillach.commons.error.PicsureException.class, () -> service.queryStatus("auth", id));
    }

    @Test
    void signedUrlOfAV3RowDispatchesToTheV3BaseWithoutUpgrading() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-1", "PENDING", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-1"))).thenReturn(new SignedUrlResponse("https://s3/x"));

        service.queryResultSignedUrl("auth", id);

        verify(hpds).queryResultSignedUrl(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), eq("rr-1"));
        verify(hpds, never()).query(any(), any());
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void resultVerifiesSavedConsentAfterLoadingAndBeforeHpds() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-2", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"))).thenReturn(org.springframework.http.ResponseEntity.ok(new byte[] {1}));

        service.queryResult("auth", id, "Bearer caller-token");

        InOrder order = inOrder(operationsClient, consent, hpds);
        order.verify(operationsClient).get(id);
        order.verify(consent).verifyReadAccess("auth", stored, "Bearer caller-token");
        order.verify(hpds).queryResult(any(HpdsTarget.class), eq("rr-2"));
    }

    @Test
    void signedUrlVerifiesSavedConsentAfterLoadingAndBeforeHpds() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-2", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-2"))).thenReturn(new SignedUrlResponse("https://s3/x"));

        service.queryResultSignedUrl("auth", id, "Bearer caller-token");

        InOrder order = inOrder(operationsClient, consent, hpds);
        order.verify(operationsClient).get(id);
        order.verify(consent).verifyReadAccess("auth", stored, "Bearer caller-token");
        order.verify(hpds).queryResultSignedUrl(any(HpdsTarget.class), eq("rr-2"));
    }

    @Test
    void statusOfAV3RowUsesStoredResourceResultIdAndPersistsNewStatus() {
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{\"resourceUUID\":\"" + UUID.randomUUID() + "\"}", "rr-7", "PENDING", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        QueryStatus s = hpdsStatus("rr-7");
        s.setStatus(PicSureStatus.AVAILABLE);
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-7"))).thenReturn(s);

        QueryStatus out = service.queryStatus("auth", id);

        assertThat(out.getPicsureResultId()).isEqualTo(id);
        verify(hpds).queryStatus(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), eq("rr-7"));
        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> "AVAILABLE".equals(u.status()) && u.version() == null));
        verify(hpds, never()).query(any(), any());
    }

    @Test
    void statusEchoesResourceUuidParsedFromStoredQueryJson() {
        UUID id = UUID.randomUUID();
        UUID resourceUuid = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{\"resourceUUID\":\"" + resourceUuid + "\"}", "rr-7", "PENDING", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-7"))).thenReturn(hpdsStatus("rr-7"));

        QueryStatus out = service.queryStatus("auth", id);

        assertThat(out.getResourceID()).isEqualTo(resourceUuid);
        verify(consent, never()).verifyReadAccess(any(), any(), any());
    }

    private static final String V1_BODY_TEMPLATE =
        "{\"resourceUUID\":\"%s\",\"query\":{\"expectedResultType\":\"COUNT\",\"categoryFilters\":{\"\\\\sex\\\\\":[\"M\"]}}}";

    private static final String UNTRANSLATABLE_V1_BODY = "{\"query\":{\"expectedResultType\":\"COUNT\",\"variantInfoFilters\":["
        + "{\"categoryVariantInfoFilters\":{\"Gene_with_variant\":[\"A\"]},\"numericVariantInfoFilters\":{}},"
        + "{\"categoryVariantInfoFilters\":{\"Gene_with_variant\":[\"B\"]},\"numericVariantInfoFilters\":{}}]}}";

    private StoredQuery legacyRow(UUID id, UUID resourceUuid, String version) {
        return new StoredQuery(id, String.format(V1_BODY_TEMPLATE, resourceUuid), "rr-old", "AVAILABLE", version, null);
    }

    private static boolean isUpgradePatch(UpdateQueryRequest u, String resourceResultId) {
        return "3".equals(u.version()) && resourceResultId.equals(u.resourceResultId()) && "PENDING".equals(u.status()) && u.query() != null
            && u.query().contains("phenotypicClause") && !u.query().contains("categoryFilters");
    }

    private static boolean isV3Target(HpdsTarget t) {
        return "http://hpds/PIC-SURE/v3".equals(t.baseUrl());
    }

    @Test
    void statusOfANullVersionRowTranslatesScopesRerunsAndPatchesBeforePolling() {
        UUID id = UUID.randomUUID();
        UUID resourceUuid = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(legacyRow(id, resourceUuid, null));
        QueryStatus submitted = hpdsStatus("rr-new");
        submitted.setResultMetadata(Map.of("queryResultMetadata", "m"));
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(submitted);
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-new"))).thenReturn(hpdsStatus("rr-new"));

        QueryStatus out = service.queryStatus("auth", id, "Bearer caller-token");

        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertThat(out.getResourceID()).isEqualTo(resourceUuid);
        InOrder order = inOrder(consent, hpds, operationsClient);
        order.verify(consent)
            .scopeQuery(eq("auth"), argThat((QueryRequest r) -> resourceUuid.equals(r.getResourceUUID())), eq("Bearer caller-token"));
        order.verify(hpds).query(argThat(QueryServiceTest::isV3Target), any());
        order.verify(operationsClient)
            .update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, "rr-new") && u.metadata() != null));
        order.verify(hpds).queryStatus(argThat(QueryServiceTest::isV3Target), eq("rr-new"));
        verify(hpds, never()).queryStatus(any(), eq("rr-old"));
    }

    @Test
    void resultOfAVersion2RowIsUpgradedBeforeReadAccessIsVerified() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(legacyRow(id, UUID.randomUUID(), "2"));
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-new"));
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-new"))).thenReturn(org.springframework.http.ResponseEntity.ok(new byte[] {1}));

        service.queryResult("auth", id, "Bearer caller-token");

        InOrder order = inOrder(consent, hpds, operationsClient);
        order.verify(consent).scopeQuery(eq("auth"), any(), eq("Bearer caller-token"));
        order.verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, "rr-new")));
        order.verify(consent).verifyReadAccess(
            eq("auth"),
            argThat(
                (StoredQuery row) -> "3".equals(row.version()) && "rr-new".equals(row.resourceResultId())
                    && row.query().contains("phenotypicClause")
            ), eq("Bearer caller-token")
        );
        order.verify(hpds).queryResult(argThat(QueryServiceTest::isV3Target), eq("rr-new"));
    }

    @Test
    void signedUrlOfANullVersionRowIsUpgradedBeforeReadAccessIsVerified() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(legacyRow(id, UUID.randomUUID(), null));
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-new"));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-new"))).thenReturn(new SignedUrlResponse("https://s3/x"));

        service.queryResultSignedUrl("auth", id, "Bearer caller-token");

        InOrder order = inOrder(consent, hpds, operationsClient);
        order.verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, "rr-new")));
        order.verify(consent)
            .verifyReadAccess(eq("auth"), argThat((StoredQuery row) -> "3".equals(row.version())), eq("Bearer caller-token"));
        order.verify(hpds).queryResultSignedUrl(argThat(QueryServiceTest::isV3Target), eq("rr-new"));
    }

    @Test
    void upgradeFallsBackToThePicsureIdWhenHpdsOmitsTheResultId() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(legacyRow(id, UUID.randomUUID(), null));
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus(null));
        when(hpds.queryStatus(any(HpdsTarget.class), eq(id.toString()))).thenReturn(hpdsStatus(id.toString()));

        service.queryStatus("auth", id, "Bearer caller-token");

        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, id.toString())));
    }

    @Test
    void untranslatableLegacyRowIs422AndIsLeftUntouched() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, UNTRANSLATABLE_V1_BODY, "rr-old", "AVAILABLE", null, null));

        assertThatThrownBy(() -> service.queryResult("auth", id, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getErrorType()).isEqualTo("untranslatable_query");
            });

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).update(any(), any());
        verify(consent, never()).verifyReadAccess(any(), any(), any());
    }

    @Test
    void legacyRowWithoutAQueryObjectIs422() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{\"query\":\"q\"}", "rr-old", "AVAILABLE", null, null));

        assertThatThrownBy(() -> service.queryStatus("auth", id, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verifyNoInteractions(hpds);
    }

    @Test
    void v3ShapedLegacyRowIs422WithoutRerunningOrPatching() {
        UUID id = UUID.randomUUID();
        String v3Body = "{\"query\":{\"expectedResultType\":\"COUNT\",\"select\":[],\"phenotypicClause\":null}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v3Body, "rr-old", "AVAILABLE", null, null));

        assertThatThrownBy(() -> service.queryStatus("auth", id, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getErrorType()).isEqualTo("untranslatable_query");
            });

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).update(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void legacyRowCarryingASampleKeyIs422WithoutRerunningOrPatching(String sampleValue) {
        UUID id = UUID.randomUUID();
        String body = "{\"query\":{\"expectedResultType\":\"DATAFRAME\",\"categoryFilters\":{\"\\\\sex\\\\\":[\"M\"]},\"sample\":"
            + sampleValue + "}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, body, "rr-old", "AVAILABLE", null, null));
        sampleProperties.setEnabled(true);

        assertThatThrownBy(() -> service.queryStatus("auth", id, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getErrorType()).isEqualTo("untranslatable_query");
            });

        verifyNoInteractions(hpds, consent);
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void legacyRowCarryingTheV3PicsureIdIs422WithoutRerunningOrPatching() {
        UUID id = UUID.randomUUID();
        String v3Body = "{\"query\":{\"expectedResultType\":\"COUNT\",\"picsureId\":\"" + UUID.randomUUID() + "\"}}";
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, v3Body, "rr-old", "AVAILABLE", null, null));

        assertThatThrownBy(() -> service.queryStatus("auth", id, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getErrorType()).isEqualTo("untranslatable_query");
            });

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void legacyRowWithAnUnrecognizableQueryObjectIs422() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{\"query\":{\"foo\":1}}", "rr-old", "AVAILABLE", null, null));

        assertThatThrownBy(() -> service.queryStatus("auth", id, "Bearer caller-token"))
            .isInstanceOfSatisfying(PicsureException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getErrorType()).isEqualTo("untranslatable_query");
            });

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void minimalV1RowWithOnlyAResultTypeIsUpgraded() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id))
            .thenReturn(new StoredQuery(id, "{\"query\":{\"expectedResultType\":\"COUNT\"}}", "rr-old", "AVAILABLE", null, null));
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-new"));
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-new"))).thenReturn(hpdsStatus("rr-new"));

        service.queryStatus("auth", id, "Bearer caller-token");

        verify(hpds).query(argThat(QueryServiceTest::isV3Target), any());
        verify(operationsClient).update(eq(id), argThat((UpdateQueryRequest u) -> isUpgradePatch(u, "rr-new")));
    }

    @Test
    void upgradeStopsBeforeHpdsAndPatchWhenConsentScopingRejectsTheCaller() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(legacyRow(id, UUID.randomUUID(), null));
        PicsureException forbidden = new PicsureException(HttpStatus.FORBIDDEN, "forbidden", "No consents");
        doThrow(forbidden).when(consent).scopeQuery(any(), any(), any());

        assertThatThrownBy(() -> service.queryStatus("auth", id, "Bearer caller-token")).isSameAs(forbidden);

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void upgradePatchCarriesTheAuthorizationFiltersConsentScopingAdded() throws Exception {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(legacyRow(id, UUID.randomUUID(), null));
        doAnswer(invocation -> {
            QueryRequest scoped = invocation.getArgument(1);
            Query v3 = MAPPER.convertValue(scoped.getQuery(), Query.class);
            scoped.setQuery(v3.setAuthorizationFilters(List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c1")))));
            return null;
        }).when(consent).scopeQuery(any(), any(), any());
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-new"));
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-new"))).thenReturn(hpdsStatus("rr-new"));

        service.queryStatus("auth", id, "Bearer caller-token");

        ArgumentCaptor<UpdateQueryRequest> patch = ArgumentCaptor.forClass(UpdateQueryRequest.class);
        verify(operationsClient, times(2)).update(eq(id), patch.capture());
        UpdateQueryRequest upgrade = patch.getAllValues().get(0);
        assertThat(upgrade.version()).isEqualTo("3");
        JsonNode filters = MAPPER.readTree(upgrade.query()).path("query").path("authorizationFilters");
        assertThat(filters.isArray()).isTrue();
        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).path("conceptPath").asText()).isEqualTo("\\_consents\\");
        assertThat(filters.get(0).path("values").get(0).asText()).isEqualTo("phs000001.c1");
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
     * any value parses fine, whether known, unknown, or garbage. That's why this test would still pass if the blob's {@code "@type"} were
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
