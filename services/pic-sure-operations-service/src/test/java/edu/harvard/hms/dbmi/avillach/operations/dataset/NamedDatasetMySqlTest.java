package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.query.Query;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryFixtures;
import edu.harvard.hms.dbmi.avillach.operations.query.QueryRepository;

/**
 * Runs named dataset linking against the schema the migrations build, on a real MySQL. Hibernate creates nothing here, so a mapping of
 * {@code named_dataset_query} that names a column, type or key the scripts do not have fails these tests. The other named dataset tests run
 * on a schema Hibernate generates from the entities, which has no foreign key from the join table to {@code named_dataset} and cannot show
 * what a delete does to the join rows.
 *
 * <p>Nothing here runs inside a test transaction: each service call commits or rolls back as it does in the deployed service. A dataset
 * that stands in for one the migration backfilled is written with SQL, with a null {@code result_type}, as the migration writes it.
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
    private static final String FULL_DATAFRAME =
        "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME\"}}";
    private static final String SAMPLE_DATAFRAME =
        "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"expectedResultType\":\"DATAFRAME\",\"sample\":true}}";

    @Container
    static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.4").withDatabaseName("picsure").withUrlParam("allowMultiQueries", "true");

    @Autowired
    private NamedDatasetService service;

    @Autowired
    private NamedDatasetQueryRepository linkRepository;

    @Autowired
    private QueryRepository queryRepository;

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
        jdbc.update("DELETE FROM named_dataset_query");
        jdbc.update("DELETE FROM named_dataset");
        jdbc.update("DELETE FROM query");
    }

    @Test
    void createStoresTheDatasetAndItsJoinRowInTheMigratedColumns() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);

        NamedDatasetDto created = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null));

        assertThat(created.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        assertThat(jdbc.queryForObject("SELECT queryId FROM named_dataset WHERE uuid = ?", byte[].class, bytes(created.uuid())))
            .isEqualTo(bytes(full.getUuid()));
        List<Map<String, Object>> rows = joinRowsOf(created.uuid());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("query_uuid")).isEqualTo(bytes(full.getUuid()));
        assertThat(((Number) rows.getFirst().get("sample")).intValue()).isZero();
        assertThat(rows.getFirst().get("result_type")).isEqualTo("DATAFRAME");
        assertThat(rows.getFirst().get("created_at")).isNotNull();
    }

    @Test
    void linkingASampleQueryAddsAJoinRowAndLeavesQueryIdOnTheFullRow() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();

        NamedDatasetDto linked = service.update(ALICE, dataset, new NamedDatasetRequestDto(sample.getUuid(), "cohort", false, null));

        assertThat(linked.query().uuid()).isEqualTo(full.getUuid());
        assertThat(linked.queries()).containsExactlyInAnyOrder(
            new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(sample.getUuid(), true, "DATAFRAME")
        );
        assertThat(service.getForUser(ALICE, dataset).queries()).isEqualTo(linked.queries());
        assertThat(jdbc.queryForObject("SELECT queryId FROM named_dataset WHERE uuid = ?", byte[].class, bytes(dataset)))
            .isEqualTo(bytes(full.getUuid()));
        assertThat(
            jdbc.queryForObject(
                "SELECT CAST(sample AS UNSIGNED) FROM named_dataset_query WHERE named_dataset_uuid = ? AND query_uuid = ?", Integer.class,
                bytes(dataset), bytes(sample.getUuid())
            )
        ).isEqualTo(1);
    }

    @Test
    void secondRowForTheSameVariantIs409AndTheVariantKeyRefusesTheRowItself() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        Query secondSample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();
        service.update(ALICE, dataset, new NamedDatasetRequestDto(sample.getUuid(), "cohort", false, null));

        assertThatThrownBy(() -> service.update(ALICE, dataset, new NamedDatasetRequestDto(secondSample.getUuid(), "cohort", false, null)))
            .isInstanceOfSatisfying(PicsureException.class, refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(
            () -> linkRepository.saveAndFlush(new NamedDatasetQuery(dataset, secondSample.getUuid(), true, "DATAFRAME", Instant.now()))
        ).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("unique_named_dataset_variant");

        assertThat(joinRowsOf(dataset)).hasSize(2);
    }

    @Test
    void backfilledJoinRowGetsItsResultTypeWrittenBackOnTheFirstRead() {
        Query full = storedQuery(null, FULL_DATAFRAME);
        UUID dataset = insertDatasetAsTheMigrationLeavesIt(full);

        NamedDatasetDto read = service.getForUser(ALICE, dataset);

        assertThat(read.queries()).containsExactly(new LinkedQueryDto(full.getUuid(), false, "DATAFRAME"));
        assertThat(joinRowsOf(dataset)).extracting(row -> row.get("result_type")).containsExactly("DATAFRAME");
        assertThat(service.listForUser(ALICE).getFirst().queries()).isEqualTo(read.queries());
    }

    @Test
    void writeBackOfADerivedResultTypeSurvivesAnExistingTypedRowForTheSameVariant() {
        Query backfilled = storedQuery(null, FULL_DATAFRAME);
        Query typed = storedQuery(ALICE_ID, FULL_DATAFRAME);
        UUID dataset = insertDatasetAsTheMigrationLeavesIt(backfilled);
        insertJoinRow(dataset, typed.getUuid(), false, "DATAFRAME", "2026-02-03 04:05:06.789");
        assertThatThrownBy(() -> linkRepository.fillResultType(dataset, backfilled.getUuid(), "DATAFRAME"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("unique_named_dataset_variant");

        NamedDatasetDto read = service.getForUser(ALICE, dataset);
        List<NamedDatasetDto> listed = service.listForUser(ALICE);

        List<LinkedQueryDto> bothAsDataframe =
            List.of(new LinkedQueryDto(backfilled.getUuid(), false, "DATAFRAME"), new LinkedQueryDto(typed.getUuid(), false, "DATAFRAME"));
        assertThat(read.queries()).isEqualTo(bothAsDataframe);
        assertThat(listed.getFirst().queries()).isEqualTo(bothAsDataframe);
        assertThat(joinRowsOf(dataset)).extracting(row -> row.get("result_type")).containsExactly(null, "DATAFRAME");
    }

    @Test
    void deletingADatasetRemovesItsJoinRowsAndKeepsTheirQueries() {
        Query full = storedQuery(ALICE_ID, FULL_DATAFRAME);
        Query sample = storedQuery(ALICE_ID, SAMPLE_DATAFRAME);
        UUID dataset = service.create(ALICE, new NamedDatasetRequestDto(full.getUuid(), "cohort", false, null)).uuid();
        service.update(ALICE, dataset, new NamedDatasetRequestDto(sample.getUuid(), "cohort", false, null));
        assertThat(joinRowsOf(dataset)).hasSize(2);

        service.delete(ALICE, dataset);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM named_dataset WHERE uuid = ?", Integer.class, bytes(dataset))).isZero();
        assertThat(joinRowsOf(dataset)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(2);
    }

    private Query storedQuery(UUID owner, String body) {
        Query query = QueryFixtures.withRandomId().setQuery(body).setVersion("3");
        query.setUserUuid(owner);
        return queryRepository.save(query);
    }

    private UUID insertDatasetAsTheMigrationLeavesIt(Query query) {
        UUID dataset = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO named_dataset (uuid, queryId, user, name, archived, metadata) VALUES (?, ?, ?, 'cohort', 0, NULL)", bytes(dataset),
            bytes(query.getUuid()), ALICE_EMAIL
        );
        insertJoinRow(dataset, query.getUuid(), false, null, "2026-01-02 03:04:05.678");
        return dataset;
    }

    private void insertJoinRow(UUID dataset, UUID query, boolean sample, String resultType, String createdAt) {
        jdbc.update(
            "INSERT INTO named_dataset_query (named_dataset_uuid, query_uuid, sample, result_type, created_at) VALUES (?, ?, ?, ?, ?)",
            bytes(dataset), bytes(query), sample, resultType, createdAt
        );
    }

    private List<Map<String, Object>> joinRowsOf(UUID dataset) {
        return jdbc.queryForList(
            "SELECT query_uuid, CAST(sample AS UNSIGNED) AS sample, result_type, created_at FROM named_dataset_query"
                + " WHERE named_dataset_uuid = ? ORDER BY created_at",
            bytes(dataset)
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
