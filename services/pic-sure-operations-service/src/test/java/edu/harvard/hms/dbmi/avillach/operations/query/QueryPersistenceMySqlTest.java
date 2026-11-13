package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * Runs the query persistence path against the schema the migrations build, on a real MySQL. Hibernate creates nothing here: the tables come
 * from the migration scripts, so a mapping that names a column, type or nullability the scripts do not have fails these tests. The other
 * persistence tests run on a schema Hibernate generates from the entities and cannot show that.
 *
 * <p>The migration set in this module has no {@code query.version} column, which the entity maps. Deployed databases get it from a separate
 * baseline, so the test adds it after the scripts.
 */
@SpringBootTest(
    properties = {"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect", "spring.jpa.hibernate.ddl-auto=none"}
)
@Testcontainers(disabledWithoutDocker = true)
class QueryPersistenceMySqlTest {

    private static final Path MIGRATIONS = Path.of("db", "sql");
    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final String CONSENTS = "\\_consents\\";

    @Container
    static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.4").withDatabaseName("picsure").withUrlParam("allowMultiQueries", "true");

    @Autowired
    private QueryPersistenceService service;

    @Autowired
    private QueryRepository queryRepository;

    @Autowired
    private QueryRunRepository runRepository;

    @Autowired
    private QueryToVariantRepository generalQueryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private LoggingClient loggingClient;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @BeforeAll
    static void buildSchemaFromMigrations() throws Exception {
        List<Path> scripts;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            scripts =
                files.filter(file -> versionOf(file) > 0).sorted(Comparator.comparingInt(QueryPersistenceMySqlTest::versionOf)).toList();
        }
        try (
            Connection connection =
                DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); Statement statement =
                    connection.createStatement()
        ) {
            for (Path script : scripts) {
                statement.execute(Files.readString(script));
                while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                    statement.getUpdateCount();
                }
            }
            statement.execute("ALTER TABLE `picsure`.`query` ADD COLUMN `version` varchar(255) NULL");
        }
    }

    @BeforeEach
    void emptyQueryTables() {
        jdbc.update("DELETE FROM query_run");
        jdbc.update("DELETE FROM named_dataset_query");
        jdbc.update("DELETE FROM named_dataset");
        jdbc.update("DELETE FROM query");
        jdbc.update("DELETE FROM query_to_variant");
    }

    @Test
    void ownedRowAndItsRunAreStoredInTheMigratedColumns() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs2.c1", "phs1.c1")), "FIRST_SUBMIT"));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT user_uuid, JSON_TYPE(consents) AS consents_type, CAST(consents AS CHAR) AS consents, status FROM query WHERE uuid = ?",
            bytes(picsureId)
        );
        assertThat(row.get("user_uuid")).isEqualTo(bytes(owner));
        assertThat(row.get("consents_type")).isEqualTo("OBJECT");
        assertThat(row.get("consents")).isEqualTo("{\"\\\\_consents\\\\\": [\"phs1.c1\", \"phs2.c1\"]}");
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(PicSureStatus.QUEUED.ordinal());

        Map<String, Object> run = jdbc.queryForMap(
            "SELECT run_id, JSON_TYPE(consents) AS consents_type, CAST(consents AS CHAR) AS consents, `trigger`, completed_at, status,"
                + " TIMESTAMPDIFF(SECOND, submitted_at, UTC_TIMESTAMP(3)) AS seconds_since_submit FROM query_run WHERE query_uuid = ?",
            bytes(picsureId)
        );
        assertThat(run.get("consents_type")).isEqualTo("OBJECT");
        assertThat(run.get("consents")).isEqualTo("{\"\\\\_consents\\\\\": [\"phs1.c1\", \"phs2.c1\"]}");
        assertThat(run.get("trigger")).isEqualTo("FIRST_SUBMIT");
        assertThat(run.get("completed_at")).isNull();
        assertThat(run.get("status")).isNull();
        assertThat(((Number) run.get("seconds_since_submit")).longValue()).isBetween(-5L, 60L);

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.userUuid()).isEqualTo(owner);
        assertThat(stored.consents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")));
        assertThat(run.get("run_id")).isEqualTo(bytes(stored.runId()));
    }

    @Test
    void terminalStatusFillsTheRunColumnsWithTheOrdinalStatus() {
        UUID picsureId = UUID.randomUUID();
        service.save(owned(picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT"));
        UUID runId = service.get(picsureId).runId();

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null, runId));

        Map<String, Object> run = jdbc.queryForMap("SELECT completed_at, status FROM query_run WHERE run_id = ?", bytes(runId));
        assertThat(run.get("completed_at")).isNotNull();
        assertThat(((Number) run.get("status")).intValue()).isEqualTo(PicSureStatus.AVAILABLE.ordinal());
        QueryRun loaded = runRepository.findById(runId).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(loaded.getCompletedAt()).isAfterOrEqualTo(loaded.getSubmittedAt());
    }

    @Test
    void resaveOnMySqlKeepsOwnerAndBodyAndAppendsASecondRun() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")), "FIRST_SUBMIT"));

        service.save(
            new SaveQueryRequest(
                "{\"q\":2}", "other", "PENDING", "4", null, picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "CONSENT_CHANGE",
                UUID.randomUUID(), false, "COUNT"
            )
        );

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(1);
        StoredQuery stored = service.get(picsureId);
        assertThat(stored.userUuid()).isEqualTo(owner);
        assertThat(stored.query()).isEqualTo("{\"q\":1}");
        assertThat(stored.consents()).containsExactlyEntriesOf(Map.of(CONSENTS, List.of("phs1.c1")));
        List<QueryRun> runs = runRepository.findByQueryUuidOrderBySubmittedAtAsc(picsureId);
        assertThat(runs).extracting(QueryRun::getTrigger).containsExactly(RunTrigger.FIRST_SUBMIT, RunTrigger.CONSENT_CHANGE);
        assertThat(stored.runId()).isEqualTo(runs.get(1).getRunId());
    }

    @Test
    void rowWrittenWithoutTheNewColumnsLoadsAsOwnerless() {
        UUID picsureId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO query (uuid, query, readyTime, resourceResultId, startTime, status, resourceId, metadata)"
                + " VALUES (?, NULL, NULL, ?, NULL, 0, NULL, NULL)",
            bytes(picsureId), picsureId.toString()
        );

        StoredQuery stored = service.get(picsureId);
        assertThat(stored.userUuid()).isNull();
        assertThat(stored.consents()).isNull();
        assertThat(stored.runId()).isNull();

        service.update(picsureId, new UpdateQueryRequest("AVAILABLE", null, null, null, null));
        assertThat(service.get(picsureId).status()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run", Integer.class)).isZero();
    }

    @Test
    void legacySaveLeavesTheNewColumnsNull() {
        UUID picsureId = service.save(new SaveQueryRequest("{\"q\":1}", "rr", "QUEUED", "3", null));

        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM query WHERE uuid = ? AND user_uuid IS NULL AND consents IS NULL"
                    + " AND query_to_variant_uuid IS NULL AND sample = 0 AND result_type IS NULL",
                Integer.class, bytes(picsureId)
            )
        ).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_to_variant", Integer.class)).isZero();
    }

    @Test
    void concurrentFirstSavesOfOneIdLeaveOneRow() throws Exception {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        List<UUID> saved = RacingFirstSaves.run(
            queryRepository, runRepository, generalQueryRepository, transactionManager, picsureId,
            owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT")
        );

        assertThat(saved).containsExactly(picsureId, picsureId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(1);
        assertThat(service.get(picsureId).userUuid()).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run WHERE query_uuid = ?", Integer.class, bytes(picsureId)))
            .isEqualTo(2);
    }

    @Test
    void concurrentFirstSavesByDifferentOwnersKeepTheWinnerAndRefuseTheLoser() throws Exception {
        UUID picsureId = UUID.randomUUID();
        List<SaveQueryRequest> requests = List.of(
            owned(picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")), "FIRST_SUBMIT"),
            owned(picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs1.c1")), "FIRST_SUBMIT")
        );

        List<SaveOutcome> outcomes = RacingFirstSaves
            .run(queryRepository, runRepository, generalQueryRepository, transactionManager, picsureId, requests.get(0), requests.get(1));

        ConcurrentSaveAssertions.assertOneOwnerWon(
            picsureId, requests, outcomes, service.get(picsureId), runRepository.findByQueryUuidOrderBySubmittedAtAsc(picsureId)
        );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_to_variant", Integer.class)).isEqualTo(1);
    }

    @Test
    void updateThatReadTheRowBeforeAConsentChangeSaveDoesNotOverwriteIt() throws Exception {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")), "FIRST_SUBMIT"));
        UUID firstRun = service.get(picsureId).runId();

        boolean savedWhileTheUpdateWasHeld = UpdateHeldAcrossASave.run(
            queryRepository, runRepository, generalQueryRepository, transactionManager, picsureId,
            new UpdateQueryRequest("AVAILABLE", null, null, null, null, firstRun),
            new SaveQueryRequest(
                "{\"q\":1}", picsureId.toString(), "PENDING", "3", null, picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")),
                "CONSENT_CHANGE", UUID.randomUUID(), false, "COUNT"
            )
        );

        ConcurrentSaveAssertions
            .assertTheSecondRunStands(firstRun, service.get(picsureId), runRepository.findByQueryUuidOrderBySubmittedAtAsc(picsureId));
        assertThat(savedWhileTheUpdateWasHeld).isFalse();
    }

    @Test
    void concurrentResavesGetDistinctSubmitTimesAndTheRowKeepsTheLatestRunsConsents() throws Exception {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Clock stopped = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs0.c1")), "FIRST_SUBMIT"));

        for (int round = 1; round <= 10; round++) {
            List<SaveOutcome> outcomes = RacingResaves.run(
                queryRepository, runRepository, generalQueryRepository, transactionManager, stopped,
                owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")), "CONSENT_CHANGE"),
                owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "CONSENT_CHANGE")
            );

            ConcurrentSaveAssertions.assertRunsAreOrdered(
                round, outcomes, service.get(picsureId), runRepository.findByQueryUuidOrderBySubmittedAtAsc(picsureId),
                runRepository.findFirstByQueryUuidOrderBySubmittedAtDesc(picsureId).orElseThrow()
            );
        }
    }

    @Test
    void saveFromADifferentOwnerAndUpdateForAnOlderRunAreRefusedOnMySql() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1", "phs2.c1")), "FIRST_SUBMIT"));
        UUID olderRun = service.get(picsureId).runId();
        service.save(owned(picsureId, owner, Map.of(CONSENTS, List.of("phs1.c1")), "CONSENT_CHANGE"));
        StoredQuery before = service.get(picsureId);

        assertThatThrownBy(() -> service.save(owned(picsureId, UUID.randomUUID(), Map.of(CONSENTS, List.of("phs9.c1")), "RESUBMIT")))
            .isInstanceOfSatisfying(PicsureException.class, refusal -> assertThat(refusal.getStatus().value()).isEqualTo(409));
        assertThatThrownBy(() -> service.update(picsureId, new UpdateQueryRequest("AVAILABLE", "rr-stale", null, null, null, olderRun)))
            .isInstanceOfSatisfying(PicsureException.class, refusal -> assertThat(refusal.getStatus().value()).isEqualTo(409));

        assertThat(service.get(picsureId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run WHERE query_uuid = ?", Integer.class, bytes(picsureId)))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run WHERE completed_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void firstOwnedSaveFilesTheRowUnderItsGeneralQueryInTheMigratedColumns() {
        UUID picsureId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();

        service.save(variant(picsureId, owner, general, true, "DATAFRAME"));

        Map<String, Object> generalRow = jdbc.queryForMap(
            "SELECT uuid, user_uuid, TIMESTAMPDIFF(SECOND, created_at, UTC_TIMESTAMP(3)) AS seconds_since_created FROM query_to_variant"
        );
        assertThat(generalRow.get("uuid")).isEqualTo(bytes(general));
        assertThat(generalRow.get("user_uuid")).isEqualTo(bytes(owner));
        assertThat(((Number) generalRow.get("seconds_since_created")).longValue()).isBetween(-5L, 60L);
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT query_to_variant_uuid, CAST(sample AS UNSIGNED) AS sample, result_type FROM query WHERE uuid = ?", bytes(picsureId)
        );
        assertThat(row.get("query_to_variant_uuid")).isEqualTo(bytes(general));
        assertThat(((Number) row.get("sample")).intValue()).isEqualTo(1);
        assertThat(row.get("result_type")).isEqualTo("DATAFRAME");
        QueryToVariant loaded = generalQueryRepository.findById(general).orElseThrow();
        assertThat(loaded.getUserUuid()).isEqualTo(owner);
        assertThat(queryRepository.findById(picsureId).orElseThrow().getQueryToVariantUuid()).isEqualTo(general);
    }

    @Test
    void generalQueryOfAnotherOwnerIsRefusedOnMySqlAndStoresNothing() {
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        UUID strangersRow = UUID.randomUUID();
        service.save(variant(UUID.randomUUID(), owner, general, false, "DATAFRAME"));

        assertThatThrownBy(() -> service.save(variant(strangersRow, UUID.randomUUID(), general, true, "DATAFRAME")))
            .isInstanceOfSatisfying(PicsureException.class, refusal -> {
                assertThat(refusal.getStatus().value()).isEqualTo(409);
                assertThat(refusal).hasMessage("General query " + general + " belongs to a different owner");
            });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_uuid FROM query_to_variant", byte[].class)).isEqualTo(bytes(owner));
    }

    @Test
    void twoFirstSavesOfTwoVariantsAtOnceLeaveOneGeneralQueryAndTwoRows() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID general = UUID.randomUUID();
        UUID sampleRow = UUID.randomUUID();
        UUID fullRow = UUID.randomUUID();

        List<SaveOutcome> outcomes = RacingFirstSaves.runUnderTheirOwnIds(
            queryRepository, runRepository, generalQueryRepository, transactionManager,
            variant(sampleRow, owner, general, true, "DATAFRAME"), variant(fullRow, owner, general, false, "DATAFRAME")
        );

        assertThat(outcomes).extracting(SaveOutcome::failure).containsOnlyNulls();
        assertThat(outcomes).extracting(SaveOutcome::saved).containsExactly(sampleRow, fullRow);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_to_variant", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_uuid FROM query_to_variant WHERE uuid = ?", byte[].class, bytes(general)))
            .isEqualTo(bytes(owner));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(2);
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM query WHERE uuid = ? AND query_to_variant_uuid = ? AND sample = 1 AND result_type = 'DATAFRAME'",
                Integer.class, bytes(sampleRow), bytes(general)
            )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM query WHERE uuid = ? AND query_to_variant_uuid = ? AND sample = 0 AND result_type = 'DATAFRAME'",
                Integer.class, bytes(fullRow), bytes(general)
            )
        ).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run", Integer.class)).isEqualTo(2);
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

    private static int versionOf(Path file) {
        Matcher matcher = MIGRATION_FILE.matcher(file.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
