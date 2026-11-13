package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.operations.query.ResultTypeBackfill.Ending;
import edu.harvard.hms.dbmi.avillach.operations.query.ResultTypeBackfill.Summary;

/**
 * Runs the result type pass against the schema the migrations build, on a real MySQL, over rows inserted with the statement a stack that
 * does not know the new columns uses. Hibernate creates nothing here, so the paging statements and the guarded update run against the
 * column types, the collation and the id order of the deployed database, which the H2 tests cannot show.
 *
 * <p>The pass runs on the test thread and nothing runs inside a test transaction: every write the pass makes is committed by its own
 * statement. The pass the application would start after startup is turned off for tests. Every test has a time limit, so a pass that never
 * reaches the end of the table fails its test instead of holding the build.
 *
 * <p>The migration set in this module has no {@code query.version} column, which the entity maps. Deployed databases get it from a separate
 * baseline, so the test adds it after the scripts.
 */
@SpringBootTest(
    properties = {"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect", "spring.jpa.hibernate.ddl-auto=none"}
)
@Testcontainers(disabledWithoutDocker = true)
@Timeout(ResultTypeBackfillMySqlTest.TEST_SECONDS)
class ResultTypeBackfillMySqlTest {

    static final long TEST_SECONDS = 60;

    private static final Path MIGRATIONS = Path.of("db", "sql");
    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final String V3_SAMPLE_PFB = "{\"query\":{\"select\":[],\"expectedResultType\":\"DATAFRAME_PFB\",\"sample\":true}}";
    private static final String V3_COUNT = "{\"query\":{\"select\":[],\"expectedResultType\":\"COUNT\"}}";
    private static final String LEGACY_DATAFRAME =
        "{\"categoryFilters\":{\"sex\":[\"Female\"]},\"fields\":[\"age\"],\"expectedResultType\":\"DATAFRAME\"}";
    private static final String UPDATE_METHOD = "setResultTypeWhereAbsent";
    private static final String EVERY_COLUMN_BUT_THE_RESULT_TYPE =
        "SELECT HEX(uuid) AS id, HEX(query) AS body, readyTime, resourceResultId,"
            + " startTime, status, HEX(resourceId) AS resource, HEX(metadata) AS metadata, version, HEX(user_uuid) AS owner,"
            + " CAST(consents AS CHAR) AS consents, HEX(query_to_variant_uuid) AS general_query, CAST(sample AS UNSIGNED) AS sample"
            + " FROM query ORDER BY uuid";

    @Container
    static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.4").withDatabaseName("picsure").withUrlParam("allowMultiQueries", "true");

    @Autowired
    private QueryRepository queryRepository;

    @Autowired
    private QueryPersistenceService service;

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
                files.filter(file -> versionOf(file) > 0).sorted(Comparator.comparingInt(ResultTypeBackfillMySqlTest::versionOf)).toList();
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
        jdbc.update("DELETE FROM named_dataset");
        jdbc.update("DELETE FROM query");
        jdbc.update("DELETE FROM query_to_variant");
    }

    @Test
    void fillsRowsAnOlderStackInsertedAndWritesNoOtherColumn() {
        UUID lowestId = insertedByAnOlderStack(new UUID(0L, 1L), gzip(V3_SAMPLE_PFB), "3");
        UUID highestId = insertedByAnOlderStack(new UUID(-1L, -1L), gzip(LEGACY_DATAFRAME), null);
        UUID aboveTheSignBit = insertedByAnOlderStack(new UUID(Long.MIN_VALUE, 0L), gzip(V3_COUNT), "3");
        UUID belowTheSignBit = insertedByAnOlderStack(new UUID(Long.MAX_VALUE, -1L), gzip(LEGACY_DATAFRAME), "2");
        UUID notGzip = insertedByAnOlderStack(UUID.randomUUID(), V3_COUNT.getBytes(StandardCharsets.UTF_8), "3");
        UUID notJson = insertedByAnOlderStack(UUID.randomUUID(), gzip("not json"), "3");
        UUID withoutAType = insertedByAnOlderStack(UUID.randomUUID(), gzip("{\"query\":{\"select\":[]}}"), "3");
        UUID withoutABody = insertedByAnOlderStack(UUID.randomUUID(), null, null);
        UUID random = insertedByAnOlderStack(UUID.randomUUID(), gzip(V3_COUNT), "3");
        List<Map<String, Object>> before = jdbc.queryForList(EVERY_COLUMN_BUT_THE_RESULT_TYPE);

        Summary first = backfill(queryRepository).runPass();
        Summary second = backfill(queryRepository).runPass();

        assertThat(first).isEqualTo(new Summary(5, 2, 0, 2, 0, Ending.COMPLETE));
        assertThat(second).isEqualTo(new Summary(0, 2, 0, 2, 0, Ending.COMPLETE));
        assertThat(typeOf(lowestId)).isEqualTo("DATAFRAME_PFB");
        assertThat(typeOf(highestId)).isEqualTo("DATAFRAME");
        assertThat(typeOf(aboveTheSignBit)).isEqualTo("COUNT");
        assertThat(typeOf(belowTheSignBit)).isEqualTo("DATAFRAME");
        assertThat(typeOf(random)).isEqualTo("COUNT");
        assertThat(List.of(notGzip, notJson, withoutAType, withoutABody)).allSatisfy(row -> assertThat(typeOf(row)).isNull());
        assertThat(jdbc.queryForList(EVERY_COLUMN_BUT_THE_RESULT_TYPE)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_to_variant", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run", Integer.class)).isZero();
    }

    @Test
    void aRowTypedByAnotherWriterBetweenTheReadAndTheWriteIsNotOverwritten() {
        UUID raced = insertedByAnOlderStack(new UUID(0L, 1L), gzip(V3_SAMPLE_PFB), "3");
        UUID quiet = insertedByAnOlderStack(new UUID(0L, 2L), gzip(V3_COUNT), "3");
        QueryRepository typedByAnotherWriterBeforeTheWrite =
            InterceptedRepository.of(QueryRepository.class, queryRepository, (method, call) -> {
                if (UPDATE_METHOD.equals(method)) {
                    jdbc.update("UPDATE query SET result_type = 'DATAFRAME' WHERE uuid = ? AND result_type IS NULL", bytes(raced));
                }
                return call.proceed();
            });

        Summary summary = backfill(typedByAnotherWriterBeforeTheWrite).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 1, 0, 0, Ending.COMPLETE));
        assertThat(typeOf(raced)).isEqualTo("DATAFRAME");
        assertThat(typeOf(quiet)).isEqualTo("COUNT");
    }

    @Test
    void aStatusUpdateBetweenTheReadAndTheWriteIsKeptOnMySql() {
        UUID row = insertedByAnOlderStack(UUID.randomUUID(), gzip(V3_COUNT), "3");
        QueryRepository updatedBeforeTheWrite = InterceptedRepository.of(QueryRepository.class, queryRepository, (method, call) -> {
            if (UPDATE_METHOD.equals(method)) {
                service.update(row, new UpdateQueryRequest("AVAILABLE", "result-after-the-read", null, null, null));
            }
            return call.proceed();
        });

        Summary summary = backfill(updatedBeforeTheWrite).runPass();

        assertThat(summary).isEqualTo(new Summary(1, 0, 0, 0, 0, Ending.COMPLETE));
        Map<String, Object> stored =
            jdbc.queryForMap("SELECT status, readyTime, resourceResultId, result_type FROM query WHERE uuid = ?", bytes(row));
        assertThat(stored.get("result_type")).isEqualTo("COUNT");
        assertThat(((Number) stored.get("status")).intValue()).isEqualTo(PicSureStatus.AVAILABLE.ordinal());
        assertThat(stored.get("readyTime")).isNotNull();
        assertThat(stored.get("resourceResultId")).isEqualTo("result-after-the-read");
    }

    /**
     * Has the database itself refuse one write. The variant key allows a general query one row for each pair of sample flag and result
     * type, and compares no nulls, so a row without a result type can sit under a general query that already holds a typed row of the same
     * sample flag. Giving that row the same type would make a second row of one variant, and MySQL refuses the update for a duplicate key.
     */
    @Test
    void aWriteTheVariantKeyRefusesIsCountedLeavesTheRowAndThePassGoesOn() {
        UUID generalQuery = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO query_to_variant (uuid, user_uuid, created_at) VALUES (?, ?, '2026-01-02 03:04:05.678')", bytes(generalQuery),
            bytes(owner)
        );
        UUID refused = insertedUnderAGeneralQuery(new UUID(0L, 1L), generalQuery, owner, gzip(V3_COUNT), null);
        UUID typedOnTheSamePage = insertedByAnOlderStack(new UUID(0L, 2L), gzip(V3_SAMPLE_PFB), "3");
        UUID typedOnTheNextPage = insertedByAnOlderStack(new UUID(0L, 3L), gzip(LEGACY_DATAFRAME), null);
        UUID holdsTheVariant = insertedUnderAGeneralQuery(new UUID(0L, 4L), generalQuery, owner, gzip(V3_COUNT), "COUNT");
        List<Map<String, Object>> before = jdbc.queryForList(EVERY_COLUMN_BUT_THE_RESULT_TYPE);
        List<Throwable> failedWrites = new ArrayList<>();
        QueryRepository recordingFailedWrites = InterceptedRepository.of(QueryRepository.class, queryRepository, (method, call) -> {
            try {
                return call.proceed();
            } catch (Throwable failure) {
                if (UPDATE_METHOD.equals(method)) {
                    failedWrites.add(failure);
                }
                throw failure;
            }
        });

        Summary summary = backfill(recordingFailedWrites).runPass();

        assertThat(summary).isEqualTo(new Summary(2, 0, 0, 0, 1, Ending.COMPLETE));
        assertThat(failedWrites).singleElement().isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(failure -> assertThat(failure).hasStackTraceContaining("unique_query_variant"));
        assertThat(typeOf(refused)).isNull();
        assertThat(typeOf(holdsTheVariant)).isEqualTo("COUNT");
        assertThat(typeOf(typedOnTheSamePage)).isEqualTo("DATAFRAME_PFB");
        assertThat(typeOf(typedOnTheNextPage)).isEqualTo("DATAFRAME");
        assertThat(jdbc.queryForList(EVERY_COLUMN_BUT_THE_RESULT_TYPE)).isEqualTo(before);
        assertThat(backfill(queryRepository).runPass()).isEqualTo(new Summary(0, 0, 0, 0, 1, Ending.COMPLETE));
    }

    private static ResultTypeBackfill backfill(QueryRepository queries) {
        return new ResultTypeBackfill(queries, new ResultTypeBackfillProperties(true, 2, Duration.ZERO));
    }

    /**
     * Inserts a row with the columns the table had before this release and no others, so the new columns take their defaults.
     *
     * @param id the id of the row
     * @param body the stored bytes of the request body, or null for none
     * @param version the version to store, or null for none
     * @return the id of the row
     */
    private UUID insertedByAnOlderStack(UUID id, byte[] body, String version) {
        jdbc.update(
            "INSERT INTO query (uuid, query, readyTime, resourceResultId, startTime, status, resourceId, metadata, version)"
                + " VALUES (?, ?, NULL, ?, '2026-01-02 03:04:05.678', 0, NULL, NULL, ?)",
            bytes(id), body, id.toString(), version
        );
        return id;
    }

    /**
     * Inserts a full-result v3 row that has an owner and is filed under a general query, which must be stored already.
     *
     * @param id the id of the row
     * @param generalQuery the id of the general query the row is a variant of
     * @param owner the UUID of the user the row and the general query belong to
     * @param body the stored bytes of the request body
     * @param resultType the result type to store, or null for none
     * @return the id of the row
     */
    private UUID insertedUnderAGeneralQuery(UUID id, UUID generalQuery, UUID owner, byte[] body, String resultType) {
        jdbc.update(
            "INSERT INTO query (uuid, query, readyTime, resourceResultId, startTime, status, resourceId, metadata, version, user_uuid,"
                + " query_to_variant_uuid, sample, result_type)"
                + " VALUES (?, ?, NULL, ?, '2026-01-02 03:04:05.678', 0, NULL, NULL, '3', ?, ?, 0, ?)",
            bytes(id), body, id.toString(), bytes(owner), bytes(generalQuery), resultType
        );
        return id;
    }

    private String typeOf(UUID id) {
        return jdbc.queryForObject("SELECT result_type FROM query WHERE uuid = ?", String.class, (Object) bytes(id));
    }

    private static byte[] gzip(String body) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int versionOf(Path file) {
        Matcher matcher = MIGRATION_FILE.matcher(file.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
