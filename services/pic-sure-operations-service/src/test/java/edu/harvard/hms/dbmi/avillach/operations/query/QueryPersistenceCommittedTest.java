package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

/**
 * Runs saves and updates in the transactions the service opens itself, on H2, with no test transaction around them. A refusal is therefore
 * checked against what was committed, and two threads can race on one row. Every test deletes the rows it committed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(QueryPersistenceService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueryPersistenceCommittedTest {

    private static final String CONSENTS = "\\_consents\\";
    private static final Map<String, List<String>> TWO_STUDIES = Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1"));
    private static final Map<String, List<String>> ONE_STUDY = Map.of(CONSENTS, List.of("phs1.c1"));
    private static final int RESAVE_ROUNDS = 10;
    private static final Instant NEVER_RUN_START = Instant.parse("2025-03-04T05:06:07Z");
    private static final String LONGER_THAN_THE_RESULT_TYPE_COLUMN =
        "DATAFRAME_DATAFRAME_DATAFRAME_DATAFRAME_DATAFRAME_DATAFRAME_DATAFRAME";

    @Autowired
    private QueryPersistenceService service;

    @Autowired
    private QueryRepository repo;

    @Autowired
    private QueryRunRepository runRepo;

    @Autowired
    private QueryToVariantRepository generalQueryRepo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void deleteCommittedRows() {
        runRepo.deleteAll();
        repo.deleteAll();
        generalQueryRepo.deleteAll();
    }

    @Test
    void saveFromADifferentOwnerIsRefusedAndChangesNothing() {
        UUID picsureId = UUID.randomUUID();
        service.save(
            new SaveQueryRequest(
                "{\"q\":1}", "first-result", "QUEUED", "3", encoded("first"), picsureId, UUID.randomUUID(), TWO_STUDIES, "FIRST_SUBMIT",
                UUID.randomUUID(), false, "COUNT"
            )
        );
        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        StoredQuery before = service.get(picsureId);
        SaveQueryRequest fromAStranger = new SaveQueryRequest(
            "{\"q\":1}", "other-result", "PENDING", "3", encoded("second"), picsureId, UUID.randomUUID(), ONE_STUDY, "RESUBMIT",
            UUID.randomUUID(), false, "COUNT"
        );

        assertRefused(() -> service.save(fromAStranger), 409, "conflict", "Query " + picsureId + " belongs to a different owner");

        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId)).hasSize(1);
    }

    @Test
    void ownerlessSaveOnAnOwnedRowIsRefusedAndChangesNothing() {
        UUID picsureId = UUID.randomUUID();
        service.save(owned(picsureId, UUID.randomUUID(), TWO_STUDIES, "FIRST_SUBMIT"));
        StoredQuery before = service.get(picsureId);
        SaveQueryRequest ownerless = new SaveQueryRequest("{\"q\":1}", "other-result", "PENDING", "3", encoded("second"));

        assertRefused(() -> service.save(picsureId, ownerless), 409, "conflict", "Query " + picsureId + " belongs to a different owner");

        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId)).hasSize(1);
    }

    @Test
    void ownedSaveOnAnOwnerlessRowIsRefusedAndChangesNothing() {
        UUID picsureId = service.save(new SaveQueryRequest("{\"q\":1}", "first-result", "QUEUED", "3", encoded("first")));
        StoredQuery before = service.get(picsureId);

        assertRefused(
            () -> service.save(owned(picsureId, UUID.randomUUID(), ONE_STUDY, "FIRST_SUBMIT")), 409, "conflict",
            "Query " + picsureId + " belongs to a different owner"
        );

        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(before.userUuid()).isNull();
        assertThat(runRepo.count()).isZero();
    }

    @Test
    void concurrentFirstSavesByDifferentOwnersKeepTheWinnerAndRefuseTheLoser() throws Exception {
        UUID picsureId = UUID.randomUUID();
        List<SaveQueryRequest> requests = List.of(
            owned(picsureId, UUID.randomUUID(), TWO_STUDIES, "FIRST_SUBMIT"), owned(picsureId, UUID.randomUUID(), ONE_STUDY, "FIRST_SUBMIT")
        );

        List<SaveOutcome> outcomes =
            RacingFirstSaves.run(repo, runRepo, generalQueryRepo, transactionManager, picsureId, requests.get(0), requests.get(1));

        ConcurrentSaveAssertions.assertOneOwnerWon(picsureId, requests, outcomes, service.get(picsureId), runsOf(picsureId));
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void updateThatReadTheRowBeforeAConsentChangeSaveDoesNotOverwriteIt() throws Exception {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, TWO_STUDIES, "FIRST_SUBMIT"));
        UUID firstRun = service.get(picsureId).runId();

        boolean savedWhileTheUpdateWasHeld = UpdateHeldAcrossASave.run(
            repo, runRepo, generalQueryRepo, transactionManager, picsureId,
            new UpdateQueryRequest("AVAILABLE", null, null, null, null, firstRun),
            new SaveQueryRequest(
                "{\"q\":1}", picsureId.toString(), "PENDING", "3", null, picsureId, owner, ONE_STUDY, "CONSENT_CHANGE", UUID.randomUUID(),
                false, "COUNT"
            )
        );

        ConcurrentSaveAssertions.assertTheSecondRunStands(firstRun, service.get(picsureId), runsOf(picsureId));
        assertThat(savedWhileTheUpdateWasHeld).isFalse();
    }

    @Test
    void concurrentResavesGetDistinctSubmitTimesAndTheRowKeepsTheLatestRunsConsents() throws Exception {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Clock stopped = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs0.c1")), "FIRST_SUBMIT"));

        for (int round = 1; round <= RESAVE_ROUNDS; round++) {
            List<SaveOutcome> outcomes = RacingResaves.run(
                repo, runRepo, generalQueryRepo, transactionManager, stopped, owned(picsureId, owner, TWO_STUDIES, "CONSENT_CHANGE"),
                owned(picsureId, owner, ONE_STUDY, "CONSENT_CHANGE")
            );

            ConcurrentSaveAssertions.assertRunsAreOrdered(
                round, outcomes, service.get(picsureId), runsOf(picsureId),
                runRepo.findFirstByQueryUuidOrderBySubmittedAtDesc(picsureId).orElseThrow()
            );
        }
    }

    @Test
    void updateNamingAnOlderRunIsRefusedAndChangesNothing() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, TWO_STUDIES, "FIRST_SUBMIT"));
        UUID olderRun = service.get(picsureId).runId();
        service.save(
            new SaveQueryRequest(
                "{\"q\":1}", picsureId.toString(), "PENDING", "3", null, picsureId, owner, ONE_STUDY, "CONSENT_CHANGE", UUID.randomUUID(),
                false, "COUNT"
            )
        );
        StoredQuery before = service.get(picsureId);
        assertThat(before.runId()).isNotEqualTo(olderRun);

        assertRefused(
            () -> service.update(picsureId, new UpdateQueryRequest("AVAILABLE", "rr-stale", encoded("stale"), null, null, olderRun)), 409,
            "conflict", "The update names a run that is not the latest run of query " + picsureId
        );
        assertRefused(
            () -> service.update(picsureId, new UpdateQueryRequest(null, "rr-stale", null, null, null, olderRun)), 409, "conflict",
            "The update names a run that is not the latest run of query " + picsureId
        );

        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(runsOf(picsureId)).allSatisfy(run -> {
            assertThat(run.getCompletedAt()).isNull();
            assertThat(run.getStatus()).isNull();
        });

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null, before.runId()));

        StoredQuery afterCurrentPoll = service.get(picsureId);
        assertThat(afterCurrentPoll.status()).isEqualTo("AVAILABLE");
        assertThat(afterCurrentPoll.readyTime()).isNotNull();
        assertThat(runRepo.findById(before.runId()).orElseThrow().getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(runRepo.findById(olderRun).orElseThrow().getStatus()).isNull();
    }

    @Test
    void updateNamingARunOnARowWithNoRunsIsRefusedAndChangesNothing() {
        UUID picsureId = service.save(new SaveQueryRequest("{}", "rr-kept", "QUEUED", null, null));
        StoredQuery before = service.get(picsureId);

        assertRefused(
            () -> service.update(picsureId, new UpdateQueryRequest("AVAILABLE", "rr-stale", null, null, null, UUID.randomUUID())), 409,
            "conflict", "The update names a run that is not the latest run of query " + picsureId
        );

        assertThat(service.get(picsureId)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"{\"q\":2},", ",4", "{\"q\":2},4"})
    void updateOfTheBodyOrVersionOfAnOwnedRowIsRefusedAndChangesNothing(String query, String version) {
        UUID picsureId = UUID.randomUUID();
        service.save(owned(picsureId, UUID.randomUUID(), TWO_STUDIES, "FIRST_SUBMIT"));
        StoredQuery before = service.get(picsureId);

        assertRefused(
            () -> service.update(picsureId, new UpdateQueryRequest("AVAILABLE", "rr-new", null, query, version, before.runId())), 400,
            "bad_request", "The query and version of an owned query cannot be changed"
        );

        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(runsOf(picsureId)).singleElement().satisfies(run -> assertThat(run.getStatus()).isNull());
    }

    @ParameterizedTest(name = "picsureId={0} userUuid={1} consents={2} trigger={3} queryToVariantUuid={4}")
    @MethodSource("partlyOwnedShapes")
    void saveThatIsNeitherLegacyNorFullyOwnedIsRefusedAndStoresNothing(
        boolean withId, boolean withOwner, boolean withConsents, boolean withTrigger, boolean withGeneralQuery
    ) {
        UUID picsureId = UUID.randomUUID();
        SaveQueryRequest partlyOwned = new SaveQueryRequest(
            "{\"q\":1}", "rr", "QUEUED", "3", null, withId ? picsureId : null, withOwner ? UUID.randomUUID() : null,
            withConsents ? ONE_STUDY : null, withTrigger ? "FIRST_SUBMIT" : null, withGeneralQuery ? UUID.randomUUID() : null, false,
            "COUNT"
        );

        assertRefused(
            () -> service.save(partlyOwned), 400, "bad_request",
            "picsureId, userUuid, consents, trigger and queryToVariantUuid must be sent together or not at all"
        );

        assertThat(repo.count()).isZero();
        assertThat(runRepo.count()).isZero();
        assertThat(generalQueryRepo.count()).isZero();
    }

    static Stream<Arguments> partlyOwnedShapes() {
        return IntStream.range(1, 31).mapToObj(
            present -> Arguments.of((present & 16) != 0, (present & 8) != 0, (present & 4) != 0, (present & 2) != 0, (present & 1) != 0)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_A_RESULT_TYPE", "dataframe", "Dataframe", "DATAFRAME ", "", LONGER_THAN_THE_RESULT_TYPE_COLUMN})
    void ownedSaveWithAnUnknownResultTypeIsRefusedAndStoresNothing(String resultType) {
        UUID picsureId = UUID.randomUUID();
        SaveQueryRequest unknownType = new SaveQueryRequest(
            "{\"q\":1}", picsureId.toString(), "QUEUED", "3", null, picsureId, UUID.randomUUID(), ONE_STUDY, "FIRST_SUBMIT",
            UUID.randomUUID(), false, resultType
        );

        assertRefused(() -> service.save(unknownType), 400, "invalid_result_type", "Unknown result type: " + resultType);

        assertThat(repo.count()).isZero();
        assertThat(runRepo.count()).isZero();
        assertThat(generalQueryRepo.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_A_RESULT_TYPE", "dataframe"})
    void ownerlessSaveWithAnUnknownResultTypeIsRefusedAndStoresNothing(String resultType) {
        SaveQueryRequest unknownType =
            new SaveQueryRequest("{\"q\":1}", "rr", "QUEUED", "3", null, null, null, null, null, null, false, resultType);

        assertRefused(() -> service.save(unknownType), 400, "invalid_result_type", "Unknown result type: " + resultType);
        assertRefused(() -> service.save(UUID.randomUUID(), unknownType), 400, "invalid_result_type", "Unknown result type: " + resultType);

        assertThat(repo.count()).isZero();
        assertThat(runRepo.count()).isZero();
        assertThat(generalQueryRepo.count()).isZero();
    }

    @Test
    void resaveWithAnUnknownResultTypeIsRefusedAndChangesNothing() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        service.save(variant(picsureId, owner, general, true, "DATAFRAME"));
        StoredQuery before = service.get(picsureId);

        assertRefused(
            () -> service.save(variant(picsureId, owner, general, true, "dataframe")), 400, "invalid_result_type",
            "Unknown result type: dataframe"
        );

        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(repo.findById(picsureId).orElseThrow().getResultType()).isEqualTo("DATAFRAME");
        assertThat(runsOf(picsureId)).hasSize(1);
    }

    @Test
    void secondRowOfOneVariantUnderAnotherIdFailsOnTheVariantKeyAndStoresNothing() {
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        UUID storedRow = UUID.randomUUID();
        UUID secondRow = UUID.randomUUID();
        service.save(variant(storedRow, owner, general, true, "DATAFRAME"));
        StoredQuery before = service.get(storedRow);

        Throwable failure = catchThrowable(() -> service.save(variant(secondRow, owner, general, true, "DATAFRAME")));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure).rootCause()
            .isInstanceOfSatisfying(SQLException.class, refusal -> assertThat(refusal.getSQLState()).isEqualTo("23505"));
        assertThat(repo.findById(secondRow)).isEmpty();
        assertThat(repo.count()).isEqualTo(1);
        assertThat(runsOf(secondRow)).isEmpty();
        assertThat(service.get(storedRow)).isEqualTo(before);
        assertThat(runsOf(storedRow)).hasSize(1);
        Query row = repo.findById(storedRow).orElseThrow();
        assertThat(row.getQueryToVariantUuid()).isEqualTo(general);
        assertThat(row.isSample()).isTrue();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME");
        assertThat(generalQueryRepo.count()).isEqualTo(1);
    }

    @Test
    void fullyOwnedSaveWithAnEmptyConsentMapIsStored() {
        UUID picsureId = UUID.randomUUID();

        service.save(owned(picsureId, UUID.randomUUID(), Map.of(), "FIRST_SUBMIT"));

        assertThat(service.get(picsureId).consents()).isEmpty();
        assertThat(runsOf(picsureId)).singleElement().satisfies(run -> assertThat(run.getConsents()).isEmpty());
    }

    @Test
    void queryRowChangeRollsBackWhenTheRunInsertFails() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, TWO_STUDIES, "FIRST_SUBMIT"));
        StoredQuery before = service.get(picsureId);
        AtomicInteger runInserts = new AtomicInteger();
        QueryPersistenceService failingAtTheRun =
            new QueryPersistenceService(repo, failingRunInserts(runInserts), generalQueryRepo, transactionManager);
        SaveQueryRequest resave = new SaveQueryRequest(
            "{\"q\":1}", "other-result", "PENDING", "3", encoded("second"), picsureId, owner, ONE_STUDY, "RESUBMIT", UUID.randomUUID(),
            false, "COUNT"
        );

        assertThatThrownBy(() -> failingAtTheRun.save(resave)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessage("run insert refused");

        assertThat(runInserts).hasValue(1);
        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(runsOf(picsureId)).hasSize(1);
    }

    @Test
    void firstSaveLeavesNoRowWhenTheRunInsertFailsAndIsNotTriedAgain() {
        UUID picsureId = UUID.randomUUID();
        AtomicInteger runInserts = new AtomicInteger();
        QueryPersistenceService failingAtTheRun =
            new QueryPersistenceService(repo, failingRunInserts(runInserts), generalQueryRepo, transactionManager);

        assertThatThrownBy(() -> failingAtTheRun.save(owned(picsureId, UUID.randomUUID(), ONE_STUDY, "FIRST_SUBMIT")))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessage("run insert refused");

        assertThat(runInserts).hasValue(1);
        assertThat(repo.findById(picsureId)).isEmpty();
        assertThat(runRepo.count()).isZero();
        assertThat(generalQueryRepo.count()).isZero();
    }

    @Test
    void firstSaveThatFailsOnSomethingOtherThanADuplicateIdIsNotTriedAgain() {
        UUID picsureId = UUID.randomUUID();
        List<String> rowCalls = new ArrayList<>();
        QueryRepository counted = InterceptedRepository.of(QueryRepository.class, repo, (methodName, call) -> {
            rowCalls.add(methodName);
            return call.proceed();
        });
        QueryPersistenceService countingService = new QueryPersistenceService(counted, runRepo, generalQueryRepo, transactionManager);
        String metadataLongerThanTheColumn = Base64.getEncoder().encodeToString(new byte[9000]);
        SaveQueryRequest tooLong = new SaveQueryRequest(
            "{\"q\":1}", "rr", "QUEUED", "3", metadataLongerThanTheColumn, picsureId, UUID.randomUUID(), ONE_STUDY, "FIRST_SUBMIT",
            UUID.randomUUID(), false, "COUNT"
        );

        assertThatThrownBy(() -> countingService.save(tooLong)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rowCalls.stream().filter(name -> name.equals("saveAndFlush"))).hasSize(1);
        assertThat(rowCalls.stream().filter(name -> name.startsWith("find"))).hasSize(1);
        assertThat(repo.findById(picsureId)).isEmpty();
        assertThat(runRepo.count()).isZero();
    }

    @Test
    void storeNeverRunInsertsAnOwnedRowWithNoStatusNoConsentsAndNoRun() {
        NeverRunQuery neverRun = neverRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        Query returned = service.storeNeverRun(neverRun);

        assertThat(returned.getUuid()).isEqualTo(neverRun.picsureId());
        Query row = repo.findById(neverRun.picsureId()).orElseThrow();
        assertThat(row.getUserUuid()).isEqualTo(neverRun.userUuid());
        assertThat(row.getQueryToVariantUuid()).isEqualTo(neverRun.queryToVariantUuid());
        assertThat(row.isSample()).isTrue();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME_PFB");
        assertThat(row.getQuery()).isEqualTo(neverRun.query());
        assertThat(row.getVersion()).isEqualTo("3");
        assertThat(row.getResourceResultId()).isEqualTo(neverRun.picsureId().toString());
        assertThat(row.getStartTime()).isEqualTo(NEVER_RUN_START);
        assertThat(row.getStatus()).isNull();
        assertThat(row.getConsents()).isNull();
        assertThat(row.getReadyTime()).isNull();
        assertThat(row.getMetadata()).isNull();
        assertThat(runsOf(neverRun.picsureId())).isEmpty();
        assertThat(generalQueryRepo.findById(neverRun.queryToVariantUuid())).get().extracting(QueryToVariant::getUserUuid)
            .isEqualTo(neverRun.userUuid());
        StoredQuery asTheQueryServiceReadsIt = service.get(neverRun.picsureId());
        assertThat(asTheQueryServiceReadsIt.status()).isNull();
        assertThat(asTheQueryServiceReadsIt.consents()).isNull();
        assertThat(asTheQueryServiceReadsIt.runId()).isNull();
        assertThat(asTheQueryServiceReadsIt.userUuid()).isEqualTo(neverRun.userUuid());
    }

    @Test
    void storeNeverRunStoresARowWithNoResultTypeAndNoStartTime() {
        UUID picsureId = UUID.randomUUID();

        service.storeNeverRun(new NeverRunQuery(picsureId, UUID.randomUUID(), UUID.randomUUID(), false, null, "{\"query\":{}}", null));

        Query row = repo.findById(picsureId).orElseThrow();
        assertThat(row.getResultType()).isNull();
        assertThat(row.getStartTime()).isNull();
        assertThat(row.isSample()).isFalse();
    }

    @Test
    void storeNeverRunReturnsARowAlreadyStoredUnderTheIdAndWritesNothingOfIt() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        service.save(variant(picsureId, owner, general, false, "COUNT"));
        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        StoredQuery before = service.get(picsureId);

        Query returned = service.storeNeverRun(neverRun(picsureId, owner, general));

        assertThat(returned.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(service.get(picsureId)).isEqualTo(before);
        Query row = repo.findById(picsureId).orElseThrow();
        assertThat(row.isSample()).isFalse();
        assertThat(row.getResultType()).isEqualTo("COUNT");
        assertThat(row.getQuery()).isEqualTo("{\"q\":1}");
        assertThat(runsOf(picsureId)).hasSize(1);
        assertThat(repo.count()).isEqualTo(1);
        assertThat(generalQueryRepo.count()).isEqualTo(1);
    }

    @Test
    void storeNeverRunRefusesARowStoredUnderAnotherOwnerOrNoneAndStoresNothing() {
        UUID ownedByAStranger = UUID.randomUUID();
        service.save(owned(ownedByAStranger, UUID.randomUUID(), ONE_STUDY, "FIRST_SUBMIT"));
        UUID ownerless = service.save(new SaveQueryRequest("{\"q\":1}", "rr", "AVAILABLE", "3", null));
        StoredQuery strangersBefore = service.get(ownedByAStranger);
        StoredQuery ownerlessBefore = service.get(ownerless);

        for (UUID taken : List.of(ownedByAStranger, ownerless)) {
            assertRefused(
                () -> service.storeNeverRun(neverRun(taken, UUID.randomUUID(), UUID.randomUUID())), 409, "conflict",
                "Query " + taken + " belongs to a different owner"
            );
        }

        assertThat(service.get(ownedByAStranger)).isEqualTo(strangersBefore);
        assertThat(service.get(ownerless)).isEqualTo(ownerlessBefore);
        assertThat(repo.count()).isEqualTo(2);
        assertThat(generalQueryRepo.count()).isEqualTo(1);
        assertThat(runRepo.count()).isEqualTo(1);
    }

    @Test
    void storeNeverRunRefusesAGeneralQueryOfAnotherOwnerAndStoresNothing() {
        UUID strangersRow = UUID.randomUUID();
        UUID strangersGeneral = UUID.randomUUID();
        service.save(variant(strangersRow, UUID.randomUUID(), strangersGeneral, false, "COUNT"));
        UUID picsureId = UUID.randomUUID();

        assertRefused(
            () -> service.storeNeverRun(neverRun(picsureId, UUID.randomUUID(), strangersGeneral)), 409, "conflict",
            "General query " + strangersGeneral + " belongs to a different owner"
        );

        assertThat(repo.findById(picsureId)).isEmpty();
        assertThat(repo.count()).isEqualTo(1);
        assertThat(generalQueryRepo.count()).isEqualTo(1);
    }

    @Test
    void storeNeverRunJoinsACallersTransactionAndIsUndoneWithIt() {
        NeverRunQuery neverRun = neverRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        TransactionTemplate callersTransaction = new TransactionTemplate(transactionManager);

        callersTransaction.executeWithoutResult(status -> {
            service.storeNeverRun(neverRun);
            assertThat(repo.findById(neverRun.picsureId())).isPresent();
            status.setRollbackOnly();
        });

        assertThat(repo.findById(neverRun.picsureId())).isEmpty();
        assertThat(generalQueryRepo.findById(neverRun.queryToVariantUuid())).isEmpty();
    }

    @Test
    void storeNeverRunThatIsRefusedInsideACallersTransactionLeavesThatTransactionToRollBack() {
        UUID taken = UUID.randomUUID();
        service.save(owned(taken, UUID.randomUUID(), ONE_STUDY, "FIRST_SUBMIT"));
        UUID general = UUID.randomUUID();
        TransactionTemplate callersTransaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
            () -> callersTransaction.executeWithoutResult(status -> service.storeNeverRun(neverRun(taken, UUID.randomUUID(), general)))
        ).isInstanceOf(PicsureException.class);

        assertThat(generalQueryRepo.findById(general)).isEmpty();
    }

    @Test
    void aNeverRunQueryNeedsAnIdAnOwnerAGeneralQueryAndABody() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new NeverRunQuery(null, id, id, false, null, "{}", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("picsureId is null");
        assertThatThrownBy(() -> new NeverRunQuery(id, null, id, false, null, "{}", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("userUuid is null");
        assertThatThrownBy(() -> new NeverRunQuery(id, id, null, false, null, "{}", null)).isInstanceOf(NullPointerException.class)
            .hasMessage("queryToVariantUuid is null");
        assertThatThrownBy(() -> new NeverRunQuery(id, id, id, false, null, null, null)).isInstanceOf(NullPointerException.class)
            .hasMessage("query is null");
    }

    private static NeverRunQuery neverRun(UUID picsureId, UUID owner, UUID general) {
        return new NeverRunQuery(
            picsureId, owner, general, true, ResultType.DATAFRAME_PFB, "{\"query\":{\"select\":[],\"sample\":true}}", NEVER_RUN_START
        );
    }

    private QueryRunRepository failingRunInserts(AtomicInteger runInserts) {
        return InterceptedRepository.of(QueryRunRepository.class, runRepo, (methodName, call) -> {
            if (methodName.equals("saveAndFlush")) {
                runInserts.incrementAndGet();
                throw new DataIntegrityViolationException("run insert refused");
            }
            return call.proceed();
        });
    }

    private List<QueryRun> runsOf(UUID picsureId) {
        return runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId);
    }

    private static void assertRefused(ThrowingCallable call, int status, String errorType, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PicsureException.class, refusal -> {
            assertThat(refusal.getStatus().value()).isEqualTo(status);
            assertThat(refusal.getErrorType()).isEqualTo(errorType);
            assertThat(refusal).hasMessage(message);
        });
    }

    private static SaveQueryRequest owned(UUID picsureId, UUID owner, Map<String, List<String>> consents, String trigger) {
        return new SaveQueryRequest(
            "{\"q\":1}", picsureId.toString(), "QUEUED", "3", null, picsureId, owner, consents, trigger, UUID.randomUUID(), false, "COUNT"
        );
    }

    private static SaveQueryRequest variant(UUID picsureId, UUID owner, UUID general, boolean sample, String resultType) {
        return new SaveQueryRequest(
            "{\"q\":1}", picsureId.toString(), "QUEUED", "3", null, picsureId, owner, ONE_STUDY, "FIRST_SUBMIT", general, sample, resultType
        );
    }

    private static String encoded(String metadata) {
        return Base64.getEncoder().encodeToString(metadata.getBytes());
    }
}
