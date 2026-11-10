package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;

import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;
import edu.harvard.hms.dbmi.avillach.query.config.QueryRowLockProperties;
import edu.harvard.hms.dbmi.avillach.query.config.SampleProperties;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentAuthorizationService;
import edu.harvard.hms.dbmi.avillach.query.consent.ConsentFilterBuilder;
import edu.harvard.hms.dbmi.avillach.query.consent.PsamaConsentClient;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector.HpdsTarget;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Covers owned submits that arrive at the same time. PSAMA, HPDS and operations-service are stand-ins: operations-service keeps the rows it
 * is asked to save and answers reads from them, and HPDS records the queries it receives in the order they arrive.
 *
 * <p>Each test holds one request inside a stand-in until the other request has gone as far as it can: until it has made the same call, or
 * until its thread is parked waiting for the row lock. Requests are lined up with latches and that check, never by sleeping for a fixed
 * time, so each test reaches the same interleaving on every run and would reach the unsafe one if the submit took no lock.
 */
class QueryServiceOwnedSubmitConcurrencyTest {

    private static final long PATIENCE_SECONDS = 10;
    private static final String TOKEN = "Bearer caller-token";
    private static final String CONSENT_PATH = "\\_consents\\";
    private static final Set<String> BOTH_CONSENTS = Set.of("phs001.c1", "phs001.c2");
    private static final Set<String> ONE_CONSENT = Set.of("phs001.c1");
    private static final UUID ALICE = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID BOB = UUID.fromString("0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77");

    OperationsClient operationsClient = mock(OperationsClient.class);
    ResourceWebClient hpds = mock(ResourceWebClient.class);
    PsamaConsentClient psama = mock(PsamaConsentClient.class);
    QueryRowLocks rowLocks = locksWaiting(30);
    QueryService service = serviceWith(rowLocks);

    Map<UUID, StoredQuery> rows = new ConcurrentHashMap<>();
    List<SaveQueryRequest> saves = Collections.synchronizedList(new ArrayList<>());
    List<Query> queriesHpdsReceived = Collections.synchronizedList(new ArrayList<>());
    /** What the operations-service stand-in does with a save. A test replaces it to hold one save back until another has happened. */
    volatile Function<SaveQueryRequest, UUID> saving = this::store;

    static QueryRowLocks locksWaiting(int seconds) {
        QueryRowLockProperties properties = new QueryRowLockProperties();
        properties.setWaitSec(seconds);
        return new QueryRowLocks(properties);
    }

    private QueryService serviceWith(QueryRowLocks locks) {
        HpdsProperties hpdsProperties = new HpdsProperties();
        hpdsProperties.setAuthUrl("http://hpds/PIC-SURE");
        return new QueryService(
            operationsClient, hpds, new HpdsBackendSelector(hpdsProperties),
            new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), true), new SampleRules(new SampleProperties()), locks
        );
    }

    @BeforeEach
    void operationsServiceKeepsWhatItIsAskedToSave() {
        when(operationsClient.find(any())).thenAnswer(read -> Optional.ofNullable(rows.get(read.<UUID>getArgument(0))));
        when(operationsClient.save(any())).thenAnswer(write -> saving.apply(write.getArgument(0)));
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

    private QueryStatus receive(InvocationOnMock submit) {
        queriesHpdsReceived.add(queryOf(submit));
        QueryStatus accepted = new QueryStatus();
        accepted.setResourceResultId(submit.getArgument(2).toString());
        accepted.setStatus(PicSureStatus.PENDING);
        return accepted;
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

    private static GatewayUser user(UUID uuid) {
        return new GatewayUser(uuid.toString(), null, null, null, Set.of());
    }

    private static Query cohort() {
        return new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, null, null);
    }

    private FutureTask<QueryStatus> submitAs(UUID caller) {
        return new FutureTask<>(() -> service.query("auth", new GeneralQueryRequest().setQuery(cohort()), TOKEN, user(caller)));
    }

    static Thread start(FutureTask<?> task) {
        Thread thread = new Thread(task);
        thread.start();
        return thread;
    }

    static <T> T resultOf(FutureTask<T> task) throws Exception {
        return task.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).as("the latch opened in time").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * Holds the calling request until the other request has gone as far as it can: until it has opened the latch, which it does when it
     * makes the same call, or until its thread is parked in a timed wait, which is where a request waiting for the row lock stands.
     */
    static void awaitOpenedOrParked(CountDownLatch openedByTheOtherRequest, CompletableFuture<Thread> otherRequest) {
        Thread other = otherRequest.orTimeout(PATIENCE_SECONDS, TimeUnit.SECONDS).join();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (openedByTheOtherRequest.getCount() > 0 && other.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) {
                fail("The other request neither made the call nor waited for the lock. Its state is " + other.getState());
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void aConsentChangeBetweenTwoConcurrentSubmitsLeavesHpdsAndTheRowUnderTheLatestConsents() throws Exception {
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
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenAnswer(submit -> {
            boolean underEarlierConsents = BOTH_CONSENTS.equals(consentsSentWith(queryOf(submit)));
            if (underEarlierConsents) {
                awaitOpenedOrParked(latestConsentsReachedHpds, secondRequest);
            }
            QueryStatus accepted = receive(submit);
            if (!underEarlierConsents) {
                latestConsentsReachedHpds.countDown();
            }
            return accepted;
        });
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
        FutureTask<QueryStatus> first = submitAs(ALICE);
        FutureTask<QueryStatus> second = submitAs(ALICE);

        start(first);
        await(firstLookupAnswered);
        secondRequest.complete(start(second));
        resultOf(first);
        resultOf(second);

        Set<String> lastSentToHpds = consentsSentWith(queriesHpdsReceived.get(queriesHpdsReceived.size() - 1));
        Set<String> lastSaved = consentsSavedWith(saves.get(saves.size() - 1));
        assertThat(lastSentToHpds).as("HPDS holds the result of the consents the row records").isEqualTo(lastSaved);
        assertThat(lastSaved).as("the row records the latest consents PSAMA answered with").isEqualTo(ONE_CONSENT);
        assertThat(queriesHpdsReceived).extracting(QueryServiceOwnedSubmitConcurrencyTest::consentsSentWith)
            .containsExactly(BOTH_CONSENTS, ONE_CONSENT);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT", "CONSENT_CHANGE");
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @Test
    void twoConcurrentFirstSubmitsOfOneQueryRunItOnceAndTheSecondIsARepeat() throws Exception {
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, ONE_CONSENT));
        AtomicInteger submits = new AtomicInteger();
        CompletableFuture<Thread> secondRequest = new CompletableFuture<>();
        CountDownLatch firstSubmitReachedHpds = new CountDownLatch(1);
        CountDownLatch secondSubmitReachedHpds = new CountDownLatch(1);
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenAnswer(submit -> {
            if (submits.incrementAndGet() == 1) {
                firstSubmitReachedHpds.countDown();
                awaitOpenedOrParked(secondSubmitReachedHpds, secondRequest);
            } else {
                secondSubmitReachedHpds.countDown();
            }
            return receive(submit);
        });
        FutureTask<QueryStatus> first = submitAs(ALICE);
        FutureTask<QueryStatus> second = submitAs(ALICE);

        start(first);
        await(firstSubmitReachedHpds);
        secondRequest.complete(start(second));
        QueryStatus firstAnswer = resultOf(first);
        QueryStatus secondAnswer = resultOf(second);

        assertThat(queriesHpdsReceived).as("HPDS received the query once").hasSize(1);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT");
        assertThat(firstAnswer.getPicsureResultId()).isEqualTo(id);
        assertThat(secondAnswer.getPicsureResultId()).isEqualTo(id);
        assertThat(secondAnswer.getResourceResultId()).isEqualTo(id.toString());
        assertThat(secondAnswer.getStatus()).isEqualTo(PicSureStatus.PENDING);
        verify(psama, times(2)).fetch(TOKEN);
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @Test
    void differentUsersSubmittingTheSameQueryDoNotWaitForEachOther() throws Exception {
        UUID aliceRow = QueryIdentity.idFor(ALICE, cohort());
        UUID bobRow = QueryIdentity.idFor(BOB, cohort());
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, ONE_CONSENT));
        CountDownLatch aliceIsInsideHpds = new CountDownLatch(1);
        CountDownLatch bobIsAnswered = new CountDownLatch(1);
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenAnswer(submit -> {
            if (aliceRow.equals(submit.getArgument(2))) {
                aliceIsInsideHpds.countDown();
                await(bobIsAnswered);
            }
            return receive(submit);
        });
        FutureTask<QueryStatus> alice = submitAs(ALICE);
        FutureTask<QueryStatus> bob = submitAs(BOB);

        start(alice);
        await(aliceIsInsideHpds);
        start(bob);
        QueryStatus bobAnswer = resultOf(bob);

        assertThat(alice.isDone()).as("Alice's submit is still inside HPDS, holding the lock of her row").isFalse();
        assertThat(bobAnswer.getPicsureResultId()).isEqualTo(bobRow);
        bobIsAnswered.countDown();
        assertThat(resultOf(alice).getPicsureResultId()).isEqualTo(aliceRow);
        assertThat(saves).extracting(SaveQueryRequest::userUuid).containsExactly(BOB, ALICE);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT", "FIRST_SUBMIT");
    }

    @Test
    void anOwnedSubmitThatCannotGetItsRowInTimeIs503AndLooksUpSendsAndSavesNothing() throws Exception {
        QueryRowLocks impatient = locksWaiting(0);
        QueryService impatientService = serviceWith(impatient);
        UUID id = QueryIdentity.idFor(ALICE, cohort());
        CountDownLatch rowIsHeld = new CountDownLatch(1);
        CountDownLatch holderMayLeave = new CountDownLatch(1);
        FutureTask<String> holder = new FutureTask<>(() -> impatient.withLock(id, () -> {
            rowIsHeld.countDown();
            await(holderMayLeave);
            return "held";
        }));
        start(holder);
        await(rowIsHeld);

        assertThatThrownBy(() -> impatientService.query("auth", new GeneralQueryRequest().setQuery(cohort()), TOKEN, user(ALICE)))
            .isInstanceOfSatisfying(PicsureException.class, busy -> {
                assertThat(busy.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(busy.getErrorType()).isEqualTo("service_unavailable");
                assertThat(busy.getMessage()).isEqualTo("Another request for this query is still in progress. Try again shortly.")
                    .doesNotContain(id.toString()).doesNotContain(ALICE.toString()).doesNotContain("phs001");
            });

        verifyNoInteractions(psama, hpds);
        verify(operationsClient, never()).find(any());
        verify(operationsClient, never()).save(any());
        holderMayLeave.countDown();
        assertThat(resultOf(holder)).isEqualTo("held");
    }
}
