package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies the operations migration set to a real MySQL and proves what V10 leaves behind. Each test starts from an empty schema, runs V1 to
 * V9 as whole scripts so the server sees them as a migration tool would send them, seeds two queries and the two named datasets that point
 * at them as the stack before V10 would have written them, and then runs V10. Statements that stand in for the stack before V10 name only
 * columns that existed in V9.
 */
@Testcontainers(disabledWithoutDocker = true)
class SampleDatasetMigrationMySqlTest {

    private static final Path MIGRATIONS = Path.of("db", "sql");
    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final int SAMPLE_DATASET_VERSION = 10;
    private static final String STARTED_QUERY_START_TIME = "2026-01-02 03:04:05.678";

    private static final UUID STARTED_QUERY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID UNSTARTED_QUERY = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID STARTED_DATASET = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID UNSTARTED_DATASET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Container
    static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.4").withDatabaseName("picsure").withUrlParam("allowMultiQueries", "true");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private String timeBeforeMigration;

    @BeforeEach
    void migrateSeededSchema() throws Exception {
        dataSource = new SingleConnectionDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP DATABASE IF EXISTS `picsure`");
        jdbc.execute("CREATE DATABASE `picsure`");
        jdbc.execute("USE `picsure`");
        for (int version = 1; version < SAMPLE_DATASET_VERSION; version++) {
            applyMigration(version);
        }
        insertQueryAsOldStack(STARTED_QUERY, STARTED_QUERY_START_TIME);
        insertQueryAsOldStack(UNSTARTED_QUERY, null);
        insertNamedDatasetAsOldStack(STARTED_DATASET, STARTED_QUERY, "first@example.org");
        insertNamedDatasetAsOldStack(UNSTARTED_DATASET, UNSTARTED_QUERY, "second@example.org");
        timeBeforeMigration = jdbc.queryForObject("SELECT CAST(NOW(3) AS CHAR)", String.class);
        applyMigration(SAMPLE_DATASET_VERSION);
    }

    @AfterEach
    void closeConnection() {
        dataSource.destroy();
    }

    @Test
    void v10AppliesOnTopOfV9() {
        assertThat(columnsOf("query")).contains("user_uuid", "consents");
        assertThat(columnsOf("query_run"))
            .containsExactly("run_id", "query_uuid", "consents", "trigger", "submitted_at", "completed_at", "status");
        assertThat(columnsOf("named_dataset_query"))
            .containsExactly("named_dataset_uuid", "query_uuid", "sample", "result_type", "created_at");
        assertThat(indexesOf("query")).contains("idx_query_user_uuid");
        assertThat(indexesOf("query_run")).containsExactlyInAnyOrder("PRIMARY", "idx_query_run_query_uuid");
        assertThat(indexesOf("named_dataset_query"))
            .containsExactlyInAnyOrder("PRIMARY", "unique_named_dataset_variant", "idx_named_dataset_query_query_uuid");
        assertThat(deleteRulesOf("query_run")).containsExactlyInAnyOrderEntriesOf(Map.of("fk_query_run_query", "NO ACTION"));
        assertThat(deleteRulesOf("named_dataset_query")).containsExactlyInAnyOrderEntriesOf(
            Map.of("fk_named_dataset_query_dataset", "CASCADE", "fk_named_dataset_query_query", "NO ACTION")
        );
    }

    @Test
    void v10KeepsNamedDatasetQueryIdRequired() {
        assertThat(
            jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = 'picsure' AND TABLE_NAME = 'named_dataset' AND COLUMN_NAME = 'queryId'",
                String.class
            )
        ).isEqualTo("NO");
        assertThat(deleteRulesOf("named_dataset")).containsOnlyKeys("foreign_queryId");
    }

    @Test
    void backfillCreatesOneJoinRowPerNamedDataset() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM named_dataset_query", Integer.class)).isEqualTo(2);

        Map<String, Object> started = joinRowOf(STARTED_DATASET);
        assertThat(started.get("query_uuid")).isEqualTo(bytes(STARTED_QUERY));
        assertThat(((Number) started.get("sample")).intValue()).isZero();
        assertThat(started.get("result_type")).isNull();
        assertThat(started.get("created_at")).isEqualTo(STARTED_QUERY_START_TIME);

        Map<String, Object> unstarted = joinRowOf(UNSTARTED_DATASET);
        assertThat(unstarted.get("query_uuid")).isEqualTo(bytes(UNSTARTED_QUERY));
        assertThat(((Number) unstarted.get("sample")).intValue()).isZero();
        assertThat(unstarted.get("result_type")).isNull();
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM named_dataset_query WHERE named_dataset_uuid = ? AND created_at BETWEEN ? AND NOW(3)", Integer.class,
                bytes(UNSTARTED_DATASET), timeBeforeMigration
            )
        ).isEqualTo(1);
    }

    @Test
    void backfillLeavesUserUuidNull() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query WHERE user_uuid IS NOT NULL OR consents IS NOT NULL", Integer.class))
            .isZero();
    }

    @Test
    void oldStackInsertWithoutNewColumnsStillWorks() {
        UUID query = UUID.randomUUID();
        UUID dataset = UUID.randomUUID();

        insertQueryAsOldStack(query, STARTED_QUERY_START_TIME);
        insertNamedDatasetAsOldStack(dataset, query, "third@example.org");

        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM query WHERE uuid = ? AND user_uuid IS NULL AND consents IS NULL", Integer.class, bytes(query)
            )
        ).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM named_dataset WHERE uuid = ?", Integer.class, bytes(dataset))).isEqualTo(1);
        assertThat(joinRowCountOf(dataset)).isZero();
    }

    @Test
    void oldStackDeleteOfBackfilledNamedDatasetRemovesItsJoinRow() {
        assertThat(joinRowCountOf(STARTED_DATASET)).isEqualTo(1);

        int deleted = jdbc.update("DELETE FROM named_dataset WHERE uuid = ?", bytes(STARTED_DATASET));

        assertThat(deleted).isEqualTo(1);
        assertThat(joinRowCountOf(STARTED_DATASET)).isZero();
        assertThat(joinRowCountOf(UNSTARTED_DATASET)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query WHERE uuid = ?", Integer.class, bytes(STARTED_QUERY))).isEqualTo(1);
    }

    @Test
    void variantUniqueKeyRejectsDuplicateTypedRow() {
        UUID firstSampleQuery = UUID.randomUUID();
        UUID secondSampleQuery = UUID.randomUUID();
        insertQueryAsOldStack(firstSampleQuery, null);
        insertQueryAsOldStack(secondSampleQuery, null);
        insertJoinRow(STARTED_DATASET, firstSampleQuery, true, "DATAFRAME");

        assertThatThrownBy(() -> insertJoinRow(STARTED_DATASET, secondSampleQuery, true, "DATAFRAME"))
            .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("unique_named_dataset_variant");

        insertJoinRow(STARTED_DATASET, secondSampleQuery, false, "DATAFRAME");
        assertThat(joinRowCountOf(STARTED_DATASET)).isEqualTo(3);
    }

    @Test
    void queryReferencedByJoinRowCannotBeDeleted() {
        UUID sampleQuery = UUID.randomUUID();
        insertQueryAsOldStack(sampleQuery, null);
        insertJoinRow(STARTED_DATASET, sampleQuery, true, "DATAFRAME");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM query WHERE uuid = ?", bytes(sampleQuery)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_named_dataset_query_query");

        assertThat(joinRowCountOf(STARTED_DATASET)).isEqualTo(2);
    }

    @Test
    void queryReferencedByQueryRunCannotBeDeleted() {
        UUID ranQuery = UUID.randomUUID();
        insertQueryAsOldStack(ranQuery, null);
        jdbc.update(
            "INSERT INTO query_run (run_id, query_uuid, `trigger`, submitted_at) VALUES (?, ?, 'SUBMIT', NOW(3))", bytes(UUID.randomUUID()),
            bytes(ranQuery)
        );

        assertThatThrownBy(() -> jdbc.update("DELETE FROM query WHERE uuid = ?", bytes(ranQuery)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_query_run_query");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_run WHERE query_uuid = ?", Integer.class, bytes(ranQuery))).isEqualTo(1);
    }

    private void applyMigration(int version) throws IOException {
        List<Path> matches;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            matches = files.filter(file -> versionOf(file) == version).toList();
        }
        assertThat(matches).as("migration files for version %s", version).hasSize(1);
        String script = Files.readString(matches.getFirst());
        jdbc.execute((Statement statement) -> {
            statement.execute(script);
            while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                statement.getUpdateCount();
            }
            return null;
        });
    }

    private static int versionOf(Path file) {
        Matcher matcher = MIGRATION_FILE.matcher(file.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private void insertQueryAsOldStack(UUID uuid, String startTime) {
        jdbc.update(
            "INSERT INTO query (uuid, query, readyTime, resourceResultId, startTime, status, resourceId, metadata)"
                + " VALUES (?, ?, NULL, ?, ?, 0, NULL, NULL)",
            bytes(uuid), new byte[] {1, 2, 3}, uuid.toString(), startTime
        );
    }

    private void insertNamedDatasetAsOldStack(UUID uuid, UUID query, String user) {
        jdbc.update(
            "INSERT INTO named_dataset (uuid, queryId, user, name, archived, metadata) VALUES (?, ?, ?, 'cohort', 0, NULL)", bytes(uuid),
            bytes(query), user
        );
    }

    private void insertJoinRow(UUID dataset, UUID query, boolean sample, String resultType) {
        jdbc.update(
            "INSERT INTO named_dataset_query (named_dataset_uuid, query_uuid, sample, result_type, created_at) VALUES (?, ?, ?, ?, NOW(3))",
            bytes(dataset), bytes(query), sample, resultType
        );
    }

    private Map<String, Object> joinRowOf(UUID dataset) {
        return jdbc.queryForMap(
            "SELECT query_uuid, CAST(sample AS UNSIGNED) AS sample, result_type, CAST(created_at AS CHAR) AS created_at"
                + " FROM named_dataset_query WHERE named_dataset_uuid = ?",
            bytes(dataset)
        );
    }

    private int joinRowCountOf(UUID dataset) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM named_dataset_query WHERE named_dataset_uuid = ?", Integer.class, bytes(dataset));
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'picsure' AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
            String.class, table
        );
    }

    private List<String> indexesOf(String table) {
        return jdbc.queryForList(
            "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'picsure' AND TABLE_NAME = ?", String.class,
            table
        );
    }

    private Map<String, String> deleteRulesOf(String table) {
        return jdbc.query(
            "SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = 'picsure' AND TABLE_NAME = ?",
            rs -> {
                Map<String, String> rules = new HashMap<>();
                while (rs.next()) {
                    rules.put(rs.getString(1), rs.getString(2));
                }
                return rules;
            }, table
        );
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
