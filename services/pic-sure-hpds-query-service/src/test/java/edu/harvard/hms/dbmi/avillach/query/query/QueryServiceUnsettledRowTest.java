package edu.harvard.hms.dbmi.avillach.query.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.SignedUrlResponse;
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
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;
import edu.harvard.hms.dbmi.avillach.query.hpds.ResourceWebClient;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;
import edu.harvard.hms.dbmi.avillach.query.query.QueryServiceTest.Read;

/**
 * Covers the ways an owned row and the job HPDS holds under its id can come apart without this service stopping, and what a request for
 * such a row is answered with. PSAMA, HPDS and operations-service are stand-ins that keep state, so each test plays a sequence of requests
 * against one row.
 *
 * <p>The HPDS stand-in answers as HPDS does. It keeps one job per id together with the consents the job was built under, and a result or a
 * signed URL it serves names those consents, so a test can tell which job a caller received. A query its validator rejects is answered with
 * 200 and a job in {@code ERROR} that is not kept, which leaves whatever the id held before in place. A status request for an id it holds
 * no job under is answered with 404. The operations-service stand-in keeps the rows it saves and can be told to fail its next saves.
 */
class QueryServiceUnsettledRowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "Bearer caller-token";
    private static final String CONSENT_PATH = "\\_consents\\";
    private static final Set<String> NARROW = Set.of("phs001.c1");
    private static final Set<String> WIDE = Set.of("phs001.c1", "phs001.c2");
    private static final UUID ALICE = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final UUID ROW = QueryIdentity.idFor(ALICE, cohort());
    private static final int EVERY_SAVE = Integer.MAX_VALUE;

    /** A job the HPDS stand-in holds: the consents its query was scoped with and how far it has run. */
    private record HpdsJob(Set<String> consents, PicSureStatus status) {
    }

    OperationsClient operationsClient = mock(OperationsClient.class);
    ResourceWebClient hpds = mock(ResourceWebClient.class);
    PsamaConsentClient psama = mock(PsamaConsentClient.class);
    QueryRowLocks rowLocks = new QueryRowLocks(new QueryRowLockProperties());
    List<Integer> rowsLockedWhenAMarkWasReadOrChanged = new ArrayList<>();
    UnsettledQueryRows unsettledRows = new UnsettledQueryRows() {
        @Override
        public boolean markUnsettled(UUID rowId) {
            rowsLockedWhenAMarkWasReadOrChanged.add(rowLocks.rowsInUse());
            return super.markUnsettled(rowId);
        }

        @Override
        public void markSettled(UUID rowId) {
            rowsLockedWhenAMarkWasReadOrChanged.add(rowLocks.rowsInUse());
            super.markSettled(rowId);
        }

        @Override
        public boolean isUnsettled(UUID rowId) {
            rowsLockedWhenAMarkWasReadOrChanged.add(rowLocks.rowsInUse());
            return super.isUnsettled(rowId);
        }
    };
    QueryService service = service();

    Map<UUID, StoredQuery> rows = new HashMap<>();
    List<SaveQueryRequest> saveAttempts = new ArrayList<>();
    List<SaveQueryRequest> saves = new ArrayList<>();
    int savesToFail;
    PicsureException saveFailure = new PicsureException(HttpStatus.GATEWAY_TIMEOUT, "gateway_timeout", "operations-service save timed out");
    Map<String, HpdsJob> jobsHpdsHolds = new HashMap<>();
    List<Set<String>> consentsHpdsReceived = new ArrayList<>();
    PicSureStatus statusOfANewJob = PicSureStatus.PENDING;
    /** What the HPDS stand-in does with a submit. A test replaces it to play a rejection, a refusal or a lost answer. */
    Function<InvocationOnMock, QueryStatus> submitting = this::accept;
    ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private QueryService service() {
        HpdsProperties hpdsProperties = new HpdsProperties();
        hpdsProperties.setAuthUrl("http://hpds/PIC-SURE");
        return new QueryService(
            operationsClient, hpds, new HpdsBackendSelector(hpdsProperties),
            new ConsentAuthorizationService(psama, new ConsentFilterBuilder(), true), new SampleRules(new SampleProperties()), rowLocks,
            unsettledRows
        );
    }

    @BeforeEach
    void standInsKeepRowsAndJobs() {
        when(operationsClient.find(any())).thenAnswer(read -> Optional.ofNullable(rows.get(read.<UUID>getArgument(0))));
        when(operationsClient.get(any())).thenAnswer(read -> Optional.ofNullable(rows.get(read.<UUID>getArgument(0))).orElseThrow());
        when(operationsClient.save(any())).thenAnswer(write -> store(write.getArgument(0)));
        when(operationsClient.updateLatestRun(any(), any())).thenReturn(true);
        when(hpds.query(any(HpdsTarget.class), any(), any(UUID.class))).thenAnswer(submit -> {
            consentsHpdsReceived.add(consentsOf(submit));
            return submitting.apply(submit);
        });
        when(hpds.queryStatus(any(HpdsTarget.class), any())).thenAnswer(ask -> statusOfTheJob(ask.getArgument(1)));
        when(hpds.queryResult(any(HpdsTarget.class), any()))
            .thenAnswer(fetch -> ResponseEntity.ok(builtUnder(finishedJob(fetch.getArgument(1))).getBytes(StandardCharsets.UTF_8)));
        when(hpds.queryResultSignedUrl(any(HpdsTarget.class), any()))
            .thenAnswer(fetch -> new SignedUrlResponse(builtUnder(finishedJob(fetch.getArgument(1)))));
        logged.start();
        ((Logger) LoggerFactory.getLogger(QueryService.class)).addAppender(logged);
    }

    @AfterEach
    void stopCapturingLogs() {
        ((Logger) LoggerFactory.getLogger(QueryService.class)).detachAppender(logged);
    }

    private UUID store(SaveQueryRequest save) {
        saveAttempts.add(save);
        if (savesToFail > 0) {
            savesToFail--;
            throw saveFailure;
        }
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

    /** HPDS takes the query: it keeps a job under the id, built under the consents the query carries, and answers with its status. */
    private QueryStatus accept(InvocationOnMock submit) {
        String id = submit.getArgument(2).toString();
        jobsHpdsHolds.put(id, new HpdsJob(consentsOf(submit), statusOfANewJob));
        return job(id, statusOfANewJob);
    }

    /** HPDS's validator rejects the query: the answer is 200 with a job in {@code ERROR}, and the id keeps whatever it held. */
    private QueryStatus rejectAtValidation(InvocationOnMock submit) {
        return job(submit.getArgument(2).toString(), PicSureStatus.ERROR);
    }

    /** HPDS takes the query and builds the job, and its answer never arrives. */
    private QueryStatus acceptAndLoseTheAnswer(InvocationOnMock submit) {
        accept(submit);
        throw new HpdsCommunicationException("HPDS call failed: http://hpds/PIC-SURE/v3/query");
    }

    /** HPDS answers 400 before its handler runs, as it does for a body it cannot read. Nothing is kept. */
    private QueryStatus refuse(InvocationOnMock submit) {
        throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Bad Request");
    }

    /** HPDS has no encryption key loaded: the answer is 200 with no status and no job. Nothing is kept. */
    private QueryStatus answerAsLocked(InvocationOnMock submit) {
        QueryStatus locked = new QueryStatus();
        locked.setResourceStatus("Resource is locked.");
        return locked;
    }

    private QueryStatus answerWithNoBody(InvocationOnMock submit) {
        return null;
    }

    private QueryStatus answerUnderAnotherId(InvocationOnMock submit) {
        return job("0d9f6c1e-7a52-4b7e-8d0a-3f1b2c4d5e6f", PicSureStatus.PENDING);
    }

    private QueryStatus statusOfTheJob(String id) {
        HpdsJob held = jobsHpdsHolds.get(id);
        if (held == null) {
            throw new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Not Found");
        }
        return job(id, held.status());
    }

    private HpdsJob finishedJob(String id) {
        HpdsJob held = jobsHpdsHolds.get(id);
        if (held == null) {
            throw new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Not Found");
        }
        if (held.status() != PicSureStatus.AVAILABLE) {
            throw new PicsureException(HttpStatus.CONFLICT, "conflict", "Status : RUNNING");
        }
        return held;
    }

    private static String builtUnder(HpdsJob finished) {
        return "built under " + new TreeSet<>(finished.consents());
    }

    private static QueryStatus job(String id, PicSureStatus status) {
        QueryStatus job = new QueryStatus();
        job.setResourceResultId(id);
        job.setStatus(status);
        return job;
    }

    private static Set<String> consentsOf(InvocationOnMock submit) {
        Query query = (Query) submit.<QueryRequest>getArgument(1).getQuery();
        return query.authorizationFilters().stream().filter(filter -> CONSENT_PATH.equals(filter.conceptPath())).findFirst().orElseThrow()
            .values();
    }

    private static GatewayUser alice() {
        return new GatewayUser(ALICE.toString(), null, null, null, Set.of());
    }

    private static Query cohort() {
        return new Query(List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, null, null);
    }

    private static Map<String, List<String>> recorded(Set<String> consents) {
        return Map.of(CONSENT_PATH, consents.stream().sorted().toList());
    }

    private void callerHolds(Set<String> consents) {
        when(psama.fetch(TOKEN)).thenReturn(Map.of(CONSENT_PATH, consents));
    }

    /** Stores Alice's row as an owned submit under the given consents leaves it, and the finished job HPDS built for it. */
    private void aliceHasAFinishedQueryUnder(Set<String> consents) {
        aliceHasARow(consents, "AVAILABLE");
        jobsHpdsHolds.put(ROW.toString(), new HpdsJob(consents, PicSureStatus.AVAILABLE));
    }

    private void aliceHasARow(Set<String> consents, String status) {
        try {
            String body = MAPPER.writeValueAsString(new GeneralQueryRequest().setQuery(cohort().setPicsureId(ROW)));
            rows.put(
                ROW, new StoredQuery(ROW, body, ROW.toString(), status, "3", null, 1L, null, ALICE, recorded(consents), UUID.randomUUID())
            );
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException(unwritable);
        }
    }

    private Object call(Read read) {
        return read.call(service, "auth", ROW, TOKEN, alice());
    }

    private QueryStatus submit() {
        return service.query("auth", new GeneralQueryRequest().setQuery(cohort()), TOKEN, alice());
    }

    private String resultServed() {
        return new String(service.queryResult("auth", ROW, TOKEN, alice()).getBody(), StandardCharsets.UTF_8);
    }

    private static void assertNotReady(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PicsureException.class, notReady -> {
            assertThat(notReady.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(notReady.getErrorType()).isEqualTo("result_not_ready");
        });
    }

    private static void assertFailedResult(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PicsureException.class, failed -> {
            assertThat(failed.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(failed.getErrorType()).isEqualTo("bad_request");
            assertThat(failed.getMessage()).isEqualTo("Status : ERROR");
        });
    }

    /** Plays a read that runs the query again: a status call returns the new run, a result or signed-url call answers not ready. */
    private void assertRunAgainBy(Read read) {
        if (read == Read.STATUS) {
            assertThat(((QueryStatus) call(read)).getStatus()).isEqualTo(statusOfANewJob);
        } else {
            assertNotReady(() -> call(read));
        }
    }

    private void verifyNothingWasServed() {
        verify(hpds, never()).queryResult(any(), any());
        verify(hpds, never()).queryResultSignedUrl(any(), any());
    }

    /**
     * Leaves Alice's row recording the narrow consents while HPDS holds a finished job built under the wide ones: her consents grow, a
     * status call runs the query again, HPDS takes it, and every attempt to save the row fails.
     */
    private void aRerunUnderWideConsentsThatHpdsTookAndTheRowNeverRecorded() {
        aliceHasAFinishedQueryUnder(NARROW);
        statusOfANewJob = PicSureStatus.AVAILABLE;
        callerHolds(WIDE);
        savesToFail = EVERY_SAVE;
        assertThatThrownBy(() -> call(Read.STATUS)).isSameAs(saveFailure);
        savesToFail = 0;
        assertThat(jobsHpdsHolds.get(ROW.toString()).consents()).isEqualTo(WIDE);
        assertThat(rows.get(ROW).consents()).isEqualTo(recorded(NARROW));
    }

    @Test
    void aQueryHpdsRejectsAtValidationIsSubmittedAndSavedOnceHoweverOftenItIsRead() {
        callerHolds(NARROW);
        submitting = this::rejectAtValidation;

        QueryStatus submitted = submit();
        List<PicSureStatus> polled = new ArrayList<>();
        for (int poll = 0; poll < 5; poll++) {
            polled.add(service.queryStatus("auth", ROW, TOKEN, alice()).getStatus());
        }

        assertThat(submitted.getStatus()).isEqualTo(PicSureStatus.ERROR);
        assertThat(polled).containsOnly(PicSureStatus.ERROR).hasSize(5);
        assertThat(consentsHpdsReceived).as("HPDS received the query once, whatever the number of polls").hasSize(1);
        assertFailedResult(() -> service.queryResult("auth", ROW, TOKEN, alice()));
        assertThat(consentsHpdsReceived).as("HPDS received the query once").hasSize(1);
        assertThat(saveAttempts).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT");
        assertThat(saveAttempts).extracting(SaveQueryRequest::status).containsExactly("ERROR");
        verify(operationsClient, never()).update(any(), any());
        verify(operationsClient, never()).updateLatestRun(any(), any());
        verifyNothingWasServed();
    }

    @Test
    void aStatusPollOfARejectedQueryAnswersFromTheRowWithItsIdsAndNamesNoConsentOrUser() throws Exception {
        callerHolds(NARROW);
        submitting = this::rejectAtValidation;
        submit();

        QueryStatus polled = service.queryStatus("auth", ROW, TOKEN, alice());

        assertThat(polled.getStatus()).isEqualTo(PicSureStatus.ERROR);
        assertThat(polled.getPicsureResultId()).isEqualTo(ROW);
        assertThat(polled.getResourceResultId()).isEqualTo(ROW.toString());
        assertThat(MAPPER.writeValueAsString(polled)).doesNotContain("phs001").doesNotContain(ALICE.toString());
    }

    @Test
    void anExplicitSubmitRunsARejectedQueryAgainAsAResubmit() {
        callerHolds(NARROW);
        submitting = this::rejectAtValidation;
        submit();
        service.queryStatus("auth", ROW, TOKEN, alice());
        submitting = this::accept;

        QueryStatus resubmitted = submit();

        assertThat(resubmitted.getStatus()).isEqualTo(PicSureStatus.PENDING);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT", "RESUBMIT");
        assertThat(consentsHpdsReceived).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = Read.class, names = {"RESULT", "SIGNED_URL"})
    void aRerunHpdsRejectsLeavesTheOlderJobUnderTheIdAndThatJobIsNeverServed(Read read) {
        aliceHasAFinishedQueryUnder(WIDE);
        callerHolds(NARROW);
        submitting = this::rejectAtValidation;

        assertThatThrownBy(() -> call(read)).isInstanceOf(PicsureException.class);
        assertThat(jobsHpdsHolds.get(ROW.toString())).as("HPDS still holds the job of the wide consents")
            .isEqualTo(new HpdsJob(WIDE, PicSureStatus.AVAILABLE));

        assertFailedResult(() -> call(read));
        assertThat(service.queryStatus("auth", ROW, TOKEN, alice()).getStatus()).isEqualTo(PicSureStatus.ERROR);
        verifyNothingWasServed();
        assertThat(consentsHpdsReceived).as("the rejected query is not sent again by a read").containsExactly(NARROW);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("CONSENT_CHANGE");
        assertThat(saves).extracting(SaveQueryRequest::status).containsExactly("ERROR");
        assertThat(rows.get(ROW).consents()).isEqualTo(recorded(NARROW));
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void aJobTheRowNeverRecordedIsNotServedOnceTheCallersConsentsEqualTheRecordedOnesAgain(Read read) {
        aRerunUnderWideConsentsThatHpdsTookAndTheRowNeverRecorded();
        callerHolds(NARROW);

        assertRunAgainBy(read);

        verifyNothingWasServed();
        assertThat(consentsHpdsReceived).containsExactly(WIDE, NARROW);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("LOST_RESULT");
        assertThat(saves.get(0).consents()).isEqualTo(recorded(NARROW));
        assertThat(jobsHpdsHolds.get(ROW.toString()).consents()).isEqualTo(NARROW);
        assertThat(rows.get(ROW).consents()).isEqualTo(recorded(NARROW));
    }

    @Test
    void aSuccessfulRerunSettlesTheRowAndTheNextReadServesTheJobOfThatRerun() {
        aRerunUnderWideConsentsThatHpdsTookAndTheRowNeverRecorded();
        callerHolds(NARROW);
        assertNotReady(() -> call(Read.RESULT));

        String served = resultServed();

        assertThat(served).isEqualTo("built under [phs001.c1]");
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
        assertThat(consentsHpdsReceived).as("the settled row is not run again").containsExactly(WIDE, NARROW);
        assertThat(saves).hasSize(1);
    }

    @Test
    void anUnsettledRowReadUnderOtherConsentsThanItRecordsIsRunAgainAsAConsentChange() {
        aRerunUnderWideConsentsThatHpdsTookAndTheRowNeverRecorded();

        assertNotReady(() -> call(Read.RESULT));

        verifyNothingWasServed();
        assertThat(consentsHpdsReceived).containsExactly(WIDE, WIDE);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("CONSENT_CHANGE");
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
    }

    @Test
    void anOwnedSubmitOnAnUnsettledRowWithEqualConsentsRunsTheQueryAgainInsteadOfReusingTheRow() {
        aRerunUnderWideConsentsThatHpdsTookAndTheRowNeverRecorded();
        statusOfANewJob = PicSureStatus.PENDING;
        callerHolds(NARROW);

        QueryStatus answer = submit();

        assertThat(answer.getStatus()).isEqualTo(PicSureStatus.PENDING);
        assertThat(consentsHpdsReceived).containsExactly(WIDE, NARROW);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("LOST_RESULT");
        assertThat(saves.get(0).consents()).isEqualTo(recorded(NARROW));
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
    }

    @Test
    void anOwnedSubmitOnASettledRowWithEqualConsentsStillReusesTheRow() {
        aliceHasAFinishedQueryUnder(NARROW);
        callerHolds(NARROW);

        QueryStatus answer = submit();

        assertThat(answer.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(consentsHpdsReceived).isEmpty();
        assertThat(saveAttempts).isEmpty();
    }

    @Test
    void anOwnedSubmitOnAnUnsettledRowThatFailedKeepsTheResubmitTrigger() {
        aliceHasARow(NARROW, "ERROR");
        callerHolds(NARROW);
        savesToFail = EVERY_SAVE;
        assertThatThrownBy(this::submit).isSameAs(saveFailure);
        savesToFail = 0;

        submit();

        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("RESUBMIT");
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
    }

    @Test
    void aSaveThatSucceedsOnTheSecondAttemptLeavesTheRowSettled() {
        aliceHasAFinishedQueryUnder(NARROW);
        statusOfANewJob = PicSureStatus.AVAILABLE;
        callerHolds(WIDE);
        savesToFail = 1;

        QueryStatus rerun = (QueryStatus) call(Read.STATUS);

        assertThat(rerun.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(saveAttempts).hasSize(2);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("CONSENT_CHANGE");
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
        assertThat(resultServed()).isEqualTo("built under [phs001.c1, phs001.c2]");
        assertThat(consentsHpdsReceived).as("HPDS received the query once").containsExactly(WIDE);
    }

    @Test
    void aSaveIsAttemptedThreeTimesAndThenTheRowStaysUnsettled() {
        aliceHasAFinishedQueryUnder(NARROW);
        callerHolds(WIDE);
        savesToFail = EVERY_SAVE;

        assertThatThrownBy(() -> call(Read.STATUS)).isSameAs(saveFailure);

        assertThat(saveAttempts).hasSize(3);
        assertThat(saves).isEmpty();
        assertThat(consentsHpdsReceived).as("the retries save, they do not submit").containsExactly(WIDE);
        assertThat(unsettledRows.isUnsettled(ROW)).isTrue();
    }

    @Test
    void aSaveRefusedForARowOfAnotherOwnerIsNotTriedAgainAndLeavesTheRowUnsettled() {
        aliceHasAFinishedQueryUnder(NARROW);
        callerHolds(WIDE);
        saveFailure = OperationsClient.storedUnderAnotherOwner();
        savesToFail = EVERY_SAVE;

        assertThatThrownBy(() -> call(Read.STATUS)).isSameAs(saveFailure);

        assertThat(saveAttempts).hasSize(1);
        assertThat(unsettledRows.isUnsettled(ROW)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Read.class)
    void anHpdsSubmitWhoseAnswerIsLostLeavesTheRowUnsettledAndTheNextReadRunsItAgain(Read read) {
        aliceHasAFinishedQueryUnder(NARROW);
        statusOfANewJob = PicSureStatus.AVAILABLE;
        callerHolds(WIDE);
        submitting = this::acceptAndLoseTheAnswer;
        assertThatThrownBy(() -> call(read)).isInstanceOf(HpdsCommunicationException.class);
        assertThat(jobsHpdsHolds.get(ROW.toString()).consents()).isEqualTo(WIDE);
        assertThat(saveAttempts).isEmpty();
        submitting = this::accept;
        callerHolds(NARROW);

        assertRunAgainBy(read);

        verifyNothingWasServed();
        assertThat(consentsHpdsReceived).containsExactly(WIDE, NARROW);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("LOST_RESULT");
        assertThat(jobsHpdsHolds.get(ROW.toString()).consents()).isEqualTo(NARROW);
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
    }

    /** The answers of HPDS to a submit that show it kept no job. */
    enum NothingKept {
        A_4XX_REFUSAL, A_LOCKED_ANSWER
    }

    private void hpdsKeepsNothing(NothingKept answer) {
        submitting = answer == NothingKept.A_4XX_REFUSAL ? this::refuse : this::answerAsLocked;
    }

    @ParameterizedTest
    @EnumSource(NothingKept.class)
    void aSubmitHpdsKeptNothingOfLeavesASettledRowSettled(NothingKept answer) {
        aliceHasAFinishedQueryUnder(NARROW);
        callerHolds(WIDE);
        hpdsKeepsNothing(answer);
        assertThatThrownBy(() -> call(Read.RESULT)).isInstanceOf(RuntimeException.class);
        callerHolds(NARROW);

        String served = resultServed();

        assertThat(served).as("the row still describes the job HPDS holds").isEqualTo("built under [phs001.c1]");
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
        assertThat(consentsHpdsReceived).containsExactly(WIDE);
        assertThat(saveAttempts).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(NothingKept.class)
    void aSubmitHpdsKeptNothingOfLeavesAnUnsettledRowUnsettled(NothingKept answer) {
        aRerunUnderWideConsentsThatHpdsTookAndTheRowNeverRecorded();
        callerHolds(NARROW);
        hpdsKeepsNothing(answer);
        assertThatThrownBy(() -> call(Read.RESULT)).isInstanceOf(RuntimeException.class);
        assertThat(unsettledRows.isUnsettled(ROW)).as("HPDS still holds the job the row never recorded").isTrue();
        submitting = this::accept;

        assertNotReady(() -> call(Read.RESULT));

        verifyNothingWasServed();
        assertThat(consentsHpdsReceived).containsExactly(WIDE, NARROW, NARROW);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("LOST_RESULT");
    }

    /** The answers of HPDS to a submit that do not show whether it kept a job under the row id. */
    enum OutcomeUnknown {
        NO_BODY, ANOTHER_ID, A_FAILURE_BEFORE_THE_JOB_WAS_KEPT
    }

    @ParameterizedTest
    @EnumSource(OutcomeUnknown.class)
    void aSubmitWhoseOutcomeIsUnknownLeavesTheRowUnsettledAndTheNextReadRunsItAgain(OutcomeUnknown answer) {
        aliceHasAFinishedQueryUnder(NARROW);
        callerHolds(WIDE);
        submitting = switch (answer) {
            case NO_BODY -> this::answerWithNoBody;
            case ANOTHER_ID -> this::answerUnderAnotherId;
            case A_FAILURE_BEFORE_THE_JOB_WAS_KEPT -> submit -> {
                throw new HpdsCommunicationException("HPDS call failed: http://hpds/PIC-SURE/v3/query");
            };
        };
        assertThatThrownBy(() -> call(Read.RESULT)).isInstanceOf(HpdsCommunicationException.class);
        assertThat(unsettledRows.isUnsettled(ROW)).isTrue();
        submitting = this::accept;
        callerHolds(NARROW);

        assertNotReady(() -> call(Read.RESULT));

        verifyNothingWasServed();
        assertThat(consentsHpdsReceived).containsExactly(WIDE, NARROW);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("LOST_RESULT");
        assertThat(unsettledRows.isUnsettled(ROW)).isFalse();
    }

    @Test
    void theMarkOfARowIsReadAndChangedOnlyInsideTheLockOfThatRow() {
        callerHolds(NARROW);
        submit();
        call(Read.STATUS);
        callerHolds(WIDE);
        savesToFail = EVERY_SAVE;
        assertThatThrownBy(() -> call(Read.RESULT)).isSameAs(saveFailure);
        savesToFail = 0;
        callerHolds(NARROW);
        assertNotReady(() -> call(Read.SIGNED_URL));
        submit();

        assertThat(rowsLockedWhenAMarkWasReadOrChanged).as("the rows locked at each read and change of a mark").isNotEmpty()
            .containsOnly(1);
        assertThat(saves).extracting(SaveQueryRequest::trigger).containsExactly("FIRST_SUBMIT", "LOST_RESULT");
        assertThat(rowLocks.rowsInUse()).isZero();
    }

    @Test
    void nothingLoggedWhileARowIsUnsettledAndRunAgainNamesAConsentOrTheUser() {
        aRerunUnderWideConsentsThatHpdsTookAndTheRowNeverRecorded();
        callerHolds(NARROW);
        assertNotReady(() -> call(Read.RESULT));

        assertThat(logged.list).isNotEmpty();
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .allSatisfy(line -> assertThat(line).doesNotContain("phs001").doesNotContain("_consents").doesNotContain(ALICE.toString()));
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .anySatisfy(line -> assertThat(line).contains(ROW.toString()));
    }
}
