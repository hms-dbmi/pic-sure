package edu.harvard.hms.dbmi.avillach.query.query;

import static edu.harvard.hms.dbmi.avillach.query.query.QueryServiceOwnedSubmitConcurrencyTest.await;
import static edu.harvard.hms.dbmi.avillach.query.query.QueryServiceOwnedSubmitConcurrencyTest.awaitOpenedOrParked;
import static edu.harvard.hms.dbmi.avillach.query.query.QueryServiceOwnedSubmitConcurrencyTest.locksWaiting;
import static edu.harvard.hms.dbmi.avillach.query.query.QueryServiceOwnedSubmitConcurrencyTest.resultOf;
import static edu.harvard.hms.dbmi.avillach.query.query.QueryServiceOwnedSubmitConcurrencyTest.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.config.SampleProperties;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentFilterBuilder;
import edu.harvard.hms.dbmi.avillach.query.consent.PsamaConsentClient;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.query.QueryServiceTest.Read;

/**
 * Covers status, result and signed-url requests for an owned row that arrive at the same time as each other or as an owned submit. PSAMA,
 * HPDS and operations-service are stand-ins. Operations-service keeps the rows it is asked to save, gives each save a new run, and refuses
 * a status write that names a run other than the latest one of the row, as the real one does. HPDS keeps one job per id, answers a status
 * request for an id it holds no job under with 404, and records the queries it receives in the order they arrive.
 *
 * <p>Each test holds one request inside a stand-in until the other request has gone as far as it can: until it has made the same call, or
 * until its thread is parked waiting for the row lock. Requests are lined up with latches and that check, never by sleeping for a fixed
 * time, so each test reaches the same interleaving on every run and would reach the unsafe one if the read took no lock.
 */
class QueryServiceOwnedReadConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "Bearer caller-token";
    private static final String CONSENT_PATH = "\\_consents\\";
    private static final Set<String> BOTH_CONSENTS = Set.of("phs001.c1", "phs001.c2");
    private static final Set<String> ONE_CONSENT = Set.of("phs001.c1");
    private static final UUID ALICE = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID ROW = QueryIdentity.idFor(ALICE, cohort());

    OperationsClient operationsClient = mock(OperationsClient.class);
    ResourceWebClient hpds = mock(ResourceWebClient.class);
    PsamaConsentClient psama = mock(PsamaConsentClient.class);
    QueryRowLocks rowLocks = locksWaiting(30);
    QueryService service = serviceWith(rowLocks);

    Map<UUID, StoredQuery> rows = new ConcurrentHashMap<>();
    List<SaveQueryRequest> saves = Collections.synchronizedList(new ArrayList<>());
    List<UpdateQueryRequest> refusedStatusWrites = Collections.synchronizedList(new ArrayList<>());
    Map<String, PicSureStatus> jobsHpdsHolds = new ConcurrentHashMap<>();
    List<Query> queriesHpdsReceived = Collections.synchronizedList(new ArrayList<>());
    /** What the operations-service stand-in does with a save. A test replaces it to hold one save back until another has happened. */
    volatile Function<SaveQueryRequest, UUID> saving = this::store;
    /** What the HPDS stand-in does with a submit. A test replaces it to hold one submit back until another request has moved. */
    volatile Function<InvocationOnMock, QueryStatus> submitting = this::receive;
    /** What the HPDS stand-in does with a status request. A test replaces it to hold a poller between its read and its write. */
    volatile Function<String, QueryStatus> reportingStatus = this::statusOfTheJob;

    private QueryService serviceWith(QueryRowLocks locks) {
        HpdsProperties hpdsProperties = new HpdsProperties();
        hpdsProperties.setAuthUrl("http://hpds/PIC-SURE");
        return new QueryService(
            operationsClient, hpds, new HpdsBackendSelector(hpdsProperties),
            new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), true), new SampleRules(new SampleProperties()), locks
        );
    }

    @BeforeEach
    void standInsKeepRowsAndJobs() {
        when(operationsClient.find(any())).thenAnswer(read -> Optional.ofNullable(rows.get(read.<UUID>getArgument(0))));
        when(operationsClient.get(any())).thenAnswer(read -> Optional.ofNullable(rows.get(read.<UUID>getArgument(0))).orElseThrow());
        when(operationsClient.save(any())).thenAnswer(write -> saving.apply(write.getArgument(0)));
        when(operationsClient.updateLatestRun(any(), any())).thenAnswer(write -> recordStatus(write.getArgument(0), write.getArgument(1)));
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenAnswer(submit -> submitting.apply(submit));
        when(hpds.queryStatus(any(HpdsTarget.class), any())).thenAnswer(ask -> reportingStatus.apply(ask.getArgument(1)));
    }

    private UUID store(SaveQueryRequest save) {
        saves.add(save);
        rows.put(
            save.picsureId(),
            new StoredQuery(
                save.picsureId(), save.query(), save.resourceResultId(), save.status(), save.version(), save.metadata(), 1L, null,
                save.userUuid(), save.consents(), UUID.randomUUID()
            )
        );
        return save.picsureId();
    }

    /** Applies a status write when it names the latest run of the row, and refuses it without changing the row otherwise. */
    private boolean recordStatus(UUID id, UpdateQueryRequest write) {
        StoredQuery row = rows.get(id);
        if (!row.runId().equals(write.runId())) {
            refusedStatusWrites.add(write);
            return false;
        }
        rows.put(
            id,
            new StoredQuery(
                id, row.query(), row.resourceResultId(), write.status(), row.version(), row.metadata(), row.startTime(), row.readyTime(),
                row.userUuid(), row.consents(), row.runId()
            )
        );
        return true;
    }

    private QueryStatus receive(InvocationOnMock submit) {
        queriesHpdsReceived.add(queryOf(submit));
        String id = submit.getArgument(2).toString();
        jobsHpdsHolds.put(id, PicSureStatus.PENDING);
        return job(id, PicSureStatus.PENDING);
    }

    private QueryStatus statusOfTheJob(String id) {
        PicSureStatus status = jobsHpdsHolds.get(id);
        if (status == null) {
            throw new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Not Found");
        }
        return job(id, status);
    }

    private static QueryStatus job(String id, PicSureStatus status) {
        QueryStatus job = new QueryStatus();
        job.setResourceResultId(id);
        job.setStatus(status);
        return job;
    }

    private static Query queryOf(InvocationOnMock submit) {
        return (Query) submit.<QueryRequest>getArgument(1).getQuery();
    }

    private static Set<String> consentsSentWith(Query query) {
        return query.authorizationFilters().stream().filter(filter -> CONSENT_PATH.equals(filter.conceptPath())).findFirst().orElseThrow()
            .values();
    }

    private static Set<String> consentsSavedWith(SaveQueryRequest save) {
        return Set.copyOf(save.consents().get(CONSENT_PATH));
    }

    private static GatewayUser alice() {
        return new GatewayUser(ALICE.toString(), null, null, null, Set.of());
    }

    private static Query cohort() {
        return new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, null, null);
    }

    /** Stores Alice's row as an owned submit under the given consents leaves it, with the given status and a latest run. */
    private StoredQuery aliceHasARow(Set<String> consents, String status) throws JsonProcessingException {
        String body = MAPPER.writeValueAsString(new GeneralQueryRequest().setQuery(cohort().setPicsureId(ROW)));
        StoredQuery row = new StoredQuery(
            ROW, body, ROW.toString(), status, "3", null, 1L, null, ALICE, Map.of(CONSENT_PATH, consents.stream().sorted().toList()),
            UUID.randomUUID()
        );
        rows.put(ROW, row);
        return row;
    }

    private FutureTask<QueryStatus> statusCall() {
        return new FutureTask<>(() -> service.queryStatus("auth", ROW, TOKEN, alice()));
    }

    private FutureTask<QueryStatus> submit() {
        return new FutureTask<>(() -> service.query("auth", new GeneralQueryRequest().setQuery(cohort()), TOKEN, alice()));
    }

    private FutureTask<ResponseEntity<byte[]>> resultCall() {
        return new FutureTask<>(() -> service.queryResult("auth", ROW, TOKEN, alice()));
    }

    private static void assertAnsweredNotReady(FutureTask<?> resultCall) {
        assertThatThrownBy(() -> resultOf(resultCall)).isInstanceOf(ExecutionException.class).cause()
            .isInstanceOfSatisfying(PicsureException.class, notReady -> {
                assertThat(notReady.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(notReady.getErrorType()).isEqualTo("result_not_ready");
            });
    }

    /** Holds the first submit that reaches HPDS until the other request has submitted too or is parked waiting for the row lock. */
    private void hpdsHoldsTheFirstSubmitUntilTheOtherRequestHasMoved(
        CountDownLatch firstSubmitReachedHpds, CompletableFuture<Thread> otherRequest
    ) {
        AtomicInteger submits = new AtomicInteger();
        CountDownLatch secondSubmitReachedHpds = new CountDownLatch(1);
        submitting = submit -> {
            if (submits.incrementAndGet() == 1) {
                firstSubmitReachedHpds.countDown();
                awaitOpenedOrParked(secondSubmitReachedHpds, otherRequest);
            } else {
                secondSubmitReachedHpds.countDown();
            }
            return receive(submit);
        };
    }

    @Test
    void twoConcurrentStatusCallsOnALostResultSubmitItOnceAndRecordOneLostResultRun() throws Exception {
        aliceHasARow(ONE_CONSENT, "AVAILABLE");
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, ONE_CONSENT));
        CompletableFuture<Thread> secondRequest = new CompletableFuture<>();
        CountDownLatch firstSubmitReachedHpds = new CountDownLatch(1);
        hpdsHoldsTheFirstSubmitUntilTheOtherRequestHasMoved(firstSubmitReachedHpds, secondRequest);
        FutureTask<QueryStatus> first = statusCall();
        FutureTask<QueryStatus> second = statusCall();

        start(first);
        await(firstSubmitReachedHpds);
        secondRequest.complete(start(second));
        QueryStatus firstAnswer = resultOf(first);
        QueryStatus secondAnswer = resultOf(second);

        assertThat(queriesHpdsReceived).as("HPDS received the query once").hasSize(1);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("LOST_RESULT");
        assertThat(firstAnswer.getStatus()).isEqualTo(PicSureStatus.PENDING);
        assertThat(secondAnswer.getStatus()).as("the second call reports the run the first one started").isEqualTo(PicSureStatus.PENDING);
        assertThat(secondAnswer.getPicsureResultId()).isEqualTo(ROW);
        assertThat(refusedStatusWrites).as("the second call wrote its status for the run the first one saved").isEmpty();
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @Test
    void twoConcurrentResultCallsOnALostResultSubmitItOnceAndBothAnswerNotReady() throws Exception {
        aliceHasARow(ONE_CONSENT, "AVAILABLE");
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, ONE_CONSENT));
        CompletableFuture<Thread> secondRequest = new CompletableFuture<>();
        CountDownLatch firstSubmitReachedHpds = new CountDownLatch(1);
        hpdsHoldsTheFirstSubmitUntilTheOtherRequestHasMoved(firstSubmitReachedHpds, secondRequest);
        FutureTask<ResponseEntity<byte[]>> first = resultCall();
        FutureTask<ResponseEntity<byte[]>> second = resultCall();

        start(first);
        await(firstSubmitReachedHpds);
        secondRequest.complete(start(second));

        assertAnsweredNotReady(first);
        assertAnsweredNotReady(second);
        assertThat(queriesHpdsReceived).as("HPDS received the query once").hasSize(1);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("LOST_RESULT");
        verify(hpds, never()).queryResult(any(), any());
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @ParameterizedTest(name = "the status call runs first: {0}")
    @ValueSource(booleans = {true, false})
    void aStatusCallRacingAnOwnedSubmitUnderChangingConsentsLeavesHpdsAndTheRowUnderTheLatestConsents(boolean statusCallRunsFirst)
        throws Exception {
        aliceHasARow(Set.of("phs009.c1"), "AVAILABLE");
        jobsHpdsHolds.put(ROW.toString(), PicSureStatus.AVAILABLE);
        AtomicInteger lookups = new AtomicInteger();
        CountDownLatch firstLookupAnswered = new CountDownLatch(1);
        when(psama.fetch(TOKEN)).thenAnswer(lookup -> {
            if (lookups.incrementAndGet() == 1) {
                firstLookupAnswered.countDown();
                return Map.of(CONSENT_PATH, BOTH_CONSENTS);
            }
            return Map.of(CONSENT_PATH, ONE_CONSENT);
        });
        CompletableFuture<Thread> secondRequest = new CompletableFuture<>();
        CountDownLatch latestConsentsReachedHpds = new CountDownLatch(1);
        CountDownLatch earlierConsentsSaved = new CountDownLatch(1);
        submitting = submit -> {
            boolean underEarlierConsents = BOTH_CONSENTS.equals(consentsSentWith(queryOf(submit)));
            if (underEarlierConsents) {
                awaitOpenedOrParked(latestConsentsReachedHpds, secondRequest);
            }
            QueryStatus accepted = receive(submit);
            if (!underEarlierConsents) {
                latestConsentsReachedHpds.countDown();
            }
            return accepted;
        };
        saving = save -> {
            boolean underEarlierConsents = BOTH_CONSENTS.equals(consentsSavedWith(save));
            if (!underEarlierConsents) {
                await(earlierConsentsSaved);
            }
            UUID id = store(save);
            if (underEarlierConsents) {
                earlierConsentsSaved.countDown();
            }
            return id;
        };
        FutureTask<QueryStatus> first = statusCallRunsFirst ? statusCall() : submit();
        FutureTask<QueryStatus> second = statusCallRunsFirst ? submit() : statusCall();

        start(first);
        await(firstLookupAnswered);
        secondRequest.complete(start(second));
        resultOf(first);
        resultOf(second);

        Set<String> lastSentToHpds = consentsSentWith(queriesHpdsReceived.get(queriesHpdsReceived.size() - 1));
        Set<String> lastSaved = consentsSavedWith(saves.get(saves.size() - 1));
        assertThat(lastSentToHpds).as("HPDS holds the result of the consents the row records").isEqualTo(lastSaved);
        assertThat(lastSaved).as("the row records the latest consents PSAMA answered with").isEqualTo(ONE_CONSENT);
        assertThat(queriesHpdsReceived).extracting(QueryServiceOwnedReadConcurrencyTest::consentsSentWith)
            .containsExactly(BOTH_CONSENTS, ONE_CONSENT);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("CONSENT_CHANGE", "CONSENT_CHANGE");
        assertThat(rows.get(ROW).consents()).isEqualTo(Map.of(CONSENT_PATH, List.of("phs001.c1")));
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    /**
     * Inside one instance of the service the row lock keeps a poller's read and its write together, so the row cannot move on between them.
     * A writer the lock does not cover still can: a second instance of the service, for one. This test plays that writer by saving a new
     * run straight into the operations-service stand-in while the poller is held between its read of the row and its status write.
     */
    @Test
    void aPollersLateStatusWriteForAnOlderRunDoesNotChangeTheRowAndDoesNotFailTheCaller() throws Exception {
        StoredQuery asThePollerReadIt = aliceHasARow(ONE_CONSENT, "PENDING");
        jobsHpdsHolds.put(ROW.toString(), PicSureStatus.AVAILABLE);
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, ONE_CONSENT));
        CountDownLatch pollerReadTheStatus = new CountDownLatch(1);
        CountDownLatch rowMovedOn = new CountDownLatch(1);
        reportingStatus = id -> {
            QueryStatus read = statusOfTheJob(id);
            pollerReadTheStatus.countDown();
            await(rowMovedOn);
            return read;
        };
        FutureTask<QueryStatus> poller = statusCall();

        start(poller);
        await(pollerReadTheStatus);
        store(
            new SaveQueryRequest(
                asThePollerReadIt.query(), ROW.toString(), "QUEUED", "3", null, ROW, ALICE, asThePollerReadIt.consents(), "RESUBMIT"
            )
        );
        StoredQuery afterTheNewerRun = rows.get(ROW);
        rowMovedOn.countDown();
        QueryStatus answer = resultOf(poller);

        assertThat(answer.getStatus()).as("the poller is answered with what it read").isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(refusedStatusWrites).extracting(UpdateQueryRequest::runId).containsExactly(asThePollerReadIt.runId());
        assertThat(rows.get(ROW)).as("the row still stands as the newer run left it").isSameAs(afterTheNewerRun);
        assertThat(rows.get(ROW).status()).isEqualTo("QUEUED");
        verify(operationsClient, never()).update(any(), any());
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @Test
    void aDownloadInProgressDoesNotKeepOtherRequestsForTheRowWaiting() throws Exception {
        aliceHasARow(ONE_CONSENT, "AVAILABLE");
        jobsHpdsHolds.put(ROW.toString(), PicSureStatus.AVAILABLE);
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, ONE_CONSENT));
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch downloadMayFinish = new CountDownLatch(1);
        when(hpds.queryResult(any(HpdsTarget.class), any())).thenAnswer(fetch -> {
            downloadStarted.countDown();
            await(downloadMayFinish);
            return ResponseEntity.ok(new byte[] {7});
        });
        FutureTask<ResponseEntity<byte[]>> download = resultCall();
        FutureTask<QueryStatus> poll = statusCall();

        start(download);
        await(downloadStarted);
        start(poll);
        QueryStatus polled = resultOf(poll);

        assertThat(download.isDone()).as("the download is still inside HPDS").isFalse();
        assertThat(polled.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        downloadMayFinish.countDown();
        assertThat(resultOf(download).getBody()).containsExactly(7);
        assertThat(queriesHpdsReceived).isEmpty();
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void anOwnedReadThatCannotGetItsRowInTimeIs503AndLooksUpSendsAndWritesNothing(Read read) throws Exception {
        aliceHasARow(ONE_CONSENT, "AVAILABLE");
        QueryRowLocks impatient = locksWaiting(0);
        QueryService impatientService = serviceWith(impatient);
        CountDownLatch rowIsHeld = new CountDownLatch(1);
        CountDownLatch holderMayLeave = new CountDownLatch(1);
        Callable<String> holdingTheRow = () -> impatient.withLock(ROW, () -> {
            rowIsHeld.countDown();
            await(holderMayLeave);
            return "held";
        });
        FutureTask<String> holder = new FutureTask<>(holdingTheRow);
        start(holder);
        await(rowIsHeld);

        assertThatThrownBy(() -> read.call(impatientService, "auth", ROW, TOKEN, alice()))
            .isInstanceOfSatisfying(PicsureException.class, busy -> {
                assertThat(busy.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(busy.getErrorType()).isEqualTo("service_unavailable");
                assertThat(busy.getMessage()).isEqualTo("Another request for this query is still in progress. Try again shortly.")
                    .doesNotContain(ROW.toString()).doesNotContain(ALICE.toString()).doesNotContain("phs001");
            });

        verifyNoInteractions(psama, hpds);
        verify(operationsClient, never()).save(any());
        verify(operationsClient, never()).update(any(), any());
        verify(operationsClient, never()).updateLatestRun(any(), any());
        holderMayLeave.countDown();
        assertThat(resultOf(holder)).isEqualTo("held");
    }
}
