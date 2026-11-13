package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.operations.query.ResultTypeBackfill.Ending;
import edu.harvard.hms.dbmi.avillach.operations.query.ResultTypeBackfill.Summary;

/**
 * Runs the result type pass on the calling thread against a real repository on H2, with no test transaction around it, so every row the
 * pass writes is committed by the statement that wrote it. The tests of the thread the pass runs on after startup use a mocked repository
 * and wait on latches, never on elapsed time.
 *
 * <p>The class has its own H2 database, because a pass reads every row without a result type and must not meet the rows of another test
 * class. Every test deletes the rows it committed. Every test also has a time limit, so a pass that never reaches the end of the table
 * fails its test instead of holding the build.
 */
@DataJpaTest(showSql = false, properties = "spring.datasource.url=jdbc:h2:mem:ops-result-type-backfill;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(QueryPersistenceService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Timeout(ResultTypeBackfillTest.WAIT_SECONDS)
class ResultTypeBackfillTest {

    private static final String V3_SAMPLE_PFB = "{\"query\":{\"select\":[],\"expectedResultType\":\"DATAFRAME_PFB\",\"sample\":true}}";
    private static final String V3_COUNT = "{\"query\":{\"select\":[],\"expectedResultType\":\"COUNT\"}}";
    private static final String V3_WITHOUT_A_TYPE = "{\"query\":{\"select\":[]}}";
    private static final String LEGACY_DATAFRAME =
        "{\"categoryFilters\":{\"sex\":[\"Female\"]},\"fields\":[\"age\"],\"expectedResultType\":\"DATAFRAME\"}";
    private static final String UNTRANSLATABLE_LEGACY = "{\"query\":{\"variantInfoFilters\":["
        + "{\"categoryVariantInfoFilters\":{\"Gene\":[\"BRCA1\"]}},{\"categoryVariantInfoFilters\":{\"Gene\":[\"BRCA2\"]}}]}}";
    private static final String UPDATE_METHOD = "setResultTypeWhereAbsent";
    static final long WAIT_SECONDS = 30;

    @Autowired
    private QueryRepository repo;

    @Autowired
    private QueryPersistenceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void deleteCommittedRows() {
        repo.deleteAll();
    }

    @Test
    void fillsTheTypeOfV3AndTranslatedRows() {
        UUID v3 = stored(1, V3_SAMPLE_PFB, "3");
        UUID bareLegacy = stored(2, LEGACY_DATAFRAME, "2");
        UUID wrappedLegacyWithoutAVersion = stored(3, "{\"query\":" + LEGACY_DATAFRAME + ",\"resourceUUID\":\"resource-id\"}", null);
        UUID legacyThatNamesNoType = stored(4, "{\"categoryFilters\":{\"sex\":[\"Female\"]}}", "2");
        Query before = repo.findById(v3).orElseThrow();

        Summary summary = backfill(repo, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(4, 0, 0, 0, Ending.COMPLETE));
        assertThat(typeOf(v3)).isEqualTo("DATAFRAME_PFB");
        assertThat(typeOf(bareLegacy)).isEqualTo("DATAFRAME");
        assertThat(typeOf(wrappedLegacyWithoutAVersion)).isEqualTo("DATAFRAME");
        assertThat(typeOf(legacyThatNamesNoType)).isEqualTo("COUNT");
        Query after = repo.findById(v3).orElseThrow();
        assertThat(after.getQuery()).isEqualTo(V3_SAMPLE_PFB);
        assertThat(after.getVersion()).isEqualTo("3");
        assertThat(after.getStatus()).isEqualTo(before.getStatus()).isEqualTo(PicSureStatus.QUEUED);
        assertThat(after.getStartTime()).isEqualTo(before.getStartTime()).isNotNull();
        assertThat(after.getResourceResultId()).isEqualTo(before.getResourceResultId()).isNotNull();
        assertThat(after.getUserUuid()).isNull();
        assertThat(after.getQueryToVariantUuid()).isNull();
        assertThat(after.getConsents()).isNull();
        assertThat(after.isSample()).isFalse();
    }

    @Test
    void leavesARowWhoseBodyCannotBeReadAndGoesOn() {
        UUID notJson = stored(1, "not json", "3");
        UUID wrongMemberType = stored(2, "{\"query\":{\"select\":{}}}", "3");
        UUID notAQuery = stored(3, "{\"unrelated\":true}", "2");
        UUID untranslatable = stored(4, UNTRANSLATABLE_LEGACY, "2");
        UUID readable = stored(5, V3_COUNT, "3");

        Summary summary = backfill(repo, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 4, Ending.COMPLETE));
        assertThat(List.of(notJson, wrongMemberType, notAQuery, untranslatable)).allSatisfy(row -> assertThat(typeOf(row)).isNull());
        assertThat(typeOf(readable)).isEqualTo("COUNT");
    }

    @Test
    void leavesARowThatIsNotGzip() {
        UUID notGzip = stored(1, V3_COUNT, "3");
        jdbc.update("update query set query = ? where uuid = ?", V3_COUNT.getBytes(StandardCharsets.UTF_8), bytes(notGzip));
        UUID gzip = stored(2, V3_COUNT, "3");

        Summary summary = backfill(repo, 1).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 1, Ending.COMPLETE));
        assertThat(typeOf(notGzip)).isNull();
        assertThat(typeOf(gzip)).isEqualTo("COUNT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "1", "null"})
    void leavesARowWhoseNamedTypePassesTheTypeCheckButIsNotAConstantName(String named) {
        UUID row = stored(1, "{\"query\":{\"select\":[],\"expectedResultType\":" + named + "}}", "3");
        List<String> writes = new ArrayList<>();

        Summary summary = backfill(recordingWrites(writes), 2).runPass();

        assertThat(summary).isEqualTo(new Summary(0, 1, 0, 0, Ending.COMPLETE));
        assertThat(writes).isEmpty();
        assertThat(typeOf(row)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"NOT_A_TYPE\"", "\"dataframe\"", "\"\"", "true"})
    void leavesARowWhoseNamedTypeFailsTheTypeCheck(String named) {
        UUID row = stored(1, "{\"query\":{\"select\":[],\"expectedResultType\":" + named + "}}", "3");
        List<String> writes = new ArrayList<>();

        Summary summary = backfill(recordingWrites(writes), 2).runPass();

        assertThat(summary).isEqualTo(new Summary(0, 0, 0, 1, Ending.COMPLETE));
        assertThat(writes).isEmpty();
        assertThat(typeOf(row)).isNull();
    }

    @Test
    void leavesARowWithoutABodyOrWithoutANamedType() {
        UUID blank = stored(1, "", "3");
        UUID withoutAType = stored(2, V3_WITHOUT_A_TYPE, "3");

        Summary summary = backfill(repo, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(0, 2, 0, 0, Ending.COMPLETE));
        assertThat(List.of(blank, withoutAType)).allSatisfy(row -> assertThat(typeOf(row)).isNull());
    }

    @Test
    void secondPassWritesNothing() {
        UUID unreadable = stored(1, "not json", "3");
        UUID untyped = stored(2, V3_WITHOUT_A_TYPE, "3");
        UUID typedOnTheFirstPage = stored(3, V3_SAMPLE_PFB, "3");
        UUID typedOnTheSecondPage = stored(4, V3_COUNT, "3");
        assertThat(backfill(repo, 3).runPass()).isEqualTo(new Summary(2, 1, 0, 1, Ending.COMPLETE));
        List<String> writes = new ArrayList<>();

        Summary second = backfill(recordingWrites(writes), 2).runPass();

        assertThat(second).isEqualTo(new Summary(0, 1, 0, 1, Ending.COMPLETE));
        assertThat(writes).isEmpty();
        assertThat(typeOf(typedOnTheFirstPage)).isEqualTo("DATAFRAME_PFB");
        assertThat(typeOf(typedOnTheSecondPage)).isEqualTo("COUNT");
        assertThat(typeOf(unreadable)).isNull();
        assertThat(typeOf(untyped)).isNull();
    }

    @Test
    void aRowTypedByAnotherWriterIsNotOverwritten() {
        UUID raced = stored(1, V3_SAMPLE_PFB, "3");
        UUID quiet = stored(2, V3_COUNT, "3");
        QueryRepository typedByAnotherWriterBeforeTheWrite = InterceptedRepository.of(QueryRepository.class, repo, (method, call) -> {
            if (UPDATE_METHOD.equals(method)) {
                jdbc.update("update query set result_type = 'DATAFRAME' where uuid = ? and result_type is null", bytes(raced));
            }
            return call.proceed();
        });

        Summary summary = backfill(typedByAnotherWriterBeforeTheWrite, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 1, 0, Ending.COMPLETE));
        assertThat(typeOf(raced)).isEqualTo("DATAFRAME");
        assertThat(typeOf(quiet)).isEqualTo("COUNT");
    }

    @Test
    void aStatusUpdateBetweenTheReadAndTheWriteIsKept() {
        UUID row = stored(1, V3_COUNT, "3");
        QueryRepository updatedBeforeTheWrite = InterceptedRepository.of(QueryRepository.class, repo, (method, call) -> {
            if (UPDATE_METHOD.equals(method)) {
                service.update(row, new UpdateQueryRequest("AVAILABLE", "result-after-the-read", null, null, null));
            }
            return call.proceed();
        });

        Summary summary = backfill(updatedBeforeTheWrite, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 0, Ending.COMPLETE));
        Query after = repo.findById(row).orElseThrow();
        assertThat(after.getResultType()).isEqualTo("COUNT");
        assertThat(after.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(after.getReadyTime()).isNotNull();
        assertThat(after.getResourceResultId()).isEqualTo("result-after-the-read");
    }

    @Test
    void aRowWhoseWriteFailsIsCountedAndThePassGoesOn() {
        UUID refused = stored(1, V3_SAMPLE_PFB, "3");
        UUID written = stored(2, V3_COUNT, "3");
        List<String> writes = new ArrayList<>();
        QueryRepository firstWriteFails = InterceptedRepository.of(QueryRepository.class, repo, (method, call) -> {
            if (UPDATE_METHOD.equals(method)) {
                writes.add(method);
                if (writes.size() == 1) {
                    throw new DataAccessResourceFailureException("refused");
                }
            }
            return call.proceed();
        });

        Summary summary = backfill(firstWriteFails, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 1, Ending.COMPLETE));
        assertThat(typeOf(refused)).isNull();
        assertThat(typeOf(written)).isEqualTo("COUNT");
    }

    @Test
    void passEndsWhenEveryRemainingRowIsUnreadable() {
        UUID typed = stored(1, V3_COUNT, "3");
        List<UUID> unreadable = List.of(
            stored(2, "not json", "3"), stored(3, "not json", "3"), stored(4, "not json", "3"), stored(5, "not json", "3"),
            stored(6, "not json", "3")
        );
        List<String> reads = new ArrayList<>();
        QueryRepository countingReads = InterceptedRepository.of(QueryRepository.class, repo, (method, call) -> {
            if (method.startsWith("find")) {
                reads.add(method);
            }
            return call.proceed();
        });

        Summary summary = backfill(countingReads, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 5, Ending.COMPLETE));
        assertThat(reads).containsExactly(
            "findFirstWithoutResultType", "findWithoutResultTypeAfter", "findWithoutResultTypeAfter", "findWithoutResultTypeAfter"
        );
        assertThat(typeOf(typed)).isEqualTo("COUNT");
        assertThat(unreadable).allSatisfy(row -> assertThat(typeOf(row)).isNull());
    }

    @Test
    void pagesFollowTheOrderOfTheDatabaseAcrossTheWholeIdRange() {
        List<UUID> rows = List.of(
            stored(new UUID(0L, 1L), V3_COUNT, "3"), stored(new UUID(Long.MAX_VALUE, -1L), V3_COUNT, "3"),
            stored(new UUID(Long.MIN_VALUE, 0L), V3_COUNT, "3"), stored(new UUID(-1L, -1L), V3_COUNT, "3"),
            stored(UUID.randomUUID(), V3_COUNT, "3"), stored(UUID.randomUUID(), V3_COUNT, "3"), stored(UUID.randomUUID(), V3_COUNT, "3")
        );

        Summary summary = backfill(repo, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(7, 0, 0, 0, Ending.COMPLETE));
        assertThat(rows).allSatisfy(row -> assertThat(typeOf(row)).isEqualTo("COUNT"));
    }

    @Test
    void aPageThatCannotBeReadEndsThePassWithoutAFailure() {
        QueryRepository unreachable = mock(QueryRepository.class);
        when(unreachable.findFirstWithoutResultType(any())).thenThrow(new DataAccessResourceFailureException("no connection"));

        Summary summary = backfill(unreachable, 2).runPass();

        assertThat(summary).isEqualTo(new Summary(0, 0, 0, 0, Ending.READ_FAILED));
        verify(unreachable, never()).setResultTypeWhereAbsent(any(), any());
    }

    @Test
    void doesNotRunWhenDisabled() {
        QueryRepository untouched = mock(QueryRepository.class);
        ResultTypeBackfill disabled = new ResultTypeBackfill(untouched, new ResultTypeBackfillProperties(false, 2, Duration.ZERO));

        disabled.start();

        assertThat(disabled.isRunning()).isFalse();
        verifyNoInteractions(untouched);
    }

    @Test
    void startRunsOnePassOnADaemonThreadAndReturnsBeforeItEnds() throws Exception {
        CountDownLatch passIsReading = new CountDownLatch(1);
        CountDownLatch letThePassGoOn = new CountDownLatch(1);
        AtomicReference<Thread> passThread = new AtomicReference<>();
        QueryRepository held = mock(QueryRepository.class);
        when(held.findFirstWithoutResultType(any())).thenAnswer(read -> {
            passThread.set(Thread.currentThread());
            passIsReading.countDown();
            letThePassGoOn.await();
            return List.of();
        });
        ResultTypeBackfill backfill = backfill(held, 2);

        backfill.start();

        assertThat(passIsReading.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(backfill.isRunning()).isTrue();
        assertThat(passThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(passThread.get().isDaemon()).isTrue();
        backfill.start();
        letThePassGoOn.countDown();
        passThread.get().join();
        assertThat(backfill.isRunning()).isFalse();
        verify(held, times(1)).findFirstWithoutResultType(Limit.of(2));
    }

    @Test
    void stopEndsAPassThatIsPausingAndNoLaterPageIsRead() throws Exception {
        CountDownLatch firstPageRead = new CountDownLatch(1);
        AtomicReference<Thread> passThread = new AtomicReference<>();
        QueryRepository onePage = mock(QueryRepository.class);
        when(onePage.findFirstWithoutResultType(any())).thenAnswer(read -> {
            passThread.set(Thread.currentThread());
            firstPageRead.countDown();
            return List.of(QueryFixtures.withRandomId().setQuery("").setVersion("3"));
        });
        ResultTypeBackfill pausing = new ResultTypeBackfill(onePage, new ResultTypeBackfillProperties(true, 1, Duration.ofHours(1)));
        pausing.start();
        assertThat(firstPageRead.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        awaitThePause(passThread.get());

        pausing.stop();

        assertThat(pausing.isRunning()).isFalse();
        verify(onePage, never()).findWithoutResultTypeAfter(any(), any());
        pausing.start();
        assertThat(pausing.isRunning()).isFalse();
        verify(onePage, times(1)).findFirstWithoutResultType(any());
    }

    @Test
    void stopBetweenTwoRowsEndsThePassBeforeTheNextRow() {
        List<UUID> rows = List.of(stored(1, V3_COUNT, "3"), stored(2, V3_COUNT, "3"), stored(3, V3_COUNT, "3"));
        AtomicReference<ResultTypeBackfill> running = new AtomicReference<>();
        QueryRepository stoppedAfterTheFirstWrite = InterceptedRepository.of(QueryRepository.class, repo, (method, call) -> {
            Object result = call.proceed();
            if (UPDATE_METHOD.equals(method)) {
                running.get().stop();
            }
            return result;
        });
        running.set(backfill(stoppedAfterTheFirstWrite, 3));

        Summary summary = running.get().runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 0, Ending.STOPPED));
        assertThat(rows).extracting(this::typeOf).containsExactly("COUNT", null, null);
    }

    /** Returns once the thread of a pass is inside its pause between two pages, which is the only timed wait a pass makes. */
    private static void awaitThePause(Thread passThread) throws InterruptedException {
        while (passThread.getState() != Thread.State.TIMED_WAITING) {
            Thread.sleep(1);
        }
    }

    private static ResultTypeBackfill backfill(QueryRepository queries, int batchSize) {
        return new ResultTypeBackfill(queries, new ResultTypeBackfillProperties(true, batchSize, Duration.ZERO));
    }

    /** Wraps the repository so that every guarded write the pass makes is added to the list before it runs. */
    private QueryRepository recordingWrites(List<String> writes) {
        return InterceptedRepository.of(QueryRepository.class, repo, (method, call) -> {
            if (UPDATE_METHOD.equals(method)) {
                writes.add(method);
            }
            return call.proceed();
        });
    }

    /**
     * Stores a row without a result type, as a save without an owner does, under an id that sorts in the order of the given number.
     *
     * @param order the position of the row among the rows of the test, from 1
     * @param body the request body to store, which the entity compresses
     * @param version the version to store, or null for none
     * @return the id of the row
     */
    private UUID stored(int order, String body, String version) {
        return stored(new UUID(0L, order), body, version);
    }

    private UUID stored(UUID id, String body, String version) {
        Query row = new Query().setQuery(body).setVersion(version);
        row.setUuid(id);
        row.setStatus(PicSureStatus.QUEUED);
        row.setStartTime(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        row.setResourceResultId("result-" + id);
        return repo.saveAndFlush(row).getUuid();
    }

    private String typeOf(UUID id) {
        return repo.findById(id).orElseThrow().getResultType();
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
