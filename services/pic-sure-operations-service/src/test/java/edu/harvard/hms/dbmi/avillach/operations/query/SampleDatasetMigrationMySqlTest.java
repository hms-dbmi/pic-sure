package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
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
 * Applies the operations migration set to a real MySQL 8.0, the release line the deployed databases run, and proves the behavior the
 * rollout of V10 and V11 depends on. Each test starts from an empty schema, runs V1 to V9 as whole scripts so the server sees them as a
 * migration tool would send them, seeds two queries and the two named datasets that point at them as the stack before V10 would have
 * written them, and then runs V10 and V11. V11 holds only the foreign keys to the general query, so one test stops after V10 to prove the
 * schema is usable without them. Statements that stand in for the stack before V10 name only columns that existed in V9. The tests cover
 * which rows can be written and deleted once the scripts have run. They do not restate the columns, indexes and keys the scripts declare.
 */
@Testcontainers(disabledWithoutDocker = true)
class SampleDatasetMigrationMySqlTest {

    private static final Path MIGRATIONS = Path.of("db", "sql");
    private static final Pattern MIGRATION_FILE = Pattern.compile("V(\\d+)__.+\\.sql");
    private static final int SAMPLE_DATASET_VERSION = 10;
    private static final int FOREIGN_KEY_VERSION = 11;
    private static final String STARTED_QUERY_START_TIME = "2026-01-02 03:04:05.678";
    private static final String NEW_QUERY_COLUMNS_UNSET = "user_uuid IS NULL AND consents IS NULL AND query_to_variant_uuid IS NULL"
        + " AND sample = 0 AND result_type IS NULL AND created_at IS NULL";

    private static final UUID STARTED_QUERY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID UNSTARTED_QUERY = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID STARTED_DATASET = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID UNSTARTED_DATASET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Container
    static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.0").withDatabaseName("picsure").withUrlParam("allowMultiQueries", "true");

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateSeededSchema() throws Exception {
        dataSource = new SingleConnectionDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        migrateSeededSchemaThrough(FOREIGN_KEY_VERSION);
    }

    @AfterEach
    void closeConnection() {
        dataSource.destroy();
    }

    @Test
    void v10AloneLeavesAUsableSchemaWithoutTheForeignKeys() throws Exception {
        migrateSeededSchemaThrough(SAMPLE_DATASET_VERSION);
        UUID query = UUID.randomUUID();
        UUID dataset = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        UUID missingGeneralQuery = UUID.randomUUID();

        insertQueryAsOldStack(query, STARTED_QUERY_START_TIME);
        insertNamedDatasetAsOldStack(dataset, query, "third@example.org");
        insertVariant(variant, missingGeneralQuery, false, "DATAFRAME");

        assertThat(count("SELECT COUNT(*) FROM query WHERE uuid = ? AND " + NEW_QUERY_COLUMNS_UNSET, bytes(query))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM named_dataset WHERE uuid = ? AND query_to_variant_uuid IS NULL", bytes(dataset)))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT query_to_variant_uuid FROM query WHERE uuid = ?", byte[].class, bytes(variant)))
            .isEqualTo(bytes(missingGeneralQuery));
        assertThat(count("SELECT COUNT(*) FROM query_to_variant")).isZero();
    }

    @Test
    void rowsFromBeforeV10GetNoValuesInTheNewColumns() {
        assertThat(count("SELECT COUNT(*) FROM query")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM query WHERE " + NEW_QUERY_COLUMNS_UNSET)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM named_dataset")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM named_dataset WHERE query_to_variant_uuid IS NULL")).isEqualTo(2);
    }

    @Test
    void oldStackInsertsStillWorkAndGetTheDefaults() {
        UUID query = UUID.randomUUID();
        UUID dataset = UUID.randomUUID();

        insertQueryAsOldStack(query, STARTED_QUERY_START_TIME);
        insertNamedDatasetAsOldStack(dataset, query, "third@example.org");

        assertThat(count("SELECT COUNT(*) FROM query WHERE uuid = ? AND " + NEW_QUERY_COLUMNS_UNSET, bytes(query))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM named_dataset WHERE uuid = ? AND query_to_variant_uuid IS NULL", bytes(dataset)))
            .isEqualTo(1);
    }

    @Test
    void v10LetsAUserHaveSeveralDatasetsOnOneQueryAndKeepsTheForeignKeyToTheQuery() {
        UUID query = UUID.randomUUID();
        insertQueryAsOldStack(query, STARTED_QUERY_START_TIME);

        insertNamedDatasetAsOldStack(UUID.randomUUID(), query, "third@example.org");
        insertNamedDatasetAsOldStack(UUID.randomUUID(), query, "third@example.org");

        assertThat(count("SELECT COUNT(*) FROM named_dataset WHERE queryId = ?", bytes(query))).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM query WHERE uuid = ?", bytes(query)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("foreign_queryId");
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

        assertThat(count("SELECT COUNT(*) FROM query_run WHERE query_uuid = ?", bytes(ranQuery))).isEqualTo(1);
    }

    @Test
    void variantKeyRefusesASecondRowOfOneVariantUnderOneGeneralQuery() {
        UUID generalQuery = UUID.randomUUID();
        insertGeneralQuery(generalQuery);
        insertVariant(UUID.randomUUID(), generalQuery, true, "DATAFRAME");

        assertThatThrownBy(() -> insertVariant(UUID.randomUUID(), generalQuery, true, "DATAFRAME"))
            .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("unique_query_variant");

        insertVariant(UUID.randomUUID(), generalQuery, false, "DATAFRAME");
        insertVariant(UUID.randomUUID(), generalQuery, true, "DATAFRAME_TIMESERIES");
        assertThat(count("SELECT COUNT(*) FROM query WHERE query_to_variant_uuid = ?", bytes(generalQuery))).isEqualTo(3);
    }

    @Test
    void variantKeyAllowsManyRowsWithNoGeneralQuery() {
        insertVariant(UUID.randomUUID(), null, true, "DATAFRAME");
        insertVariant(UUID.randomUUID(), null, true, "DATAFRAME");

        assertThat(count("SELECT COUNT(*) FROM query WHERE query_to_variant_uuid IS NULL AND sample = 1 AND result_type = 'DATAFRAME'"))
            .isEqualTo(2);
    }

    @Test
    void queryRowCannotNameAMissingGeneralQuery() {
        UUID missingGeneralQuery = UUID.randomUUID();

        assertThatThrownBy(() -> insertVariant(UUID.randomUUID(), missingGeneralQuery, false, "DATAFRAME"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_query_query_to_variant");

        assertThat(count("SELECT COUNT(*) FROM query")).isEqualTo(2);
    }

    @Test
    void namedDatasetCannotNameAMissingGeneralQuery() {
        UUID missingGeneralQuery = UUID.randomUUID();
        UUID generalQuery = UUID.randomUUID();
        insertGeneralQuery(generalQuery);

        assertThatThrownBy(() -> nameGeneralQuery(STARTED_DATASET, missingGeneralQuery)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_named_dataset_query_to_variant");

        assertThat(nameGeneralQuery(STARTED_DATASET, generalQuery)).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT query_to_variant_uuid FROM named_dataset WHERE uuid = ?", byte[].class, bytes(STARTED_DATASET))
        ).isEqualTo(bytes(generalQuery));
    }

    /**
     * Rebuilds the schema from nothing: V1 to V9, then the rows the stack before V10 would hold, then V10 up to the given version.
     *
     * @param lastVersion the last migration version to apply, 10 or later
     * @throws IOException when a migration script cannot be read
     */
    private void migrateSeededSchemaThrough(int lastVersion) throws IOException {
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
        for (int version = SAMPLE_DATASET_VERSION; version <= lastVersion; version++) {
            applyMigration(version);
        }
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

    private void insertGeneralQuery(UUID uuid) {
        jdbc.update("INSERT INTO query_to_variant (uuid, user_uuid, created_at) VALUES (?, ?, NOW(3))", bytes(uuid), bytes(OWNER));
    }

    private void insertVariant(UUID uuid, UUID generalQuery, boolean sample, String resultType) {
        jdbc.update(
            "INSERT INTO query (uuid, query, resourceResultId, status, user_uuid, query_to_variant_uuid, sample, result_type)"
                + " VALUES (?, ?, ?, 0, ?, ?, ?, ?)",
            bytes(uuid), new byte[] {1, 2, 3}, uuid.toString(), bytes(OWNER), generalQuery == null ? null : bytes(generalQuery), sample,
            resultType
        );
    }

    private int nameGeneralQuery(UUID dataset, UUID generalQuery) {
        return jdbc.update("UPDATE named_dataset SET query_to_variant_uuid = ? WHERE uuid = ?", bytes(generalQuery), bytes(dataset));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
