package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

/**
 * Boots a real (H2) JPA context so save/get/update/dispatch are proven against an actual repository, not a mock.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(QueryPersistenceService.class)
class QueryPersistenceServiceTest {

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

    @Autowired
    private TestEntityManager entityManager;

    private static final String CONSENTS = "\\_consents\\";
    private static final String TOPMED = "\\_topmed_consents\\";

    @Test
    void saveThenGetRoundTripsTheGzipQueryStatusAndVersion() {
        SaveQueryRequest req = new SaveQueryRequest(
            "{\"select\":[\"foo\"]}", "resource-result-1", "QUEUED", "3", Base64.getEncoder().encodeToString("meta".getBytes())
        );

        UUID picsureId = service.save(req);
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.picsureId()).isEqualTo(picsureId);
        assertThat(stored.query()).isEqualTo("{\"select\":[\"foo\"]}");
        assertThat(stored.resourceResultId()).isEqualTo("resource-result-1");
        assertThat(stored.status()).isEqualTo("QUEUED");
        assertThat(stored.version()).isEqualTo("3");
        assertThat(new String(Base64.getDecoder().decode(stored.metadata()))).isEqualTo("meta");
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(unknown)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void updateChangesOnlyThePresentFields() {
        UUID picsureId = service.save(new SaveQueryRequest("{\"q\":1}", "orig-result-id", "QUEUED", "1", null));
        entityManager.flush();
        entityManager.clear();

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.status()).isEqualTo("AVAILABLE");
        // resourceResultId/metadata were not part of the update -> unchanged.
        assertThat(stored.resourceResultId()).isEqualTo("orig-result-id");
        assertThat(stored.version()).isEqualTo("1");
        assertThat(stored.query()).isEqualTo("{\"q\":1}");
    }

    @Test
    void updateReplacesQueryAndVersionWhenPresent() {
        UUID picsureId = service.save(new SaveQueryRequest("{\"query\":{\"categoryFilters\":{}}}", "rr-old", "AVAILABLE", null, null));
        entityManager.flush();
        entityManager.clear();

        service.update(picsureId, new UpdateQueryRequest("PENDING", "rr-new", null, "{\"query\":{\"phenotypicClause\":null}}", "3"));
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.query()).isEqualTo("{\"query\":{\"phenotypicClause\":null}}");
        assertThat(stored.version()).isEqualTo("3");
        assertThat(stored.resourceResultId()).isEqualTo("rr-new");
        assertThat(stored.status()).isEqualTo("PENDING");
        assertThat(service.dispatchQueryJson(picsureId)).isEqualTo("{\"query\":{\"phenotypicClause\":null}}");
    }

    @Test
    void updateStripsResourceCredentialsFromAReplacementQuery() {
        UUID picsureId = service.save(new SaveQueryRequest("{}", null, "QUEUED", null, null));
        entityManager.flush();
        entityManager.clear();

        service.update(picsureId, new UpdateQueryRequest(null, null, null, "{\"resourceCredentials\":{\"k\":\"v\"},\"query\":{}}", null));
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.query()).isEqualTo("{\"query\":{}}");
        assertThat(stored.version()).isNull();
    }

    @Test
    void updateUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        UpdateQueryRequest req = new UpdateQueryRequest("AVAILABLE", null, null, null, null);
        assertThatThrownBy(() -> service.update(unknown, req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void saveStampsStartTimeAndFirstAvailableTransitionStampsReadyTime() {
        UUID picsureId = service.save(new SaveQueryRequest("{}", null, "QUEUED", null, null));
        entityManager.flush();
        entityManager.clear();

        StoredQuery afterSave = service.get(picsureId);
        assertThat(afterSave.startTime()).isNotNull();
        assertThat(afterSave.readyTime()).isNull();

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        StoredQuery ready = service.get(picsureId);
        assertThat(ready.startTime()).isEqualTo(afterSave.startTime());
        assertThat(ready.readyTime()).isNotNull();

        // a later status update must not move the original readyTime
        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", "rr-later", null, null, null));
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(picsureId).readyTime()).isEqualTo(ready.readyTime());
    }

    @Test
    void timingFieldsKeepTheirTimeOfDayThroughGet() {
        Instant start = Instant.parse("2024-01-02T15:30:45.123Z");
        Instant ready = Instant.parse("2024-01-02T15:31:02.456Z");
        Query query = QueryFixtures.withRandomId();
        query.setQuery("{}");
        query.setStartTime(start);
        query.setReadyTime(ready);
        UUID picsureId = repo.save(query).getUuid();
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.startTime()).isEqualTo(start.toEpochMilli());
        assertThat(stored.readyTime()).isEqualTo(ready.toEpochMilli());
    }

    @Test
    void saveStripsResourceCredentialsBeforePersisting() {
        UUID picsureId = service.save(
            new SaveQueryRequest(
                "{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}", null, "QUEUED", null, null
            )
        );
        entityManager.flush();
        entityManager.clear();

        Query entity = repo.findById(picsureId).orElseThrow();
        assertThat(entity.getQuery()).doesNotContain("resourceCredentials").doesNotContain("secret").contains("\"query\":\"q\"");
    }

    @Test
    void getStripsResourceCredentialsFromRowsStoredBeforeWritersStripped() {
        Query legacy = QueryFixtures.withRandomId();
        legacy.setQuery("{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}");
        UUID picsureId = repo.save(legacy).getUuid();
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.query()).doesNotContain("resourceCredentials").doesNotContain("secret").contains("\"query\":\"q\"");
    }

    @Test
    void dispatchStripsResourceCredentialsAndReturnsAString() {
        UUID picsureId = service.save(
            new SaveQueryRequest(
                "{\"resourceUUID\":\"r\",\"resourceCredentials\":{\"BEARER_TOKEN\":\"secret\"},\"query\":\"q\"}", null, "QUEUED", null, null
            )
        );
        entityManager.flush();
        entityManager.clear();

        String dispatchJson = service.dispatchQueryJson(picsureId);

        assertThat(dispatchJson).doesNotContain("resourceCredentials").doesNotContain("secret").contains("\"query\":\"q\"");
    }

    @Test
    void dispatchUnknownIdThrowsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.dispatchQueryJson(unknown)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void dispatchOfBlankStoredQueryReturnsNull() {
        UUID picsureId = service.save(new SaveQueryRequest(null, null, "QUEUED", null, null));
        entityManager.flush();
        entityManager.clear();

        assertThat(service.dispatchQueryJson(picsureId)).isNull();
    }

    @Test
    void invalidStatusOnSaveThrowsBadRequest() {
        SaveQueryRequest req = new SaveQueryRequest("{}", null, "NOT_A_REAL_STATUS", null, null);
        assertThatThrownBy(() -> service.save(req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void invalidBase64MetadataOnSaveThrowsBadRequest() {
        SaveQueryRequest req = new SaveQueryRequest("{}", null, "QUEUED", null, "not-valid-base64!!!");
        assertThatThrownBy(() -> service.save(req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void invalidBase64MetadataOnUpdateThrowsBadRequest() {
        UUID picsureId = service.save(new SaveQueryRequest("{}", null, "QUEUED", null, null));
        entityManager.flush();
        entityManager.clear();

        UpdateQueryRequest req = new UpdateQueryRequest(null, null, "not-valid-base64!!!", null, null);
        assertThatThrownBy(() -> service.update(picsureId, req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void savedStatusEnumRoundTripsThroughOrdinalMapping() {
        for (PicSureStatus status : PicSureStatus.values()) {
            UUID picsureId = service.save(new SaveQueryRequest("{}", null, status.name(), null, null));
            entityManager.flush();
            entityManager.clear();

            assertThat(service.get(picsureId).status()).isEqualTo(status.name());
        }
    }

    @Test
    void saveWithIdStoresOwnerAndConsents() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        UUID saved = service
            .save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs2.c1", "phs1.c2"), TOPMED, List.of("phs9.c1")), "FIRST_SUBMIT"));
        entityManager.flush();
        entityManager.clear();

        assertThat(saved).isEqualTo(picsureId);
        StoredQuery stored = service.get(picsureId);
        assertThat(stored.picsureId()).isEqualTo(picsureId);
        assertThat(stored.userUuid()).isEqualTo(owner);
        assertThat(stored.consents())
            .containsExactlyInAnyOrderEntriesOf(Map.of(CONSENTS, List.of("phs1.c2", "phs2.c1"), TOPMED, List.of("phs9.c1")));
        assertThat(stored.query()).isEqualTo("{\"q\":1}");
        assertThat(stored.resourceResultId()).isEqualTo(picsureId.toString());
        assertThat(stored.status()).isEqualTo("QUEUED");
        assertThat(stored.startTime()).isNotNull();
        assertThat(stored.runId()).isNotNull();
    }

    @Test
    void saveReadsTheIdFromTheRequest() {
        UUID picsureId = UUID.randomUUID();

        UUID saved = service.save(owned(picsureId, UUID.randomUUID(), Map.of(), "FIRST_SUBMIT"));

        assertThat(saved).isEqualTo(picsureId);
        assertThat(repo.findById(picsureId)).isPresent();
    }

    @Test
    void userUuidIsNeverUpdatedAfterInsert() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT"));
        entityManager.flush();
        entityManager.clear();

        Query loaded = repo.findById(picsureId).orElseThrow();
        loaded.setUserUuid(someoneElse);
        loaded.setStatus(PicSureStatus.ERROR);
        repo.save(loaded);
        entityManager.flush();
        entityManager.clear();

        Query reloaded = repo.findById(picsureId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PicSureStatus.ERROR);
        assertThat(reloaded.getUserUuid()).isEqualTo(owner);
    }

    @Test
    void resaveUpdatesConsentsAndResetsReadyTimeButKeepsBody() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(
            new SaveQueryRequest(
                "{\"q\":1}", "first-result", "QUEUED", "3", encoded("first"), picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")),
                "FIRST_SUBMIT", UUID.randomUUID(), false, "COUNT"
            )
        );
        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        entityManager.flush();
        entityManager.clear();
        StoredQuery ready = service.get(picsureId);
        assertThat(ready.readyTime()).isNotNull();
        entityManager.clear();

        service.save(
            new SaveQueryRequest(
                "{\"q\":2}", "second-result", "PENDING", "4", encoded("second"), picsureId, owner,
                Map.of(CONSENTS, List.of("phs3.c1", "phs1.c1")), "CONSENT_CHANGE", UUID.randomUUID(), false, "COUNT"
            )
        );
        entityManager.flush();
        entityManager.clear();

        StoredQuery stored = service.get(picsureId);
        assertThat(repo.count()).isEqualTo(1);
        assertThat(stored.consents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1", "phs3.c1")));
        assertThat(stored.readyTime()).isNull();
        assertThat(stored.status()).isEqualTo("PENDING");
        assertThat(new String(Base64.getDecoder().decode(stored.metadata()))).isEqualTo("second");
        assertThat(stored.startTime()).isGreaterThanOrEqualTo(ready.startTime());
        assertThat(stored.query()).isEqualTo("{\"q\":1}");
        assertThat(stored.version()).isEqualTo("3");
        assertThat(stored.resourceResultId()).isEqualTo("first-result");
        assertThat(stored.userUuid()).isEqualTo(owner);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentFirstSavesOfOneIdLeaveOneRow() throws Exception {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        try {
            List<UUID> saved = RacingFirstSaves.run(
                repo, runRepo, generalQueryRepo, transactionManager, picsureId,
                owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT")
            );

            assertThat(saved).containsExactly(picsureId, picsureId);
            assertThat(repo.count()).isEqualTo(1);
            assertThat(generalQueryRepo.count()).isEqualTo(1);
            StoredQuery stored = service.get(picsureId);
            assertThat(stored.userUuid()).isEqualTo(owner);
            assertThat(stored.query()).isEqualTo("{\"q\":1}");
            assertThat(stored.consents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1")));
            assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId)).hasSize(2);
        } finally {
            runRepo.deleteAll();
            repo.deleteAll();
            generalQueryRepo.deleteAll();
        }
    }

    @Test
    void saveWithTriggerAppendsAQueryRunRow() {
        UUID picsureId = UUID.randomUUID();
        Instant before = Instant.now().minusMillis(1);

        service.save(owned(picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs2.c1", "phs1.c1")), "FIRST_SUBMIT"));
        entityManager.flush();
        entityManager.clear();

        assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId)).singleElement().satisfies(run -> {
            assertThat(run.getRunId()).isNotNull();
            assertThat(run.getQueryUuid()).isEqualTo(picsureId);
            assertThat(run.getConsents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")));
            assertThat(run.getTrigger()).isEqualTo(RunTrigger.FIRST_SUBMIT);
            assertThat(run.getSubmittedAt()).isBetween(before, Instant.now().plusMillis(1));
            assertThat(run.getCompletedAt()).isNull();
            assertThat(run.getStatus()).isNull();
            assertThat(service.get(picsureId).runId()).isEqualTo(run.getRunId());
        });
    }

    @Test
    void twoRunsUnderDifferentConsentsAreBothKept() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")), "FIRST_SUBMIT"));
        entityManager.flush();
        entityManager.clear();

        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "CONSENT_CHANGE"));
        entityManager.flush();
        entityManager.clear();

        List<QueryRun> runs = runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId);
        assertThat(runs).extracting(QueryRun::getTrigger).containsExactly(RunTrigger.FIRST_SUBMIT, RunTrigger.CONSENT_CHANGE);
        assertThat(runs.get(0).getConsents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")));
        assertThat(runs.get(1).getConsents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1")));
        assertThat(runs.get(1).getSubmittedAt()).isAfter(runs.get(0).getSubmittedAt());
        StoredQuery stored = service.get(picsureId);
        assertThat(stored.consents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1")));
        assertThat(stored.runId()).isEqualTo(runs.get(1).getRunId());
    }

    @Test
    void fiveRunsSubmittedAtOneInstantAreKeptOneMillisecondApartInSubmitOrder() {
        Instant instant = Instant.parse("2026-03-04T05:06:07.008Z");
        QueryPersistenceService stoppedClock =
            new QueryPersistenceService(repo, runRepo, generalQueryRepo, transactionManager, Clock.fixed(instant, ZoneOffset.UTC));
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Map<String, List<String>> consents = Map.of(CONSENTS, List.of("phs1.c1"));
        List<String> triggers = List.of("FIRST_SUBMIT", "RESUBMIT", "LOST_RESULT", "CONSENT_CHANGE", "RESUBMIT");
        for (String trigger : triggers) {
            stoppedClock.save(owned(picsureId, owner, consents, trigger));
        }
        entityManager.flush();
        entityManager.clear();

        List<QueryRun> runs = runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId);
        assertThat(runs).extracting(QueryRun::getSubmittedAt)
            .containsExactly(instant, instant.plusMillis(1), instant.plusMillis(2), instant.plusMillis(3), instant.plusMillis(4));
        assertThat(runs).extracting(run -> run.getTrigger().name()).containsExactlyElementsOf(triggers);
        assertThat(service.get(picsureId).runId()).isEqualTo(runs.getLast().getRunId());
    }

    @Test
    void runSubmittedBeforeTheClockPassesTheLatestRunStillBecomesTheLatest() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Map<String, List<String>> consents = Map.of(CONSENTS, List.of("phs1.c1"));
        service.save(owned(picsureId, owner, consents, "FIRST_SUBMIT"));
        Instant ahead = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
        QueryRun aheadOfTheClock =
            runRepo.saveAndFlush(new QueryRun(UUID.randomUUID(), picsureId, consents, RunTrigger.LOST_RESULT, ahead));

        service.save(owned(picsureId, owner, consents, "RESUBMIT"));
        entityManager.flush();
        entityManager.clear();

        List<QueryRun> runs = runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId);
        assertThat(runs).hasSize(3);
        assertThat(runs.get(1).getRunId()).isEqualTo(aheadOfTheClock.getRunId());
        assertThat(runs.getLast().getTrigger()).isEqualTo(RunTrigger.RESUBMIT);
        assertThat(runs.getLast().getSubmittedAt()).isEqualTo(ahead.plusMillis(1));
        assertThat(service.get(picsureId).runId()).isEqualTo(runs.getLast().getRunId());
    }

    @Test
    void terminalStatusCompletesTheLatestRunOnce() {
        UUID picsureId = UUID.randomUUID();
        service.save(owned(picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT"));
        entityManager.flush();
        entityManager.clear();
        UUID runId = service.get(picsureId).runId();

        service.update(picsureId, new UpdateQueryRequest("PENDING", null, null, null, null, runId));
        entityManager.flush();
        entityManager.clear();
        assertThat(runRepo.findById(runId).orElseThrow().getCompletedAt()).isNull();
        assertThat(runRepo.findById(runId).orElseThrow().getStatus()).isNull();

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null, runId));
        entityManager.flush();
        entityManager.clear();
        QueryRun completed = runRepo.findById(runId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(completed.getCompletedAt()).isNotNull();
        entityManager.clear();

        service.update(picsureId, new UpdateQueryRequest("ERROR", null, null, null, null, runId));
        entityManager.flush();
        entityManager.clear();
        QueryRun afterSecondTerminal = runRepo.findById(runId).orElseThrow();
        assertThat(afterSecondTerminal.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(afterSecondTerminal.getCompletedAt()).isEqualTo(completed.getCompletedAt());
        assertThat(runRepo.count()).isEqualTo(1);
    }

    @Test
    void errorStatusCompletesTheLatestRun() {
        UUID picsureId = UUID.randomUUID();
        service.save(owned(picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT"));
        entityManager.flush();
        entityManager.clear();
        UUID runId = service.get(picsureId).runId();

        service.update(picsureId, new UpdateQueryRequest("ERROR", null, null, null, null, runId));
        entityManager.flush();
        entityManager.clear();

        QueryRun completed = runRepo.findById(runId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PicSureStatus.ERROR);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    void updateWithoutARunIdCompletesTheLatestRunOnATerminalStatus() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")), "FIRST_SUBMIT"));
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "CONSENT_CHANGE"));
        entityManager.flush();
        entityManager.clear();

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        entityManager.flush();
        entityManager.clear();

        List<QueryRun> runs = runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId);
        assertThat(runs).hasSize(2);
        assertThat(runs.getFirst().getStatus()).isNull();
        assertThat(runs.getFirst().getCompletedAt()).isNull();
        assertThat(runs.getLast().getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(runs.getLast().getCompletedAt()).isNotNull();
        StoredQuery stored = service.get(picsureId);
        assertThat(stored.status()).isEqualTo("AVAILABLE");
        assertThat(stored.readyTime()).isNotNull();
    }

    @Test
    void legacySaveWithoutIdOrOwnerBehavesAsBefore() {
        UUID first = service.save(new SaveQueryRequest("{\"q\":1}", "rr-1", "QUEUED", "3", encoded("meta")));
        UUID second = service.save(new SaveQueryRequest("{\"q\":1}", "rr-1", "QUEUED", "3", encoded("meta")));
        entityManager.flush();
        entityManager.clear();

        assertThat(first).isNotNull().isNotEqualTo(second);
        StoredQuery stored = service.get(first);
        assertThat(stored.userUuid()).isNull();
        assertThat(stored.consents()).isNull();
        assertThat(stored.runId()).isNull();
        assertThat(stored.query()).isEqualTo("{\"q\":1}");
        assertThat(stored.resourceResultId()).isEqualTo("rr-1");
        assertThat(stored.status()).isEqualTo("QUEUED");
        assertThat(stored.version()).isEqualTo("3");
        assertThat(runRepo.count()).isZero();
        entityManager.clear();

        service.update(first, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(first).status()).isEqualTo("AVAILABLE");
        assertThat(service.get(first).readyTime()).isNotNull();
        assertThat(runRepo.count()).isZero();
    }

    @Test
    void unknownTriggerOnSaveThrowsBadRequestAndStoresNothing() {
        UUID picsureId = UUID.randomUUID();
        SaveQueryRequest req = owned(picsureId, UUID.randomUUID(), Map.of(), "NOT_A_TRIGGER");

        assertThatThrownBy(() -> service.save(req)).isInstanceOf(PicsureException.class)
            .satisfies(e -> assertThat(((PicsureException) e).getStatus().value()).isEqualTo(400));
        assertThat(runRepo.count()).isZero();
    }

    @Test
    void firstOwnedSaveCreatesTheGeneralQueryAndFilesTheRow() {
        Instant instant = Instant.parse("2026-03-04T05:06:07.008Z");
        QueryPersistenceService stoppedClock =
            new QueryPersistenceService(repo, runRepo, generalQueryRepo, transactionManager, Clock.fixed(instant, ZoneOffset.UTC));
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();

        stoppedClock.save(variant(picsureId, owner, general, true, "DATAFRAME"));
        entityManager.flush();
        entityManager.clear();

        assertThat(generalQueryRepo.count()).isEqualTo(1);
        QueryToVariant storedGeneral = generalQueryRepo.findById(general).orElseThrow();
        assertThat(storedGeneral.getUuid()).isEqualTo(general);
        assertThat(storedGeneral.getUserUuid()).isEqualTo(owner);
        assertThat(storedGeneral.getCreatedAt()).isEqualTo(instant);
        Query row = repo.findById(picsureId).orElseThrow();
        assertThat(row.getUserUuid()).isEqualTo(owner);
        assertThat(row.getQueryToVariantUuid()).isEqualTo(general);
        assertThat(row.isSample()).isTrue();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME");
        assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId)).hasSize(1);
    }

    @Test
    void secondVariantReusesTheGeneralQuery() {
        Instant firstSubmit = Instant.parse("2026-03-04T05:06:07.008Z");
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        UUID sampleRow = UUID.randomUUID();
        UUID fullRow = UUID.randomUUID();
        UUID pfbRow = UUID.randomUUID();
        new QueryPersistenceService(repo, runRepo, generalQueryRepo, transactionManager, Clock.fixed(firstSubmit, ZoneOffset.UTC))
            .save(variant(sampleRow, owner, general, true, "DATAFRAME"));
        entityManager.flush();
        entityManager.clear();

        QueryPersistenceService anHourLater = new QueryPersistenceService(
            repo, runRepo, generalQueryRepo, transactionManager, Clock.fixed(firstSubmit.plusSeconds(3600), ZoneOffset.UTC)
        );
        anHourLater.save(variant(fullRow, owner, general, false, "DATAFRAME"));
        anHourLater.save(variant(pfbRow, owner, general, false, "DATAFRAME_PFB"));
        entityManager.flush();
        entityManager.clear();

        assertThat(generalQueryRepo.count()).isEqualTo(1);
        assertThat(generalQueryRepo.findById(general).orElseThrow().getCreatedAt()).isEqualTo(firstSubmit);
        assertThat(repo.findAllById(List.of(sampleRow, fullRow, pfbRow))).hasSize(3)
            .allSatisfy(row -> assertThat(row.getQueryToVariantUuid()).isEqualTo(general));
        assertThat(repo.findById(sampleRow).orElseThrow().isSample()).isTrue();
        assertThat(repo.findById(fullRow).orElseThrow().isSample()).isFalse();
        assertThat(repo.findById(fullRow).orElseThrow().getResultType()).isEqualTo("DATAFRAME");
        assertThat(repo.findById(pfbRow).orElseThrow().isSample()).isFalse();
        assertThat(repo.findById(pfbRow).orElseThrow().getResultType()).isEqualTo("DATAFRAME_PFB");
    }

    @Test
    void resaveNeverRewritesTheGeneralQuerySampleOrResultType() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        UUID anotherGeneral = UUID.randomUUID();
        service.save(variant(picsureId, owner, general, true, "DATAFRAME"));
        entityManager.flush();
        entityManager.clear();

        service.save(
            new SaveQueryRequest(
                "{\"q\":1}", picsureId.toString(), "PENDING", "3", null, picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "RESUBMIT",
                anotherGeneral, false, "DATAFRAME_PFB"
            )
        );
        entityManager.flush();
        entityManager.clear();

        Query row = repo.findById(picsureId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PicSureStatus.PENDING);
        assertThat(row.getQueryToVariantUuid()).isEqualTo(general);
        assertThat(row.isSample()).isTrue();
        assertThat(row.getResultType()).isEqualTo("DATAFRAME");
        assertThat(generalQueryRepo.findById(anotherGeneral)).isEmpty();
        assertThat(generalQueryRepo.count()).isEqualTo(1);
        assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(picsureId)).hasSize(2);
    }

    @Test
    void generalQueryOfAnotherOwnerIs409AndStoresNothing() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        UUID ownersRow = UUID.randomUUID();
        UUID strangersRow = UUID.randomUUID();
        service.save(variant(ownersRow, owner, general, false, "DATAFRAME"));
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> service.save(variant(strangersRow, stranger, general, true, "DATAFRAME")))
            .isInstanceOfSatisfying(PicsureException.class, refusal -> {
                assertThat(refusal.getStatus().value()).isEqualTo(409);
                assertThat(refusal.getErrorType()).isEqualTo("conflict");
                assertThat(refusal).hasMessage("General query " + general + " belongs to a different owner");
            });

        assertThat(repo.findById(strangersRow)).isEmpty();
        assertThat(repo.count()).isEqualTo(1);
        assertThat(runRepo.findByQueryUuidOrderBySubmittedAtAsc(strangersRow)).isEmpty();
        assertThat(generalQueryRepo.count()).isEqualTo(1);
        assertThat(generalQueryRepo.findById(general).orElseThrow().getUserUuid()).isEqualTo(owner);
    }

    @Test
    void generalIdWithoutAnOwnerIs400() {
        SaveQueryRequest ownerless =
            new SaveQueryRequest("{\"q\":1}", "rr", "QUEUED", "3", null, null, null, null, null, UUID.randomUUID(), false, "COUNT");

        assertThatThrownBy(() -> service.save(ownerless)).isInstanceOfSatisfying(PicsureException.class, refusal -> {
            assertThat(refusal.getStatus().value()).isEqualTo(400);
            assertThat(refusal)
                .hasMessage("picsureId, userUuid, consents, trigger and queryToVariantUuid must be sent together or not at all");
        });

        assertThat(repo.count()).isZero();
        assertThat(generalQueryRepo.count()).isZero();
    }

    @Test
    void ownedSaveWithoutAGeneralIdIs400() {
        UUID picsureId = UUID.randomUUID();
        SaveQueryRequest withoutAGeneralId = new SaveQueryRequest(
            "{\"q\":1}", picsureId.toString(), "QUEUED", "3", null, picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs1.c1")),
            "FIRST_SUBMIT", null, false, "COUNT"
        );

        assertThatThrownBy(() -> service.save(withoutAGeneralId)).isInstanceOfSatisfying(PicsureException.class, refusal -> {
            assertThat(refusal.getStatus().value()).isEqualTo(400);
            assertThat(refusal)
                .hasMessage("picsureId, userUuid, consents, trigger and queryToVariantUuid must be sent together or not at all");
        });

        assertThat(repo.count()).isZero();
        assertThat(runRepo.count()).isZero();
        assertThat(generalQueryRepo.count()).isZero();
    }

    @Test
    void legacySaveStoresNoGeneralQuery() {
        UUID picsureId = service.save(new SaveQueryRequest("{\"q\":1}", "rr", "QUEUED", "3", null));
        entityManager.flush();
        entityManager.clear();

        Query row = repo.findById(picsureId).orElseThrow();
        assertThat(row.getUserUuid()).isNull();
        assertThat(row.getQueryToVariantUuid()).isNull();
        assertThat(row.isSample()).isFalse();
        assertThat(row.getResultType()).isNull();
        assertThat(generalQueryRepo.count()).isZero();
    }

    @Test
    void everyResultTypeNameIsAcceptedOnAnOwnedAndAnOwnerlessSave() {
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        for (ResultType resultType : ResultType.values()) {
            UUID ownedRow = UUID.randomUUID();
            service.save(variant(ownedRow, owner, general, false, resultType.name()));
            UUID ownerlessRow = service
                .save(new SaveQueryRequest("{\"q\":1}", "rr", "QUEUED", "3", null, null, null, null, null, null, false, resultType.name()));
            entityManager.flush();
            entityManager.clear();

            assertThat(repo.findById(ownedRow).orElseThrow().getResultType()).isEqualTo(resultType.name());
            assertThat(repo.findById(ownerlessRow).orElseThrow().getResultType()).isEqualTo(resultType.name());
        }
        assertThat(repo.count()).isEqualTo(2L * ResultType.values().length);
        assertThat(generalQueryRepo.count()).isEqualTo(1);
    }

    @Test
    void ownedSaveWithoutAResultTypeIsStoredWithNone() {
        UUID picsureId = UUID.randomUUID();

        service.save(variant(picsureId, UUID.randomUUID(), UUID.randomUUID(), true, null));
        entityManager.flush();
        entityManager.clear();

        Query row = repo.findById(picsureId).orElseThrow();
        assertThat(row.getResultType()).isNull();
        assertThat(row.isSample()).isTrue();
        assertThat(row.getQueryToVariantUuid()).isNotNull();
    }

    private static SaveQueryRequest owned(UUID picsureId, UUID owner, Map<String, List<String>> consents, String trigger) {
        return new SaveQueryRequest(
            "{\"q\":1}", picsureId.toString(), "QUEUED", "3", null, picsureId, owner, consents, trigger, UUID.randomUUID(), false, "COUNT"
        );
    }

    private static SaveQueryRequest variant(UUID picsureId, UUID owner, UUID general, boolean sample, String resultType) {
        return new SaveQueryRequest(
            "{\"q\":1}", picsureId.toString(), "QUEUED", "3", null, picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT",
            general, sample, resultType
        );
    }

    private static String encoded(String metadata) {
        return Base64.getEncoder().encodeToString(metadata.getBytes());
    }
}
