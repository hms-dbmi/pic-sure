package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.QueryIdentity;
import edu.harvard.hms.dbmi.avillach.operations.query.InterceptedRepository;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryFixtures;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryPersistenceService;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

/**
 * Runs the named dataset service against the schema the migrations build, on a real MySQL. Hibernate creates nothing here, so a mapping of
 * {@code named_dataset.query_to_variant_uuid}, or of the variant columns of {@code query} the service lists, that names a column or type
 * the scripts do not have fails these tests. The other named dataset tests run on a schema Hibernate generates from the entities, which has
 * no foreign key from {@code named_dataset} or {@code query} to {@code query_to_variant}, and whose ordering of ids and of missing result
 * types is H2's and not MySQL's.
 *
 * <p>Nothing here runs inside a test transaction: each service call commits or rolls back as it does in the deployed service.
 *
 * <p>The step that files datasets under a general query on a read is off in this context, as it is by default, so the reads of the other
 * tests write nothing. The tests of that step build a service with the step turned on.
 *
 * <p>The migration set in this module has no {@code query.version} column, which the entity maps. Deployed databases get it from a separate
 * baseline, so the test adds it after the scripts.
 */
@SpringBootTest(
    properties = {"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect", "spring.jpa.hibernate.ddl-auto=none"}
)
@Testcontainers(disabledWithoutDocker = true)
class NamedDatasetMySqlTest {

    private static final Path MIGRATIONS = Path.of("db", "sql");
    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final String ALICE_EMAIL = "alice@example.org";
    private static final UUID ALICE_ID = UUID.fromString("5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11");
    private static final GatewayUser ALICE = new GatewayUser(ALICE_ID.toString(), "auth0|alice", ALICE_EMAIL, null, Set.of());
    private static final UUID BOB_ID = UUID.fromString("7c1b1f0e-2a3b-4c4d-8e5f-0a1b2c3d4e5f");

    /** A stored request of a stack without query owners: a v3 dataframe query that carries the consent values of its run. */
    private static final String COHORT_BODY =
        "{\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],\"authorizationFilters\":[{\"conceptPath\":\"\\\\_consents\\\\\","
            + "\"values\":[\"phs001.c1\"]}],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME\"}}";

    @Container
    static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.4").withDatabaseName("picsure").withUrlParam("allowMultiQueries", "true");

    @Autowired
    private NamedDatasetService service;

    @Autowired
    private QueryRepository queryRepository;

    @Autowired
    private NamedDatasetRepository datasetRepository;

    @Autowired
    private QueryPersistenceService queryPersistence;

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
            scripts = files.filter(file -> versionOf(file) > 0).sorted(Comparator.comparingInt(NamedDatasetMySqlTest::versionOf)).toList();
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
    void emptyTables() {
        jdbc.update("DELETE FROM query_run");
        jdbc.update("DELETE FROM named_dataset");
        jdbc.update("DELETE FROM query");
        jdbc.update("DELETE FROM query_to_variant");
    }

    @Test
    void createStoresQueryIdAndTheGeneralQueryInTheMigratedColumns() {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));

        NamedDatasetDto created = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null));

        assertThat(created.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        assertThat(queryIdAndGeneralQueryOf(created.uuid())).containsEntry("queryId", bytes(full.getUuid()))
            .containsEntry("query_to_variant_uuid", bytes(general));
    }

    @Test
    void createOnAnOwnerlessQueryStoresNoGeneralQuery() {
        Query ownerless = stored(QueryFixtures.withRandomId().setQuery("{\"query\":{\"select\":[]}}").setVersion("3"));

        NamedDatasetDto created = service.create(ALICE, new NamedDatasetRequestDto(ownerless.getUuid(), "cohort", false, null));

        assertThat(created.queries()).isEmpty();
        assertThat(queryIdAndGeneralQueryOf(created.uuid())).containsEntry("queryId", bytes(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", null);
        assertThat(service.getForUser(ALICE, created.uuid()).queries()).isEmpty();
    }

    @Test
    void repointStoresQueryIdAndTheGeneralQueryInTheMigratedColumns() {
        UUID general = generalQueryOf(ALICE_ID);
        UUID otherGeneral = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        Query count = stored(QueryFixtures.variant(ALICE_ID, otherGeneral, false, "COUNT"));
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        NamedDatasetDto repointed = service.update(ALICE, dataset, new NamedDatasetRequestDto(count.getUuid(), "renamed", true, null));

        assertThat(repointed.query().uuid()).isEqualTo(count.getUuid());
        assertThat(repointed.queries()).containsExactly(new LinkedQueryDto(count.getUuid(), false, "COUNT"));
        assertThat(service.getForUser(ALICE, dataset).queries()).isEqualTo(repointed.queries());
        assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(count.getUuid()))
            .containsEntry("query_to_variant_uuid", bytes(otherGeneral));
        assertThat(nameArchivedFlagAndMetadataOf(dataset)).containsEntry("name", "renamed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(2);
    }

    @Test
    void repointOntoAnOwnerlessQueryClearsTheGeneralQueryColumn() {
        Query full = stored(QueryFixtures.variant(ALICE_ID, generalQueryOf(ALICE_ID), false, "DATAFRAME"));
        Query ownerless = stored(QueryFixtures.withRandomId().setQuery("{\"query\":{\"select\":[]}}").setVersion("3"));
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        NamedDatasetDto repointed = service.update(ALICE, dataset, new NamedDatasetRequestDto(ownerless.getUuid(), "cohort", false, null));

        assertThat(repointed.queries()).isEmpty();
        assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", null);
    }

    @Test
    void putWithTheSameQueryFillsInAGeneralQueryTheDatasetWasStoredWithout() {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        UUID dataset = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO named_dataset (uuid, queryId, user, name, archived, metadata) VALUES (?, ?, ?, 'cohort', 0, NULL)", bytes(dataset),
            bytes(full.getUuid()), ALICE_EMAIL
        );
        assertThat(service.getForUser(ALICE, dataset).queries()).isEmpty();

        NamedDatasetDto updated = service.update(ALICE, dataset, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null));

        assertThat(updated.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(full.getUuid()))
            .containsEntry("query_to_variant_uuid", bytes(general));
    }

    @Test
    void readListsTheCallersVariantsBySampleThenResultTypeThenId() {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        Query pfb = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME_PFB"));
        Query sample = stored(QueryFixtures.variant(ALICE_ID, general, true, "DATAFRAME"));
        Query untypedWithAHighId =
            stored(withId("ffffffff-0000-4000-8000-000000000001", QueryFixtures.variant(ALICE_ID, general, true, null)));
        Query untypedWithALowId =
            stored(withId("00000000-0000-4000-8000-000000000002", QueryFixtures.variant(ALICE_ID, general, true, null)));
        stored(QueryFixtures.variant(BOB_ID, general, false, "COUNT"));
        stored(QueryFixtures.variant(ALICE_ID, generalQueryOf(ALICE_ID), false, "COUNT"));
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        List<LinkedQueryDto> expected = List.of(
            new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(pfb.getUuid(), false, "DATAFRAME_PFB"),
            new LinkedQueryDto(untypedWithALowId.getUuid(), true, null), new LinkedQueryDto(untypedWithAHighId.getUuid(), true, null),
            new LinkedQueryDto(sample.getUuid(), true, "DATAFRAME")
        );
        assertThat(service.getForUser(ALICE, dataset).queries()).isEqualTo(expected);
        assertThat(service.listForUser(ALICE)).singleElement().extracting(NamedDatasetDto::queries).isEqualTo(expected);
    }

    @Test
    void readListsOnlyTheCallersRowsForEachOfTwoUsersWhoShareAnEmail() {
        GatewayUser bobWithAlicesEmail = new GatewayUser(BOB_ID.toString(), "auth0|bob", ALICE_EMAIL, null, Set.of());
        UUID general = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        assertThat(service.getForUser(ALICE, dataset).queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        assertThat(service.getForUser(bobWithAlicesEmail, dataset).queries()).isEmpty();
        assertThat(service.listForUser(bobWithAlicesEmail)).singleElement().extracting(NamedDatasetDto::queries).isEqualTo(List.of());
    }

    @Test
    void createOnAnotherUsersQueryIs403AndStoresNoRow() {
        Query bobs = stored(QueryFixtures.variant(BOB_ID, generalQueryOf(BOB_ID), false, "DATAFRAME"));

        assertThatThrownBy(() -> service.create(ALICE, new NamedDatasetRequestDto(bobs.getUuid(), "cohort", false, null)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM named_dataset", Integer.class)).isZero();
    }

    @Test
    void putOntoAnotherUsersQueryWithARenameIs403AndWritesNeitherTheQueryIdNorTheName() {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        Query bobs = stored(QueryFixtures.variant(BOB_ID, generalQueryOf(BOB_ID), true, "DATAFRAME"));
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, Map.of("color", "blue"))).uuid();
        Map<String, Object> before = nameArchivedFlagAndMetadataOf(dataset);

        assertThatThrownBy(
            () -> service.update(ALICE, dataset, new NamedDatasetRequestDto(bobs.getUuid(), "renamed", true, Map.of("color", "red")))
        ).isInstanceOfSatisfying(PicsureException.class, refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(nameArchivedFlagAndMetadataOf(dataset)).isEqualTo(before).containsEntry("name", "cohort");
        assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(full.getUuid()))
            .containsEntry("query_to_variant_uuid", bytes(general));
    }

    @Test
    void createOfASecondDatasetOnAQueryTheCallerAlreadyNamedStoresBoth() {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        UUID first = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        NamedDatasetDto second = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "the same cohort", false, null));

        assertThat(second.uuid()).isNotEqualTo(first);
        assertThat(second.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        assertThat(List.of(first, second.uuid())).allSatisfy(
            dataset -> assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(full.getUuid()))
                .containsEntry("query_to_variant_uuid", bytes(general))
        );
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM named_dataset WHERE queryId = ? AND user = ?", Integer.class, bytes(full.getUuid()), ALICE_EMAIL
            )
        ).isEqualTo(2);
        assertThat(service.listForUser(ALICE)).extracting(NamedDatasetDto::name).containsExactlyInAnyOrder("cohort", "the same cohort");
    }

    @Test
    void createOfASecondDatasetOnAnOwnerlessQueryTheCallerAlreadyNamedStoresBoth() {
        Query ownerless = stored(QueryFixtures.withRandomId().setQuery("{\"query\":{\"select\":[]}}").setVersion("3"));
        service.create(ALICE, new NamedDatasetRequestDto(ownerless.getUuid(), "cohort", false, null));

        service.create(ALICE, new NamedDatasetRequestDto(ownerless.getUuid(), "the same cohort", false, null));

        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM named_dataset WHERE queryId = ? AND user = ?", Integer.class, bytes(ownerless.getUuid()), ALICE_EMAIL
            )
        ).isEqualTo(2);
    }

    @Test
    void putOntoAQueryAnotherDatasetOfTheCallerNamesMovesTheDataset() {
        UUID general = generalQueryOf(ALICE_ID);
        UUID countGeneral = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        Query count = stored(QueryFixtures.variant(ALICE_ID, countGeneral, false, "COUNT"));
        UUID onCount = service.create(ALICE, new NamedDatasetRequestDto(count.getUuid(), "counts", false, null)).uuid();
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        NamedDatasetDto moved = service.update(ALICE, dataset, new NamedDatasetRequestDto(count.getUuid(), "renamed", false, null));

        assertThat(moved.query().uuid()).isEqualTo(count.getUuid());
        assertThat(moved.queries()).containsExactly(new LinkedQueryDto(count.getUuid(), false, "COUNT"));
        assertThat(nameArchivedFlagAndMetadataOf(dataset)).containsEntry("name", "renamed");
        assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(count.getUuid()))
            .containsEntry("query_to_variant_uuid", bytes(countGeneral));
        assertThat(queryIdAndGeneralQueryOf(onCount)).containsEntry("queryId", bytes(count.getUuid()))
            .containsEntry("query_to_variant_uuid", bytes(countGeneral));
        assertThat(nameArchivedFlagAndMetadataOf(onCount)).containsEntry("name", "counts");
    }

    /**
     * Two datasets of one user on one query row with no owner are both filed under the general query derived from its stored query, and
     * both stay on that row.
     */
    @Test
    void twoDatasetsOfOneUserOnOneOwnerlessRowAreBothFiledUnderItsGeneralQuery() {
        Query ownerless = stored(QueryFixtures.withRandomId().setQuery(COHORT_BODY).setVersion("3"));
        UUID one = datasetOfAliceStoredBeforeGeneralQueries(ownerless, "cohort");
        UUID other = datasetOfAliceStoredBeforeGeneralQueries(ownerless, "the same cohort");

        readingWithTheStepTurnedOn(datasetRepository).listForUser(ALICE);

        byte[] general = jdbc.queryForObject("SELECT uuid FROM query_to_variant", byte[].class);
        assertThat(List.of(one, other)).allSatisfy(
            dataset -> assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(ownerless.getUuid()))
                .containsEntry("query_to_variant_uuid", general)
        );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(1);
    }

    @Test
    void deletingADatasetKeepsItsQueriesAndItsGeneralQuery() {
        UUID general = generalQueryOf(ALICE_ID);
        Query full = stored(QueryFixtures.variant(ALICE_ID, general, false, "DATAFRAME"));
        stored(QueryFixtures.variant(ALICE_ID, general, true, "DATAFRAME"));
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        service.delete(ALICE, dataset);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM named_dataset WHERE uuid = ?", Integer.class, bytes(dataset))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_to_variant", Integer.class)).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @ExtendWith(OutputCaptureExtension.class)
    void twoListsAtOnceBringADatasetOverOnce(CapturedOutput output) throws Exception {
        edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query cohort = new edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query(
            List.of("\\demographics\\AGE\\"), List.of(), null, List.of(), ResultType.DATAFRAME, null, null
        );
        UUID generalId = QueryIdentity.generalIdFor(ALICE_ID, cohort);
        Query ownerless = stored(QueryFixtures.withRandomId().setQuery(COHORT_BODY).setVersion("3"));
        UUID dataset = datasetOfAliceStoredBeforeGeneralQueries(ownerless, "cohort");
        CyclicBarrier bothReadTheDatasetBeforeEitherLockedIt = new CyclicBarrier(2);
        AtomicInteger lockAttempts = new AtomicInteger();
        NamedDatasetRepository heldBeforeTheLock =
            InterceptedRepository.of(NamedDatasetRepository.class, datasetRepository, (methodName, call) -> {
                if ("findLockedByUuidAndUser".equals(methodName) && lockAttempts.incrementAndGet() <= 2) {
                    bothReadTheDatasetBeforeEitherLockedIt.await(20, TimeUnit.SECONDS);
                }
                return call.proceed();
            });
        NamedDatasetService racing = readingWithTheStepTurnedOn(heldBeforeTheLock);

        List<List<NamedDatasetDto>> answers;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<NamedDatasetDto>> one = executor.submit(() -> racing.listForUser(ALICE));
            Future<List<NamedDatasetDto>> other = executor.submit(() -> racing.listForUser(ALICE));
            answers = List.of(one.get(60, TimeUnit.SECONDS), other.get(60, TimeUnit.SECONDS));
        }

        assertThat(lockAttempts).hasValue(2);
        assertThat(answers).allSatisfy(answer -> assertThat(answer).singleElement().satisfies(listed -> {
            assertThat(listed.uuid()).isEqualTo(dataset);
            assertThat(listed.query().uuid()).isEqualTo(ownerless.getUuid());
            assertThat(listed.queries()).isEmpty();
        }));
        assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(ownerless.getUuid()))
            .containsEntry("query_to_variant_uuid", bytes(generalId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_to_variant", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_uuid FROM query_to_variant WHERE uuid = ?", byte[].class, bytes(generalId)))
            .isEqualTo(bytes(ALICE_ID));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT user_uuid FROM query WHERE uuid = ?", byte[].class, bytes(ownerless.getUuid()))).isNull();
        assertThat(output.getAll().split("Brought dataset ", -1)).hasSize(2);
        assertThat(output.getAll()).doesNotContain("Could not bring dataset");
    }

    /**
     * Two datasets of one user whose stored queries are the same query derive the same general query. Both are filed under it, each stays
     * on its own query row, and the general query is stored once.
     */
    @Test
    @Timeout(120)
    @ExtendWith(OutputCaptureExtension.class)
    void twoDatasetsOfOneUserOnTheSameStoredQueryAreFiledUnderOneGeneralQuery(CapturedOutput output) {
        Query one = stored(QueryFixtures.withRandomId().setQuery(COHORT_BODY).setVersion("3"));
        Query other = stored(QueryFixtures.withRandomId().setQuery(COHORT_BODY).setVersion("3"));
        UUID datasetOnOne = datasetOfAliceStoredBeforeGeneralQueries(one, "cohort");
        UUID datasetOnOther = datasetOfAliceStoredBeforeGeneralQueries(other, "the same cohort");
        NamedDatasetService reading = readingWithTheStepTurnedOn(datasetRepository);

        List<NamedDatasetDto> listed = reading.listForUser(ALICE);

        assertThat(listed).extracting(NamedDatasetDto::uuid).containsExactlyInAnyOrder(datasetOnOne, datasetOnOther);
        byte[] general = jdbc.queryForObject("SELECT uuid FROM query_to_variant", byte[].class);
        assertThat(queryIdAndGeneralQueryOf(datasetOnOne)).containsEntry("queryId", bytes(one.getUuid()))
            .containsEntry("query_to_variant_uuid", general);
        assertThat(queryIdAndGeneralQueryOf(datasetOnOther)).containsEntry("queryId", bytes(other.getUuid()))
            .containsEntry("query_to_variant_uuid", general);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query WHERE user_uuid IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run", Integer.class)).isZero();
        Map<String, List<Map<String, Object>>> afterTheFirst = everyRow();

        assertThat(reading.listForUser(ALICE)).isEqualTo(listed);

        assertThat(everyRow()).isEqualTo(afterTheFirst);
        assertThat(output.getAll().split("Brought dataset ", -1)).hasSize(3);
        assertThat(output.getAll()).doesNotContain("Could not bring dataset").doesNotContain("Duplicate entry").doesNotContain(ALICE_EMAIL);
    }

    /**
     * A stack that does not know the general query column stores and moves datasets with the columns it knows. A dataset it moved keeps the
     * general query of the query it pointed at before, and the next read by its user files it under the one of its query now.
     */
    @Test
    void aDatasetAnOlderStackMovedIsFiledUnderTheGeneralQueryOfItsQueryOnTheNextRead() {
        Query cohort = stored(QueryFixtures.withRandomId().setQuery(COHORT_BODY).setVersion("3"));
        Query count = stored(
            QueryFixtures.withRandomId().setQuery("{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"COUNT\"}}")
                .setVersion("3")
        );
        UUID dataset = datasetOfAliceStoredBeforeGeneralQueries(cohort, "cohort");
        NamedDatasetService reading = readingWithTheStepTurnedOn(datasetRepository);
        reading.getForUser(ALICE, dataset);
        Object generalOfTheCohort = queryIdAndGeneralQueryOf(dataset).get("query_to_variant_uuid");
        jdbc.update("UPDATE named_dataset SET queryId = ?, name = 'counts' WHERE uuid = ?", bytes(count.getUuid()), bytes(dataset));

        NamedDatasetDto read = reading.getForUser(ALICE, dataset);

        Object generalOfTheCount = queryIdAndGeneralQueryOf(dataset).get("query_to_variant_uuid");
        assertThat(read.query().uuid()).isEqualTo(count.getUuid());
        assertThat(generalOfTheCohort).isNotNull();
        assertThat(generalOfTheCount).isNotNull().isNotEqualTo(generalOfTheCohort);
        assertThat(queryIdAndGeneralQueryOf(dataset)).containsEntry("queryId", bytes(count.getUuid()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_to_variant WHERE user_uuid = ?", Integer.class, bytes(ALICE_ID)))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(2);
    }

    private NamedDatasetService readingWithTheStepTurnedOn(NamedDatasetRepository datasets) {
        DatasetBringOver turnedOn =
            new DatasetBringOver(datasets, queryPersistence, transactionManager, new DatasetBringOverProperties(true));
        return new NamedDatasetService(datasets, queryRepository, new NamedDatasetMapper(), turnedOn, transactionManager);
    }

    private Map<String, Object> nameArchivedFlagAndMetadataOf(UUID dataset) {
        return jdbc
            .queryForMap("SELECT name, CAST(archived AS UNSIGNED) AS archived, metadata FROM named_dataset WHERE uuid = ?", bytes(dataset));
    }

    private Map<String, Object> queryIdAndGeneralQueryOf(UUID dataset) {
        return jdbc.queryForMap("SELECT queryId, query_to_variant_uuid FROM named_dataset WHERE uuid = ?", bytes(dataset));
    }

    private UUID generalQueryOf(UUID owner) {
        return QueryFixtures.storedGeneralQuery(jdbc, owner);
    }

    private Query stored(Query query) {
        return queryRepository.save(query);
    }

    private static Query withId(String id, Query query) {
        query.setUuid(UUID.fromString(id));
        return query;
    }

    /** Stores a dataset of Alice on a query row the way a release without general queries stored one: with no general query. */
    private UUID datasetOfAliceStoredBeforeGeneralQueries(Query row, String name) {
        UUID dataset = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO named_dataset (uuid, queryId, user, name, archived, metadata) VALUES (?, ?, ?, ?, 0, NULL)", bytes(dataset),
            bytes(row.getUuid()), ALICE_EMAIL, name
        );
        return dataset;
    }

    /** Reads every row of the four tables a dataset read can write, binary values as hex text, so that two states can be compared. */
    private Map<String, List<Map<String, Object>>> everyRow() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("named_dataset", "query", "query_to_variant", "query_run")) {
            String orderedById = "query_run".equals(table) ? "run_id" : "uuid";
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + orderedById).stream().map(row -> {
                Map<String, Object> comparable = new LinkedHashMap<>();
                row.forEach(
                    (column, value) -> comparable.put(column, value instanceof byte[] binary ? HexFormat.of().formatHex(binary) : value)
                );
                return comparable;
            }).toList());
        }
        return rows;
    }

    private static int versionOf(Path file) {
        Matcher matcher = MIGRATION_FILE.matcher(file.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
