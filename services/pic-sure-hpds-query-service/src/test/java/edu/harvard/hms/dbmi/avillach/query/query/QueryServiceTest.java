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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
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
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.QueryIdentity;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentFilterBuilder;
import edu.harvard.hms.dbmi.avillach.query.consent.PsamaConsentClient;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.config.QueryRowLockProperties;
import edu.harvard.hms.dbmi.avillach.query.config.SampleProperties;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
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
    QueryRowLocks rowLocks = new QueryRowLocks(new QueryRowLockProperties());
    UnsettledQueryRows unsettledRows = new UnsettledQueryRows();
    QueryService service = new QueryService(operationsClient, hpds, selector, consent, sampleRules, rowLocks, unsettledRows);

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
        QueryService scopedService = new QueryService(operationsClient, hpds, selector, consent, sampleRules, rowLocks, unsettledRows);
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

    private static final String TOKEN = "Bearer caller-token";
    private static final String CONSENT_PATH = "\\_consents\\";
    private static final UUID ALICE = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID BOB = UUID.fromString("0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77");

    PsamaConsentClient psama = mock(PsamaConsentClient.class);
    QueryService ownedService = new QueryService(
        operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), true), sampleRules, rowLocks,
        unsettledRows
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

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PENDING", "AVAILABLE"})
    void repeatedSubmitOfAQueuedRunningOrAvailableRowReturnsItsStatusWithoutCallingHpds(String status) {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, ALICE, status, Map.of(CONSENT_PATH, List.of("phs001.c1")))));

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        assertThat(out.getStatus()).isEqualTo(PicSureStatus.valueOf(status));
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        verifyNoInteractions(hpds);
        verify(operationsClient).find(id);
        verifyNoMoreInteractions(operationsClient);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "ERROR")
    void resubmitOfARowThatFailedOrNeverRanRunsAgainAndRecordsResubmit(String status) {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        Map<String, List<String>> consents = Map.of(CONSENT_PATH, List.of("phs001.c1"));
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, ALICE, status, consents)));
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.trigger()).isEqualTo("RESUBMIT");
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(saved.userUuid()).isEqualTo(ALICE);
        assertThat(saved.consents()).isEqualTo(consents);
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertThat(out.getStatus()).isEqualTo(PicSureStatus.PENDING);
    }

    @Test
    void resubmitOfAFailedRowUnderChangedConsentsRecordsConsentChange() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id))
            .thenReturn(Optional.of(ownedRow(id, ALICE, "ERROR", Map.of(CONSENT_PATH, List.of("phs001.c1", "phs001.c2")))));
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        assertThat(onlySave().trigger()).isEqualTo("CONSENT_CHANGE");
    }

    @Test
    void repeatedSubmitReturnsTheStoredResultMetadata() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        Map<String, List<String>> consents = Map.of(CONSENT_PATH, List.of("phs001.c1"));
        String metadata = Base64.getEncoder().encodeToString("{\"picsureQueryId\":\"stored-query\"}".getBytes(StandardCharsets.UTF_8));
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id)).thenReturn(
            Optional.of(new StoredQuery(id, "{}", id.toString(), "AVAILABLE", "3", metadata, 1L, 2L, ALICE, consents, UUID.randomUUID()))
        );

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        assertThat(out.getResultMetadata()).containsExactly(Map.entry("picsureQueryId", "stored-query"));
        verifyNoInteractions(hpds);
    }

    @Test
    void consentAuthorizationSwitchedOnAfterARowWasSavedWithNoConsentsRunsAgainAsAConsentChange() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, ALICE, "AVAILABLE", Map.of())));
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.trigger()).isEqualTo("CONSENT_CHANGE");
        assertThat(saved.consents()).isEqualTo(Map.of(CONSENT_PATH, List.of("phs001.c1")));
    }

    @Test
    void consentAuthorizationSwitchedOffAfterARowWasSavedWithConsentsRunsAgainAsAConsentChange() {
        QueryService consentOff = new QueryService(
            operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), false), sampleRules,
            rowLocks, unsettledRows
        );
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        when(operationsClient.find(id))
            .thenReturn(Optional.of(ownedRow(id, ALICE, "AVAILABLE", Map.of(CONSENT_PATH, List.of("phs001.c1")))));
        hpdsAcceptsOwnedSubmits();

        consentOff.query("auth", envelope(cohort()), null, user(ALICE));

        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.trigger()).isEqualTo("CONSENT_CHANGE");
        assertThat(saved.consents()).isNotNull().isEmpty();
        verifyNoInteractions(psama);
    }

    @Test
    void anUntypedBodyThatConvertsToAV3QueryIsStoredUnderItsCaller() {
        Map<String, Object> untyped = Map.of("select", List.of("\\demographics\\AGE\\"), "expectedResultType", "DATAFRAME");
        UUID id = QueryIdentity.idFor(ALICE, MAPPER.convertValue(untyped, Query.class));
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.query("auth", new GeneralQueryRequest().setQuery(untyped), TOKEN, user(ALICE));

        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(saved.userUuid()).isEqualTo(ALICE);
        assertThat(saved.trigger()).isEqualTo("FIRST_SUBMIT");
        assertThat(out.getPicsureResultId()).isEqualTo(id);
    }

    private static QueryStatus hpdsAnswer(PicSureStatus status, String resourceResultId, String resourceStatus) {
        QueryStatus answer = new QueryStatus();
        answer.setStatus(status);
        answer.setResourceResultId(resourceResultId);
        answer.setResourceStatus(resourceStatus);
        return answer;
    }

    private void assertNotAcceptedByHpds(QueryStatus answer) {
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenReturn(answer);

        assertThatThrownBy(() -> ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(HpdsCommunicationException.class, fault -> {
                assertThat(fault.getMessage()).isEqualTo("HPDS did not accept the query");
                assertThat(fault.getCause()).isNull();
            });

        verify(operationsClient, never()).save(any());
        verify(operationsClient, never()).update(any(), any());
    }

    @Test
    void ownedSubmitThatHpdsAnswersAsLockedIs502AndSavesNothing() {
        assertNotAcceptedByHpds(hpdsAnswer(null, null, "Resource is locked."));
    }

    @Test
    void ownedSubmitThatHpdsAnswersWithNoStatusIs502AndSavesNothing() {
        assertNotAcceptedByHpds(hpdsAnswer(null, QueryIdentity.idFor(ALICE, cohort()).toString(), null));
    }

    @Test
    void ownedSubmitThatHpdsStoredUnderAnotherIdIs502AndSavesNothing() {
        assertNotAcceptedByHpds(hpdsAnswer(PicSureStatus.PENDING, "0d9f6c1e-7a52-4b7e-8d0a-3f1b2c4d5e6f", null));
    }

    @Test
    void ownedSubmitThatHpdsAnswersWithNoBodyIs502AndSavesNothing() {
        assertNotAcceptedByHpds(null);
    }

    @Test
    void ownedSubmitWithABodyThatIsNotAV3QueryIs400BeforeAnythingIsLookedUpSentOrSaved() {
        Map<String, Object> notAQuery = Map.of("expectedResultType", "DATAFRAME", "numericFilters", Map.of());

        assertThatThrownBy(() -> ownedService.query("auth", new GeneralQueryRequest().setQuery(notAQuery), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(refused.getErrorType()).isEqualTo("bad_request");
                assertThat(refused.getMessage()).isEqualTo("Incorrectly formatted request");
            });

        verifyNoInteractions(psama, hpds, operationsClient);
    }

    @Test
    void ownedSubmitWithATextBodyIs400BeforeAnythingIsLookedUpSentOrSaved() {
        assertThatThrownBy(() -> ownedService.query("auth", new GeneralQueryRequest().setQuery("q"), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(psama, hpds, operationsClient);
    }

    @Test
    void anUppercaseCanonicalUuidIsOwnedUnderTheSameOwnerAsItsLowercaseForm() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();
        GatewayUser uppercase = new GatewayUser(ALICE.toString().toUpperCase(java.util.Locale.ROOT), null, null, null, Set.of());

        QueryStatus out = ownedService.query("auth", envelope(cohort()), TOKEN, uppercase);

        SaveQueryRequest saved = onlySave();
        assertThat(saved.userUuid()).isEqualTo(ALICE);
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(out.getPicsureResultId()).isEqualTo(id);
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

    private static Query cohortAs(ResultType type, boolean sample) {
        return new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), type, null, null, sample);
    }

    private List<SaveQueryRequest> saves(int count) {
        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient, times(count)).save(saved.capture());
        return saved.getAllValues();
    }

    @Test
    void firstSubmitSavesTheGeneralIdSampleAndResultType() {
        sampleProperties.setEnabled(true);
        Query pfbSample = cohortAs(ResultType.DATAFRAME_PFB, true);
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(pfbSample), TOKEN, user(ALICE));

        SaveQueryRequest saved = onlySave();
        assertThat(saved.trigger()).isEqualTo("FIRST_SUBMIT");
        assertThat(saved.picsureId()).isEqualTo(QueryIdentity.idFor(ALICE, pfbSample));
        assertThat(saved.queryToVariantUuid()).isEqualTo(QueryIdentity.generalIdFor(ALICE, pfbSample)).isNotEqualTo(saved.picsureId());
        assertThat(saved.sample()).isTrue();
        assertThat(saved.resultType()).isEqualTo("DATAFRAME_PFB");
    }

    @Test
    void firstSubmitOfAFullQuerySavesSampleFalse() {
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));

        SaveQueryRequest saved = onlySave();
        assertThat(saved.queryToVariantUuid()).isEqualTo(QueryIdentity.generalIdFor(ALICE, cohort()));
        assertThat(saved.sample()).isFalse();
        assertThat(saved.resultType()).isEqualTo("DATAFRAME");
    }

    @Test
    void generalIdOfASubmitIgnoresTheAuthorizationFiltersAndThePicsureIdTheClientSent() {
        Query fromTheClient = cohortWithClientFilters("\\client\\", "narrow").setPicsureId(UUID.randomUUID());
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(fromTheClient), TOKEN, user(ALICE));

        assertThat(onlySave().queryToVariantUuid()).isEqualTo(QueryIdentity.generalIdFor(ALICE, cohort()));
    }

    @Test
    void sampleAndFullSubmitsShareAGeneralIdAndHaveDifferentRowIds() {
        sampleProperties.setEnabled(true);
        Query full = cohortAs(ResultType.DATAFRAME, false);
        Query sample = cohortAs(ResultType.DATAFRAME, true);
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(full), TOKEN, user(ALICE));
        ownedService.query("auth", envelope(sample), TOKEN, user(ALICE));

        List<SaveQueryRequest> saved = saves(2);
        assertThat(saved).extracting(SaveQueryRequest::queryToVariantUuid).containsOnly(QueryIdentity.generalIdFor(ALICE, full));
        assertThat(saved).extracting(SaveQueryRequest::picsureId)
            .containsExactly(QueryIdentity.idFor(ALICE, full), QueryIdentity.idFor(ALICE, sample)).doesNotHaveDuplicates();
        assertThat(saved).extracting(SaveQueryRequest::sample).containsExactly(false, true);
        assertThat(saved).extracting(SaveQueryRequest::resultType).containsExactly("DATAFRAME", "DATAFRAME");
    }

    @Test
    void dataframeAndPfbSubmitsShareAGeneralId() {
        Query dataframe = cohortAs(ResultType.DATAFRAME, false);
        Query pfb = cohortAs(ResultType.DATAFRAME_PFB, false);
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(dataframe), TOKEN, user(ALICE));
        ownedService.query("auth", envelope(pfb), TOKEN, user(ALICE));

        List<SaveQueryRequest> saved = saves(2);
        assertThat(saved).extracting(SaveQueryRequest::queryToVariantUuid).containsOnly(QueryIdentity.generalIdFor(ALICE, dataframe));
        assertThat(saved).extracting(SaveQueryRequest::picsureId)
            .containsExactly(QueryIdentity.idFor(ALICE, dataframe), QueryIdentity.idFor(ALICE, pfb)).doesNotHaveDuplicates();
        assertThat(saved).extracting(SaveQueryRequest::sample).containsExactly(false, false);
        assertThat(saved).extracting(SaveQueryRequest::resultType).containsExactly("DATAFRAME", "DATAFRAME_PFB");
    }

    @Test
    void twoUsersSubmittingOneQueryGetDifferentGeneralIds() {
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE));
        ownedService.query("auth", envelope(cohort()), TOKEN, user(BOB));

        assertThat(saves(2)).extracting(SaveQueryRequest::queryToVariantUuid)
            .containsExactly(QueryIdentity.generalIdFor(ALICE, cohort()), QueryIdentity.generalIdFor(BOB, cohort()))
            .doesNotHaveDuplicates();
    }

    @Test
    void rerunSavesTheSameGeneralId() {
        sampleProperties.setEnabled(true);
        Query pfbSample = cohortAs(ResultType.DATAFRAME_PFB, true);
        UUID id = QueryIdentity.idFor(ALICE, pfbSample);
        callerHoldsConsents("phs001.c1");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(pfbSample), TOKEN, user(ALICE));
        StoredQuery stored = rowOwnedByAlice(pfbSample, "AVAILABLE", BOTH_CONSENTS);
        ownedService.queryStatus("auth", stored.picsureId(), TOKEN, user(ALICE));
        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, ALICE, "ERROR", ONE_CONSENT)));
        ownedService.query("auth", envelope(pfbSample), TOKEN, user(ALICE));

        List<SaveQueryRequest> saved = saves(3);
        assertThat(saved).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT", "CONSENT_CHANGE", "RESUBMIT");
        assertThat(saved).extracting(SaveQueryRequest::picsureId).containsOnly(id);
        assertThat(saved).extracting(SaveQueryRequest::queryToVariantUuid).containsOnly(QueryIdentity.generalIdFor(ALICE, pfbSample));
        assertThat(saved).extracting(SaveQueryRequest::sample).containsOnly(true);
        assertThat(saved).extracting(SaveQueryRequest::resultType).containsOnly("DATAFRAME_PFB");
    }

    @Test
    void ownerlessSubmitSavesNoGeneralId() {
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-legacy"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        GatewayUser notAUuid = new GatewayUser("auth0|alice", null, null, null, Set.of());

        ownedService.query("auth", envelope(cohortAs(ResultType.DATAFRAME_PFB, false)), TOKEN, notAUuid);

        assertSavedWithoutAnOwner("rr-legacy");
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
            operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), false), sampleRules,
            rowLocks, unsettledRows
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

    private QueryService consentOffService() {
        return new QueryService(
            operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), false), sampleRules,
            rowLocks, unsettledRows
        );
    }

    private static Query cohortWithClientFilters(String conceptPath, String value) {
        return cohort().setAuthorizationFilters(List.of(new AuthorizationFilter(conceptPath, Set.of(value))));
    }

    @Test
    void ownedSubmitWithConsentAuthorizationOffSendsAndStoresNoClientAuthorizationFilters() throws Exception {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = consentOffService().query("auth", envelope(cohortWithClientFilters("\\client\\", "narrow")), null, user(ALICE));

        verify(hpds).query(any(HpdsTarget.class), any(), eq(id));
        assertThat(queriesSentToHpds).hasSize(1);
        assertThat(((Query) queriesSentToHpds.get(0)).authorizationFilters()).isEmpty();
        SaveQueryRequest saved = onlySave();
        assertThat(MAPPER.readTree(saved.query()).get("query").get("authorizationFilters")).isEmpty();
        assertThat(saved.query()).doesNotContain("client").doesNotContain("narrow");
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        verifyNoInteractions(psama);
    }

    @Test
    void ownedSubmitsWithConsentAuthorizationOffThatDifferOnlyInClientAuthorizationFiltersAreOneRow() {
        QueryService consentOff = consentOffService();
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        hpdsAcceptsOwnedSubmits();

        QueryStatus first = consentOff.query("auth", envelope(cohortWithClientFilters("\\client\\", "narrow")), null, user(ALICE));

        assertThat(first.getPicsureResultId()).isEqualTo(id);
        assertThat(onlySave().picsureId()).isEqualTo(id);
        assertThat(((Query) queriesSentToHpds.get(0)).authorizationFilters()).isEmpty();

        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, ALICE, "AVAILABLE", Map.of())));
        QueryStatus second = consentOff.query("auth", envelope(cohortWithClientFilters("\\other\\", "wide")), null, user(ALICE));

        assertThat(second.getPicsureResultId()).isEqualTo(id);
        assertThat(second.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(queriesSentToHpds).hasSize(1);
        verify(hpds, times(1)).query(any(HpdsTarget.class), any(), any(UUID.class));
        verify(operationsClient, times(1)).save(any());
    }

    @Test
    void ownedSubmitWithConsentAuthorizationOnSendsTheServersFiltersInPlaceOfTheClients() {
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsAcceptsOwnedSubmits();

        ownedService.query("auth", envelope(cohortWithClientFilters("\\client\\", "narrow")), TOKEN, user(ALICE));

        assertThat(((Query) queriesSentToHpds.get(0)).authorizationFilters())
            .containsExactly(new AuthorizationFilter(CONSENT_PATH, Set.of("phs001.c1", "phs001.c2")));
        assertThat(onlySave().picsureId()).isEqualTo(QueryIdentity.idFor(ALICE, cohort()));
    }

    @Test
    void createWithoutAnOwnerAndConsentAuthorizationOffForwardsAndStoresTheClientsAuthorizationFilters() throws Exception {
        AuthorizationFilter fromTheClient = new AuthorizationFilter("\\client\\", Set.of("narrow"));
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-legacy"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        GatewayUser notAUuid = new GatewayUser("auth0|alice", null, null, null, Set.of());

        consentOffService().query("auth", envelope(cohortWithClientFilters("\\client\\", "narrow")), null, notAUuid);

        ArgumentCaptor<QueryRequest> sent = ArgumentCaptor.forClass(QueryRequest.class);
        verify(hpds).query(any(HpdsTarget.class), sent.capture());
        assertThat(((Query) sent.getValue().getQuery()).authorizationFilters()).containsExactly(fromTheClient);
        JsonNode storedFilters = MAPPER.readTree(onlySave().query()).get("query").get("authorizationFilters");
        assertThat(storedFilters).hasSize(1);
        assertThat(storedFilters.get(0).get("conceptPath").asText()).isEqualTo("\\client\\");
        assertSavedWithoutAnOwner("rr-legacy");
    }

    @Test
    void syncWithConsentAuthorizationOffForwardsTheClientsAuthorizationFilters() {
        AuthorizationFilter fromTheClient = new AuthorizationFilter("\\client\\", Set.of("narrow"));
        when(hpds.querySync(any(HpdsTarget.class), any(), any())).thenReturn(new ResourceWebClient.QuerySyncResult("7".getBytes(), null));
        Query count = new Query(List.of(), List.of(fromTheClient), null, List.of(), ResultType.COUNT, null, null);

        consentOffService().querySync("auth", envelope(count), "UI", null, user(ALICE));

        ArgumentCaptor<QueryRequest> sent = ArgumentCaptor.forClass(QueryRequest.class);
        verify(hpds).querySync(any(HpdsTarget.class), sent.capture(), any());
        assertThat(((Query) sent.getValue().getQuery()).authorizationFilters()).containsExactly(fromTheClient);
    }

    @Test
    void aRowUnderTheDerivedIdThatBelongsToSomeoneElseIsAServerFaultThatNamesNoOwner() {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        callerHoldsConsents("phs001.c1");
        when(operationsClient.find(id)).thenReturn(Optional.of(ownedRow(id, BOB, "AVAILABLE", Map.of(CONSENT_PATH, List.of("phs001.c1")))));

        assertThatThrownBy(() -> ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, fault -> {
                assertThat(fault.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(fault.getErrorType()).isEqualTo("internal_server_error");
                assertThat(fault.getMessage()).isEqualTo("The query could not be stored").doesNotContain(id.toString());
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

        assertThatThrownBy(() -> ownedService.query("auth", envelope(cohort()), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, fault -> {
                assertThat(fault.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(fault.getErrorType()).isEqualTo("internal_server_error");
                assertThat(fault.getMessage()).isEqualTo("The query could not be stored");
            });

        verifyNoInteractions(hpds);
        verify(operationsClient, never()).save(any());
    }

    private void assertSavedWithoutAnOwner(String resourceResultId) {
        SaveQueryRequest saved = onlySave();
        assertThat(saved.picsureId()).isNull();
        assertThat(saved.userUuid()).isNull();
        assertThat(saved.consents()).isNull();
        assertThat(saved.trigger()).isNull();
        assertThat(saved.queryToVariantUuid()).isNull();
        assertThat(saved.sample()).isNull();
        assertThat(saved.resultType()).isNull();
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
    @ValueSource(
        strings = {"auth0|alice", "OPEN_ACCESS:picsure.example.org", "5f0c1f0e6f0a4a579d6e0d1c6a3b9f11", " ", "1-2-3-4-5",
            " 5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11 ", "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11\n", "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f1",
            "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f1g"}
    )
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
            operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), false), sampleRules, rowLocks,
            unsettledRows
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

    private QueryService serviceLockingWith(QueryRowLocks locks, boolean consentAuthorizationOn) {
        return new QueryService(
            operationsClient, hpds, selector, new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), consentAuthorizationOn),
            sampleRules, locks, unsettledRows
        );
    }

    @Test
    void ownedSubmitLooksUpSendsAndSavesNothingOutsideTheLockOfItsRow() {
        QueryRowLocks neverRunsTheWork = mock(QueryRowLocks.class);

        serviceLockingWith(neverRunsTheWork, true).query("auth", envelope(cohort()), TOKEN, user(ALICE));

        verify(neverRunsTheWork).withLock(eq(QueryIdentity.idFor(ALICE, cohort())), any());
        verifyNoInteractions(psama, hpds, operationsClient);
    }

    @Test
    void ownedSubmitThatIsRefusedForItsBodyOrByASampleRuleTakesNoRowLock() {
        QueryRowLocks locks = mock(QueryRowLocks.class);
        QueryService refusing = serviceLockingWith(locks, true);

        assertThatThrownBy(() -> refusing.query("auth", new GeneralQueryRequest().setQuery("not a query"), TOKEN, user(ALICE)))
            .isInstanceOf(PicsureException.class);
        assertThatThrownBy(() -> refusing.query("auth", sampleRequest(ResultType.DATAFRAME), TOKEN, user(ALICE)))
            .isInstanceOf(PicsureException.class);

        verifyNoInteractions(locks, psama, hpds, operationsClient);
    }

    @Test
    void submitsWithoutAnOwnerAndSynchronousQueriesTakeNoRowLock() {
        QueryRowLocks locks = mock(QueryRowLocks.class);
        QueryService unowned = serviceLockingWith(locks, true);
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any())).thenReturn(hpdsStatus("rr-legacy"));
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        when(hpds.querySync(any(HpdsTarget.class), any(), any()))
            .thenReturn(new ResourceWebClient.QuerySyncResult("7".getBytes(), "hpds-sync-id"));
        Query count = new Query(List.of(), List.of(), null, List.of(), ResultType.COUNT, null, null);

        unowned.query("open", envelope(cohort()), TOKEN, user(ALICE));
        unowned.query("auth", envelope(cohort()), TOKEN, null);
        unowned.query("auth", envelope(cohort()), TOKEN, new GatewayUser("auth0|alice", null, null, null, Set.of()));
        serviceLockingWith(locks, false).query("auth", envelope(null), TOKEN, user(ALICE));
        unowned.querySync("auth", envelope(count), "UI", TOKEN, user(ALICE));

        verify(hpds, times(4)).query(any(HpdsTarget.class), any());
        verify(hpds).querySync(any(HpdsTarget.class), any(), any());
        verifyNoInteractions(locks);
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
        QueryService scopedService = new QueryService(operationsClient, hpds, selector, consent, sampleRules, rowLocks, unsettledRows);
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

    private static final Map<String, List<String>> BOTH_CONSENTS = Map.of(CONSENT_PATH, List.of("phs001.c1", "phs001.c2"));
    private static final Map<String, List<String>> ONE_CONSENT = Map.of(CONSENT_PATH, List.of("phs001.c1"));
    private static final String NOT_THE_OWNER_MESSAGE = "Only the user who submitted this query can read it";
    private static final String NOT_READY_MESSAGE =
        "The result is not ready. Poll the status of the query and ask again once it reports AVAILABLE.";

    /** The three reads of a stored query, so that one test can run against each of them. */
    enum Read {
        STATUS {
            @Override
            Object call(QueryService service, String backend, UUID id, String authorizationHeader, GatewayUser caller) {
                return service.queryStatus(backend, id, authorizationHeader, caller);
            }
        },
        RESULT {
            @Override
            Object call(QueryService service, String backend, UUID id, String authorizationHeader, GatewayUser caller) {
                return service.queryResult(backend, id, authorizationHeader, caller);
            }
        },
        SIGNED_URL {
            @Override
            Object call(QueryService service, String backend, UUID id, String authorizationHeader, GatewayUser caller) {
                return service.queryResultSignedUrl(backend, id, authorizationHeader, caller);
            }
        };

        abstract Object call(QueryService service, String backend, UUID id, String authorizationHeader, GatewayUser caller);
    }

    /**
     * Stores a row as an owned submit of the query by Alice leaves it: under the derived id, with a body that carries that id and no
     * authorization filters, the row id as its result id, and a latest run.
     */
    private StoredQuery rowOwnedByAlice(Query query, String status, Map<String, List<String>> consents) {
        UUID id = QueryIdentity.idFor(ALICE, query);
        QueryRequest stored = envelope(query.setPicsureId(id).setAuthorizationFilters(List.of()));
        stored.setResourceUUID(UUID.randomUUID());
        try {
            StoredQuery row = new StoredQuery(
                id, MAPPER.writeValueAsString(stored), id.toString(), status, "3", null, 1L, null, ALICE, consents, UUID.randomUUID()
            );
            when(operationsClient.get(id)).thenReturn(row);
            return row;
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException(unwritable);
        }
    }

    private void hpdsHoldsTheJob(UUID id, PicSureStatus status) {
        QueryStatus job = hpdsStatus(id.toString());
        job.setStatus(status);
        when(hpds.queryStatus(any(HpdsTarget.class), eq(id.toString()))).thenReturn(job);
    }

    private void hpdsHasLostTheJob(UUID id) {
        when(hpds.queryStatus(any(HpdsTarget.class), eq(id.toString())))
            .thenThrow(new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Not Found"));
    }

    private void hpdsServesTheResult(UUID id) {
        when(hpds.queryResult(any(HpdsTarget.class), eq(id.toString())))
            .thenReturn(org.springframework.http.ResponseEntity.ok(new byte[] {7}));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq(id.toString()))).thenReturn(new SignedUrlResponse("https://s3/owned"));
    }

    private void verifyNoResultWasFetched() {
        verify(hpds, never()).queryResult(any(), any());
        verify(hpds, never()).queryResultSignedUrl(any(), any());
    }

    private void verifyNothingWasRunOrWritten() {
        verify(hpds, never()).query(any(), any());
        verify(hpds, never()).query(any(), any(), any());
        verify(operationsClient, never()).save(any());
        verify(operationsClient, never()).update(any(), any());
        verify(operationsClient, never()).updateLatestRun(any(), any());
    }

    private static void assertNamesNoConsentOrUser(String text) {
        assertThat(text).doesNotContain("phs001").doesNotContain("_consents").doesNotContain(ALICE.toString())
            .doesNotContain(BOB.toString());
    }

    private static void assertResultNotReady(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(PicsureException.class, notReady -> {
            assertThat(notReady.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(notReady.getErrorType()).isEqualTo("result_not_ready");
            assertThat(notReady.getMessage()).isEqualTo(NOT_READY_MESSAGE);
        });
    }

    private void assertRerunUnder(UUID id, Map<String, List<String>> consents, String trigger) {
        verify(hpds, times(1)).query(any(HpdsTarget.class), any(), eq(id));
        verify(hpds, never()).query(any(HpdsTarget.class), any());
        Query sent = (Query) queriesSentToHpds.get(0);
        assertThat(sent.picsureId()).isEqualTo(id);
        assertThat(sent.select()).isEqualTo(cohort().select());
        assertThat(sent.authorizationFilters())
            .containsExactly(new AuthorizationFilter(CONSENT_PATH, Set.copyOf(consents.get(CONSENT_PATH))));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.picsureId()).isEqualTo(id);
        assertThat(saved.userUuid()).isEqualTo(ALICE);
        assertThat(saved.consents()).isEqualTo(consents);
        assertThat(saved.trigger()).isEqualTo(trigger);
        assertThat(saved.resourceResultId()).isEqualTo(id.toString());
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(saved.version()).isEqualTo("3");
        verify(psama, times(1)).fetch(TOKEN);
        verify(operationsClient, never()).update(any(), any());
        verify(operationsClient, never()).updateLatestRun(any(), any());
    }

    static Stream<Arguments> callersWhoAreNotAlice() {
        return Stream.of(
            Arguments.of("another user", user(BOB)), Arguments.of("no gateway user", null),
            Arguments.of("a user with no id", new GatewayUser(null, null, null, null, Set.of())),
            Arguments.of("a subject that is not a UUID", new GatewayUser("auth0|alice", ALICE.toString(), null, null, Set.of())),
            Arguments.of("the open access identity", new GatewayUser("OPEN_ACCESS:picsure.example.org", null, null, null, Set.of())),
            Arguments.of("Alice's id without hyphens", new GatewayUser(ALICE.toString().replace("-", ""), null, null, null, Set.of()))
        );
    }

    private void assertRefusedAsNotTheOwner(Read read, GatewayUser caller) {
        QueryRowLocks locks = mock(QueryRowLocks.class);
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);

        assertThatThrownBy(() -> read.call(serviceLockingWith(locks, true), "auth", row.picsureId(), TOKEN, caller))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(refused.getErrorType()).isEqualTo("forbidden");
                assertThat(refused.getMessage()).isEqualTo(NOT_THE_OWNER_MESSAGE).doesNotContain(row.picsureId().toString());
                assertNamesNoConsentOrUser(refused.getMessage());
            });

        verify(operationsClient).get(row.picsureId());
        verifyNoMoreInteractions(operationsClient);
        verifyNoInteractions(locks, psama, hpds);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callersWhoAreNotAlice")
    void statusByANonOwnerIs403(String name, GatewayUser caller) {
        assertRefusedAsNotTheOwner(Read.STATUS, caller);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callersWhoAreNotAlice")
    void resultByANonOwnerIs403(String name, GatewayUser caller) {
        assertRefusedAsNotTheOwner(Read.RESULT, caller);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callersWhoAreNotAlice")
    void signedUrlByANonOwnerIs403(String name, GatewayUser caller) {
        assertRefusedAsNotTheOwner(Read.SIGNED_URL, caller);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void ownedRowReadByItsOwnerThroughAnotherBackendIs403AndNothingIsLookedUpSentOrWritten(Read read) {
        QueryRowLocks locks = mock(QueryRowLocks.class);
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);

        assertThatThrownBy(() -> read.call(serviceLockingWith(locks, true), "open", row.picsureId(), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(refused.getErrorType()).isEqualTo("forbidden");
            });

        verify(operationsClient).get(row.picsureId());
        verifyNoMoreInteractions(operationsClient);
        verifyNoInteractions(locks, psama, hpds);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void readOfAnIdNoRowIsStoredUnderIs404AndNothingIsLookedUpOrSent(Read read) {
        QueryRowLocks locks = mock(QueryRowLocks.class);
        UUID unknown = QueryIdentity.idFor(ALICE, cohort());
        PicsureException noSuchRow = new PicsureException(HttpStatus.NOT_FOUND, "not_found", "No stored query for picsureId " + unknown);
        when(operationsClient.get(unknown)).thenThrow(noSuchRow);

        assertThatThrownBy(() -> read.call(serviceLockingWith(locks, true), "auth", unknown, TOKEN, user(ALICE))).isSameAs(noSuchRow);

        verifyNoInteractions(locks, psama, hpds);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void ownedReadByACallerWithNoConsentsIs403AndNothingIsRerunOrServed(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        when(psama.fetch(TOKEN)).thenReturn(Map.of());

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(refused.getErrorType()).isEqualTo("consent_denied");
                assertNamesNoConsentOrUser(refused.getMessage());
            });

        verifyNoInteractions(hpds);
        verifyNothingWasRunOrWritten();
    }

    private void assertConsentChangeOnStatusReruns(Map<String, List<String>> stored, Map<String, List<String>> current) throws Exception {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", stored);
        UUID id = row.picsureId();
        callerHoldsConsents(current.get(CONSENT_PATH).toArray(String[]::new));
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.queryStatus("auth", id, TOKEN, user(ALICE));

        assertRerunUnder(id, current, "CONSENT_CHANGE");
        assertThat(out.getStatus()).isEqualTo(PicSureStatus.PENDING);
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertThat(out.getResourceResultId()).isEqualTo(id.toString());
        assertThat(out.getResourceID()).isEqualTo(UUID.fromString(MAPPER.readTree(row.query()).path("resourceUUID").asText()));
        assertNamesNoConsentOrUser(MAPPER.writeValueAsString(out));
        verify(hpds, never()).queryStatus(any(), any());
        verifyNoResultWasFetched();
    }

    @Test
    void consentShrinkOnStatusRerunsAndRecordsARun() throws Exception {
        assertConsentChangeOnStatusReruns(BOTH_CONSENTS, ONE_CONSENT);
    }

    @Test
    void consentGrowOnStatusRerunsAndRecordsARun() throws Exception {
        assertConsentChangeOnStatusReruns(ONE_CONSENT, BOTH_CONSENTS);
    }

    static Stream<Arguments> consentChanges() {
        return Stream.of(Arguments.of("shrink", BOTH_CONSENTS, ONE_CONSENT), Arguments.of("grow", ONE_CONSENT, BOTH_CONSENTS));
    }

    private void assertConsentChangeAnswers409AndServesNothing(
        Read read, Map<String, List<String>> stored, Map<String, List<String>> current
    ) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", stored);
        UUID id = row.picsureId();
        callerHoldsConsents(current.get(CONSENT_PATH).toArray(String[]::new));
        hpdsHoldsTheJob(id, PicSureStatus.AVAILABLE);
        hpdsServesTheResult(id);
        hpdsAcceptsOwnedSubmits();

        assertThatThrownBy(() -> read.call(ownedService, "auth", id, TOKEN, user(ALICE))).satisfies(QueryServiceTest::assertResultNotReady);

        assertRerunUnder(id, current, "CONSENT_CHANGE");
        verifyNoResultWasFetched();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consentChanges")
    void consentChangeOnResultAnswers409AndDoesNotServeTheOldResult(
        String name, Map<String, List<String>> stored, Map<String, List<String>> current
    ) {
        assertConsentChangeAnswers409AndServesNothing(Read.RESULT, stored, current);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consentChanges")
    void consentChangeOnSignedUrlAnswers409AndDoesNotServeTheOldResult(
        String name, Map<String, List<String>> stored, Map<String, List<String>> current
    ) {
        assertConsentChangeAnswers409AndServesNothing(Read.SIGNED_URL, stored, current);
    }

    @ParameterizedTest
    @EnumSource(value = Read.class, names = {"RESULT", "SIGNED_URL"})
    void resultRunAgainAnswers409EvenWhenHpdsReportsTheNewRunAsFinishedAtOnce(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        UUID id = row.picsureId();
        callerHoldsConsents("phs001.c1");
        hpdsServesTheResult(id);
        when(hpds.query(any(HpdsTarget.class), any(), eq(id))).thenReturn(hpdsAnswer(PicSureStatus.AVAILABLE, id.toString(), null));

        assertThatThrownBy(() -> read.call(ownedService, "auth", id, TOKEN, user(ALICE))).satisfies(QueryServiceTest::assertResultNotReady);

        assertThat(onlySave().trigger()).isEqualTo("CONSENT_CHANGE");
        verifyNoResultWasFetched();
    }

    @Test
    void statusOnALostResultResubmitsAndReturnsRunning() throws Exception {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        UUID id = row.picsureId();
        callerHoldsConsents("phs001.c2", "phs001.c1");
        hpdsHasLostTheJob(id);
        hpdsAcceptsOwnedSubmits();

        QueryStatus out = ownedService.queryStatus("auth", id, TOKEN, user(ALICE));

        assertRerunUnder(id, BOTH_CONSENTS, "LOST_RESULT");
        assertThat(out.getStatus()).isEqualTo(PicSureStatus.PENDING);
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertNamesNoConsentOrUser(MAPPER.writeValueAsString(out));
    }

    private void assertLostResultStartsOneRerunAndAnswers409(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        UUID id = row.picsureId();
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHasLostTheJob(id);
        hpdsAcceptsOwnedSubmits();

        assertThatThrownBy(() -> read.call(ownedService, "auth", id, TOKEN, user(ALICE))).satisfies(QueryServiceTest::assertResultNotReady);

        assertRerunUnder(id, BOTH_CONSENTS, "LOST_RESULT");
        verifyNoResultWasFetched();
    }

    @Test
    void resultOnALostResultStartsOneRerunAndAnswers409() {
        assertLostResultStartsOneRerunAndAnswers409(Read.RESULT);
    }

    @Test
    void signedUrlOnALostResultStartsOneRerunAndAnswers409() {
        assertLostResultStartsOneRerunAndAnswers409(Read.SIGNED_URL);
    }

    private void assertUnfinishedResultAnswers409(Read read, PicSureStatus unfinished) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), unfinished);
        hpdsServesTheResult(row.picsureId());

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE)))
            .satisfies(QueryServiceTest::assertResultNotReady);

        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    @ParameterizedTest
    @EnumSource(value = PicSureStatus.class, names = {"QUEUED", "PENDING"})
    void resultOnARunningResultAnswers409ResultNotReady(PicSureStatus unfinished) {
        assertUnfinishedResultAnswers409(Read.RESULT, unfinished);
    }

    @ParameterizedTest
    @EnumSource(value = PicSureStatus.class, names = {"QUEUED", "PENDING"})
    void signedUrlOnARunningResultAnswers409ResultNotReady(PicSureStatus unfinished) {
        assertUnfinishedResultAnswers409(Read.SIGNED_URL, unfinished);
    }

    private void assertErroredResultAnswers400(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "ERROR", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.ERROR);
        hpdsServesTheResult(row.picsureId());

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, failed -> {
                assertThat(failed.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(failed.getErrorType()).isEqualTo("bad_request");
                assertThat(failed.getMessage()).isEqualTo("Status : ERROR");
            });

        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    @Test
    void resultOnAnErroredResultAnswers400() {
        assertErroredResultAnswers400(Read.RESULT);
    }

    @Test
    void signedUrlOnAnErroredResultAnswers400() {
        assertErroredResultAnswers400(Read.SIGNED_URL);
    }

    @Test
    void statusOfAnErroredOwnedRowReportsTheErrorAndDoesNotRerun() {
        StoredQuery row = rowOwnedByAlice(cohort(), "PENDING", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.ERROR);

        QueryStatus out = ownedService.queryStatus("auth", row.picsureId(), TOKEN, user(ALICE));

        assertThat(out.getStatus()).isEqualTo(PicSureStatus.ERROR);
        verify(hpds, never()).query(any(), any(), any());
        verify(operationsClient, never()).save(any());
        verify(operationsClient).updateLatestRun(eq(row.picsureId()), argThat((UpdateQueryRequest u) -> "ERROR".equals(u.status())));
    }

    @Test
    void resultOfAnAvailableOwnedRowUnderUnchangedConsentsIsServedWithoutTheSavedFilterCheck() {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c2", "phs001.c1");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        hpdsServesTheResult(row.picsureId());

        org.springframework.http.ResponseEntity<byte[]> out = ownedService.queryResult("auth", row.picsureId(), TOKEN, user(ALICE));

        assertThat(out.getBody()).containsExactly(7);
        verify(hpds).queryResult(argThat((HpdsTarget t) -> "http://hpds/PIC-SURE/v3".equals(t.baseUrl())), eq(row.picsureId().toString()));
        verify(psama, times(1)).fetch(TOKEN);
        verifyNothingWasRunOrWritten();
    }

    @Test
    void signedUrlOfAnAvailableOwnedRowUnderUnchangedConsentsIsServedWithoutTheSavedFilterCheck() {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c2", "phs001.c1");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        hpdsServesTheResult(row.picsureId());

        SignedUrlResponse out = ownedService.queryResultSignedUrl("auth", row.picsureId(), TOKEN, user(ALICE));

        assertThat(out.getSignedUrl()).isEqualTo("https://s3/owned");
        verify(psama, times(1)).fetch(TOKEN);
        verifyNothingWasRunOrWritten();
    }

    @Test
    void resultIsServedFromWhatHpdsHoldsEvenWhenTheRowStillRecordsAnEarlierStatus() {
        StoredQuery row = rowOwnedByAlice(cohort(), "PENDING", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        hpdsServesTheResult(row.picsureId());

        assertThat(ownedService.queryResult("auth", row.picsureId(), TOKEN, user(ALICE)).getBody()).containsExactly(7);
    }

    @Test
    void statusPatchCarriesTheRunIdItLoaded() throws Exception {
        StoredQuery row = rowOwnedByAlice(cohort(), "PENDING", BOTH_CONSENTS);
        UUID id = row.picsureId();
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(id, PicSureStatus.AVAILABLE);
        when(operationsClient.updateLatestRun(any(), any())).thenReturn(true);

        QueryStatus out = ownedService.queryStatus("auth", id, TOKEN, user(ALICE));

        assertThat(out.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(out.getPicsureResultId()).isEqualTo(id);
        assertThat(out.getResourceID()).isEqualTo(UUID.fromString(MAPPER.readTree(row.query()).path("resourceUUID").asText()));
        assertNamesNoConsentOrUser(MAPPER.writeValueAsString(out));
        verify(operationsClient).updateLatestRun(id, new UpdateQueryRequest("AVAILABLE", null, null, null, null, row.runId()));
        verify(operationsClient, never()).update(any(), any());
        verify(operationsClient, never()).save(any());
        verify(hpds, never()).query(any(), any(), any());
        verify(psama, times(1)).fetch(TOKEN);
    }

    @Test
    void statusWriteRefusedBecauseTheRowHasANewerRunDoesNotFailTheStatusCall() {
        StoredQuery row = rowOwnedByAlice(cohort(), "PENDING", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        when(operationsClient.updateLatestRun(any(), any())).thenReturn(false);

        QueryStatus out = ownedService.queryStatus("auth", row.picsureId(), TOKEN, user(ALICE));

        assertThat(out.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        verify(operationsClient).updateLatestRun(eq(row.picsureId()), any());
        verify(operationsClient, never()).update(any(), any());
        verify(operationsClient, never()).save(any());
    }

    @Test
    void hpds5xxOnStatusIs502AndDoesNotRerun() {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        HpdsCommunicationException hpdsFailed = new HpdsCommunicationException("HPDS call failed");
        when(hpds.queryStatus(any(HpdsTarget.class), any())).thenThrow(hpdsFailed);

        for (Read read : Read.values()) {
            assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE))).isSameAs(hpdsFailed);
        }

        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    @ParameterizedTest
    @EnumSource(value = Read.class, names = {"RESULT", "SIGNED_URL"})
    void hpds5xxOnTheResultOfAnAvailableRowIs502AndDoesNotRerun(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        HpdsCommunicationException resultFileIsGone = new HpdsCommunicationException("HPDS result call failed");
        when(hpds.queryResult(any(HpdsTarget.class), any())).thenThrow(resultFileIsGone);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), any())).thenThrow(resultFileIsGone);

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE))).isSameAs(resultFileIsGone);

        verify(hpds, times(1)).queryStatus(any(), any());
        verifyNothingWasRunOrWritten();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void hpdsStatusAnswerWithoutAStatusIs502AndDoesNotRerun(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        when(hpds.queryStatus(any(HpdsTarget.class), any())).thenReturn(hpdsAnswer(null, null, "Resource is locked."));

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE)))
            .isInstanceOf(HpdsCommunicationException.class).hasMessage("HPDS did not report the status of the query");

        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    @ParameterizedTest
    @EnumSource(value = Read.class, names = {"RESULT", "SIGNED_URL"})
    void resultThatHpdsLosesBetweenTheDecisionAndTheFetchIsRerunAndAnswers409(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        UUID id = row.picsureId();
        callerHoldsConsents("phs001.c1", "phs001.c2");
        PicsureException lost = new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Not Found");
        when(hpds.queryStatus(any(HpdsTarget.class), eq(id.toString())))
            .thenReturn(hpdsAnswer(PicSureStatus.AVAILABLE, id.toString(), null)).thenThrow(lost);
        when(hpds.queryResult(any(HpdsTarget.class), any())).thenThrow(lost);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), any())).thenThrow(lost);
        hpdsAcceptsOwnedSubmits();

        assertThatThrownBy(() -> read.call(ownedService, "auth", id, TOKEN, user(ALICE))).satisfies(QueryServiceTest::assertResultNotReady);

        verify(hpds, times(1)).query(any(HpdsTarget.class), any(), eq(id));
        SaveQueryRequest saved = onlySave();
        assertThat(saved.trigger()).isEqualTo("LOST_RESULT");
        assertThat(saved.consents()).isEqualTo(BOTH_CONSENTS);
    }

    @ParameterizedTest
    @EnumSource(value = Read.class, names = {"RESULT", "SIGNED_URL"})
    void resultThatIsRunAgainBetweenTheDecisionAndTheFetchAnswers409WithoutAnotherRerun(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        PicsureException unfinished = new PicsureException(HttpStatus.CONFLICT, "conflict", "Status : RUNNING");
        when(hpds.queryResult(any(HpdsTarget.class), any())).thenThrow(unfinished);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), any())).thenThrow(unfinished);

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE)))
            .satisfies(QueryServiceTest::assertResultNotReady);

        verifyNothingWasRunOrWritten();
    }

    private static void assertFailedResult(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(PicsureException.class, failed -> {
            assertThat(failed.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(failed.getErrorType()).isEqualTo("bad_request");
            assertThat(failed.getMessage()).isEqualTo("Status : ERROR");
        });
    }

    /** Reads a row stored as failed under the consents the caller still holds, and checks the answer comes from the row alone. */
    private void assertAnsweredAsFailedFromTheRow(Read read, StoredQuery row) throws Exception {
        UUID id = row.picsureId();
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsAcceptsOwnedSubmits();

        if (read == Read.STATUS) {
            QueryStatus out = ownedService.queryStatus("auth", id, TOKEN, user(ALICE));
            assertThat(out.getStatus()).isEqualTo(PicSureStatus.ERROR);
            assertThat(out.getPicsureResultId()).isEqualTo(id);
            assertThat(out.getResourceResultId()).isEqualTo(id.toString());
            assertThat(out.getResourceID()).isEqualTo(UUID.fromString(MAPPER.readTree(row.query()).path("resourceUUID").asText()));
            assertNamesNoConsentOrUser(MAPPER.writeValueAsString(out));
        } else {
            assertThatThrownBy(() -> read.call(ownedService, "auth", id, TOKEN, user(ALICE)))
                .satisfies(QueryServiceTest::assertFailedResult);
        }

        verify(psama, times(1)).fetch(TOKEN);
        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void readOfARowStoredAsFailedWhoseJobHpdsDoesNotHoldRunsAndWritesNothing(Read read) throws Exception {
        StoredQuery row = rowOwnedByAlice(cohort(), "ERROR", BOTH_CONSENTS);
        hpdsHasLostTheJob(row.picsureId());

        assertAnsweredAsFailedFromTheRow(read, row);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void readOfARowStoredAsFailedIsAnsweredFromTheRowEvenWhenHpdsHoldsAFinishedJobUnderItsId(Read read) throws Exception {
        StoredQuery row = rowOwnedByAlice(cohort(), "ERROR", BOTH_CONSENTS);
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        hpdsServesTheResult(row.picsureId());

        assertAnsweredAsFailedFromTheRow(read, row);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void consentChangeOnARowStoredAsFailedRunsItAgain(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "ERROR", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1");
        hpdsHasLostTheJob(row.picsureId());
        hpdsAcceptsOwnedSubmits();

        try {
            read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE));
        } catch (PicsureException notReady) {
            assertResultNotReady(notReady);
        }

        assertRerunUnder(row.picsureId(), ONE_CONSENT, "CONSENT_CHANGE");
        verify(hpds, never()).queryStatus(any(), any());
        verifyNoResultWasFetched();
    }

    static Stream<Arguments> rerunsOfAResultRead() {
        return Stream.of(
            Arguments.of(Read.RESULT, "CONSENT_CHANGE"), Arguments.of(Read.SIGNED_URL, "CONSENT_CHANGE"),
            Arguments.of(Read.RESULT, "LOST_RESULT"), Arguments.of(Read.SIGNED_URL, "LOST_RESULT")
        );
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("rerunsOfAResultRead")
    void rerunThatHpdsAnswersAsFailedSavesTheRowAsFailedOnceAndTheResultCallAnswers400(Read read, String trigger) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        UUID id = row.picsureId();
        if ("LOST_RESULT".equals(trigger)) {
            callerHoldsConsents("phs001.c1", "phs001.c2");
            hpdsHasLostTheJob(id);
        } else {
            callerHoldsConsents("phs001.c1");
        }
        hpdsServesTheResult(id);
        when(hpds.query(any(HpdsTarget.class), any(), eq(id))).thenReturn(hpdsAnswer(PicSureStatus.ERROR, id.toString(), "ERROR"));

        assertThatThrownBy(() -> read.call(ownedService, "auth", id, TOKEN, user(ALICE))).satisfies(QueryServiceTest::assertFailedResult);

        SaveQueryRequest saved = onlySave();
        assertThat(saved.status()).isEqualTo("ERROR");
        assertThat(saved.trigger()).isEqualTo(trigger);
        verify(hpds, times(1)).query(any(HpdsTarget.class), any(), eq(id));
        verifyNoResultWasFetched();
    }

    @Test
    void statusRerunThatHpdsAnswersAsFailedReportsTheFailureAndSavesTheRowAsFailedOnce() {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        UUID id = row.picsureId();
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any(), eq(id))).thenReturn(hpdsAnswer(PicSureStatus.ERROR, id.toString(), "ERROR"));

        QueryStatus out = ownedService.queryStatus("auth", id, TOKEN, user(ALICE));

        assertThat(out.getStatus()).isEqualTo(PicSureStatus.ERROR);
        assertThat(onlySave().status()).isEqualTo("ERROR");
        verify(operationsClient, never()).updateLatestRun(any(), any());
    }

    @ParameterizedTest
    @EnumSource(
        value = HttpStatus.class,
        names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "METHOD_NOT_ALLOWED", "CONFLICT", "GONE", "TOO_MANY_REQUESTS"}
    )
    void hpds4xxOtherThan404OnStatusIs502AndDoesNotRerun(HttpStatus refusal) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsServesTheResult(row.picsureId());
        hpdsAcceptsOwnedSubmits();
        PicsureException refused = new PicsureException(refusal, refusal.name().toLowerCase(), "what HPDS said");
        when(hpds.queryStatus(any(HpdsTarget.class), any())).thenThrow(refused);

        for (Read read : Read.values()) {
            assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE)))
                .isInstanceOfSatisfying(HpdsCommunicationException.class, fault -> {
                    assertThat(fault.getMessage()).isEqualTo("HPDS refused to report the status of the query");
                    assertThat(fault.getCause()).isSameAs(refused);
                });
        }

        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    private static Query sampleCohort() {
        return new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, null, null, true);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void sampleRowRerunWithFlagOffIs400SampleDisabled(Read read) {
        StoredQuery changed = rowOwnedByAlice(sampleCohort(), "AVAILABLE", ONE_CONSENT);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsAcceptsOwnedSubmits();

        assertThatThrownBy(() -> read.call(ownedService, "auth", changed.picsureId(), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(refused.getErrorType()).isEqualTo("sample_disabled");
            });

        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void sampleRowWhoseResultIsLostWithFlagOffIs400SampleDisabled(Read read) {
        StoredQuery lost = rowOwnedByAlice(sampleCohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHasLostTheJob(lost.picsureId());
        hpdsAcceptsOwnedSubmits();

        assertThatThrownBy(() -> read.call(ownedService, "auth", lost.picsureId(), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> assertThat(refused.getErrorType()).isEqualTo("sample_disabled"));

        verifyNoResultWasFetched();
        verifyNothingWasRunOrWritten();
    }

    @Test
    void sampleRowRerunWithFlagOnSendsTheSampleFlagAgain() {
        sampleProperties.setEnabled(true);
        StoredQuery row = rowOwnedByAlice(sampleCohort(), "AVAILABLE", ONE_CONSENT);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsAcceptsOwnedSubmits();

        ownedService.queryStatus("auth", row.picsureId(), TOKEN, user(ALICE));

        assertThat(((Query) queriesSentToHpds.get(0)).sample()).isTrue();
        assertThat(onlySave().trigger()).isEqualTo("CONSENT_CHANGE");
    }

    @Test
    void sampleRowThatNeedsNoRerunIsReadWithFlagOff() {
        StoredQuery row = rowOwnedByAlice(sampleCohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1", "phs001.c2");
        hpdsHoldsTheJob(row.picsureId(), PicSureStatus.AVAILABLE);
        hpdsServesTheResult(row.picsureId());

        assertThat(ownedService.queryStatus("auth", row.picsureId(), TOKEN, user(ALICE)).getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(ownedService.queryResult("auth", row.picsureId(), TOKEN, user(ALICE)).getBody()).containsExactly(7);
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void rerunThatHpdsAnswersWithoutAJobIs502AndSavesNothing(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1");
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenReturn(hpdsAnswer(null, null, "Resource is locked."));

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE)))
            .isInstanceOf(HpdsCommunicationException.class).hasMessage("HPDS did not accept the query");

        verify(operationsClient, never()).save(any());
        verifyNoResultWasFetched();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void rerunThatHpdsRejectsSavesNothing(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1");
        PicsureException rejected = new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "HPDS refused the query");
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenThrow(rejected);

        assertThatThrownBy(() -> read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE))).isSameAs(rejected);

        verify(operationsClient, never()).save(any());
        verifyNoResultWasFetched();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void ownedReadWithConsentAuthorizationOffChecksTheJobInsideTheRowLockLooksUpNoConsentsAndRerunsNothing(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", Map.of());
        UUID id = row.picsureId();
        List<Integer> rowsLockedWhenHpdsWasAskedForTheJob = new ArrayList<>();
        when(hpds.queryStatus(any(HpdsTarget.class), eq(id.toString()))).thenAnswer(ask -> {
            rowsLockedWhenHpdsWasAskedForTheJob.add(rowLocks.rowsInUse());
            return hpdsAnswer(PicSureStatus.AVAILABLE, id.toString(), null);
        });
        hpdsServesTheResult(id);
        when(operationsClient.updateLatestRun(any(), any())).thenReturn(true);

        assertThat(read.call(consentOffService(), "auth", id, null, user(ALICE))).isNotNull();

        verifyNoInteractions(psama);
        assertThat(rowsLockedWhenHpdsWasAskedForTheJob).as("HPDS is asked for the job once, inside the lock of the row").containsExactly(1);
        verify(hpds, never()).query(any(), any());
        verify(hpds, never()).query(any(), any(), any());
        verify(operationsClient, never()).save(any());
        verify(operationsClient, never()).update(any(), any());
        if (read == Read.STATUS) {
            verify(operationsClient).updateLatestRun(id, new UpdateQueryRequest("AVAILABLE", null, null, null, null, row.runId()));
        } else {
            verify(operationsClient, never()).updateLatestRun(any(), any());
        }
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void ownedRowReadByAnotherUserWithConsentAuthorizationOffIs403(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", Map.of());

        assertThatThrownBy(() -> read.call(consentOffService(), "auth", row.picsureId(), null, user(BOB)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> {
                assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(refused.getErrorType()).isEqualTo("forbidden");
            });

        verifyNoInteractions(psama, hpds);
    }

    @Test
    void ownedRowStoredWithConsentsIsRunAgainWithoutFiltersOnceConsentAuthorizationIsOff() {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        hpdsAcceptsOwnedSubmits();

        consentOffService().queryStatus("auth", row.picsureId(), null, user(ALICE));

        assertThat(((Query) queriesSentToHpds.get(0)).authorizationFilters()).isEmpty();
        SaveQueryRequest saved = onlySave();
        assertThat(saved.consents()).isNotNull().isEmpty();
        assertThat(saved.trigger()).isEqualTo("CONSENT_CHANGE");
    }

    @Test
    void ownedReadsLookUpConsentsAskHpdsAndWriteInsideTheLockOfTheirRowAndFetchTheResultOutsideIt() {
        StoredQuery row = rowOwnedByAlice(cohort(), "PENDING", BOTH_CONSENTS);
        UUID id = row.picsureId();
        List<String> stepsOutsideTheLock = new ArrayList<>();
        List<String> stepsInsideTheLock = new ArrayList<>();
        Consumer<String> step = name -> (rowLocks.rowsInUse() == 1 ? stepsInsideTheLock : stepsOutsideTheLock).add(name);
        when(psama.fetch(TOKEN)).thenAnswer(lookup -> {
            step.accept("consent lookup");
            return Map.of(CONSENT_PATH, Set.of("phs001.c1", "phs001.c2"));
        });
        when(hpds.queryStatus(any(HpdsTarget.class), any())).thenAnswer(ask -> {
            step.accept("hpds status");
            return hpdsAnswer(PicSureStatus.AVAILABLE, id.toString(), null);
        });
        when(operationsClient.updateLatestRun(any(), any())).thenAnswer(write -> {
            step.accept("status write");
            return true;
        });
        when(hpds.queryResult(any(HpdsTarget.class), any())).thenAnswer(fetch -> {
            step.accept("result fetch");
            return org.springframework.http.ResponseEntity.ok(new byte[] {7});
        });
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), any())).thenAnswer(fetch -> {
            step.accept("signed url fetch");
            return new SignedUrlResponse("https://s3/owned");
        });

        ownedService.queryStatus("auth", id, TOKEN, user(ALICE));
        ownedService.queryResult("auth", id, TOKEN, user(ALICE));
        ownedService.queryResultSignedUrl("auth", id, TOKEN, user(ALICE));

        assertThat(stepsInsideTheLock).containsExactly(
            "consent lookup", "hpds status", "status write", "consent lookup", "hpds status", "consent lookup", "hpds status"
        );
        assertThat(stepsOutsideTheLock).containsExactly("result fetch", "signed url fetch");
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void rerunSubmitsAndSavesInsideTheLockOfItsRow(Read read) {
        StoredQuery row = rowOwnedByAlice(cohort(), "AVAILABLE", BOTH_CONSENTS);
        callerHoldsConsents("phs001.c1");
        List<Integer> rowsLockedDuringSubmitAndSave = new ArrayList<>();
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenAnswer(submit -> {
            rowsLockedDuringSubmitAndSave.add(rowLocks.rowsInUse());
            return hpdsStatus(submit.getArgument(2).toString());
        });
        when(operationsClient.save(any())).thenAnswer(save -> {
            rowsLockedDuringSubmitAndSave.add(rowLocks.rowsInUse());
            return row.picsureId();
        });

        try {
            read.call(ownedService, "auth", row.picsureId(), TOKEN, user(ALICE));
        } catch (PicsureException notReady) {
            assertResultNotReady(notReady);
        }

        assertThat(rowsLockedDuringSubmitAndSave).containsExactly(1, 1);
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @Test
    void ownerlessRowKeepsVerifyReadAccess() {
        QueryRowLocks locks = mock(QueryRowLocks.class);
        QueryService ownerless = new QueryService(operationsClient, hpds, selector, consent, sampleRules, locks, unsettledRows);
        UUID id = UUID.randomUUID();
        StoredQuery stored = new StoredQuery(id, "{}", "rr-2", "AVAILABLE", "3", null);
        when(operationsClient.get(id)).thenReturn(stored);
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-2"))).thenReturn(hpdsAnswer(PicSureStatus.AVAILABLE, "rr-2", null));
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"))).thenReturn(org.springframework.http.ResponseEntity.ok(new byte[] {1}));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-2"))).thenReturn(new SignedUrlResponse("https://s3/x"));

        assertThat(ownerless.queryStatus("auth", id, TOKEN, user(BOB)).getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        verify(consent, never()).verifyReadAccess(any(), any(), any());
        assertThat(ownerless.queryResult("auth", id, TOKEN, user(BOB)).getBody()).containsExactly(1);
        assertThat(ownerless.queryResultSignedUrl("auth", id, TOKEN, null).getSignedUrl()).isEqualTo("https://s3/x");

        verify(consent, times(2)).verifyReadAccess("auth", stored, TOKEN);
        verify(consent, never()).currentConsents(any(), any());
        verify(operationsClient).update(id, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        verify(operationsClient, never()).updateLatestRun(any(), any());
        verify(operationsClient, never()).save(any());
        verify(hpds, never()).query(any(), any(), any());
        verifyNoInteractions(locks);
    }

    @ParameterizedTest
    @EnumSource(value = Read.class, names = {"RESULT", "SIGNED_URL"})
    void ownerlessResultThatIsStillRunningAnswers409ResultNotReady(Read read) {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-2", "PENDING", "3", null));
        PicsureException unfinished = new PicsureException(HttpStatus.CONFLICT, "conflict", "Status : RUNNING");
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"))).thenThrow(unfinished);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-2"))).thenThrow(unfinished);

        assertThatThrownBy(() -> read.call(service, "auth", id, TOKEN, user(BOB))).satisfies(QueryServiceTest::assertResultNotReady);

        verifyNothingWasRunOrWritten();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void ownerlessRowWhoseResultHpdsLostKeepsThe404AndIsNotRerun(Read read) {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-2", "AVAILABLE", "3", null));
        PicsureException lost = new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Not Found");
        when(hpds.queryStatus(any(HpdsTarget.class), eq("rr-2"))).thenThrow(lost);
        when(hpds.queryResult(any(HpdsTarget.class), eq("rr-2"))).thenThrow(lost);
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), eq("rr-2"))).thenThrow(lost);

        assertThatThrownBy(() -> read.call(service, "auth", id, TOKEN, user(BOB))).isSameAs(lost);

        verifyNothingWasRunOrWritten();
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
    void metadataDoesNotCallConsentLookup() {
        UUID id = UUID.randomUUID();
        when(operationsClient.get(id)).thenReturn(new StoredQuery(id, "{}", "rr-1", "AVAILABLE", "3", null));

        service.queryMetadata(id);

        verify(operationsClient).get(id);
        verifyNoInteractions(consent);
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
