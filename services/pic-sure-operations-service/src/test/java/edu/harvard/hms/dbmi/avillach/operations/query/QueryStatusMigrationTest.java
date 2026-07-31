package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;

/**
 * Exercises {@code db/sql/V9__ALTER_QUERY_STATUS_TO_STRING.sql} -- the data half of the {@code @Enumerated} ORDINAL -> STRING flip on
 * {@link Query#getStatus()}.
 *
 * <p>The service itself does NOT run Flyway (production runs {@code ddl-auto: none} against a schema owned by the legacy Flyway migrations,
 * and the test context builds H2 tables from the entities), so nothing else in the suite would ever execute this file. This test replays it
 * against an H2 database in MySQL mode over a table shaped like the real one -- an {@code int} {@code status} column holding the legacy
 * ORDINALS -- and asserts every ordinal lands on the matching {@link PicSureStatus} NAME. It is a regression net for the CASE mapping, not
 * a substitute for running the migration against real MySQL.
 */
class QueryStatusMigrationTest {

    private static final Path MIGRATION = Path.of("db/sql/V9__ALTER_QUERY_STATUS_TO_STRING.sql");

    @Test
    void rewritesEveryLegacyOrdinalToItsEnumName() throws Exception {
        Map<UUID, Integer> seeded = new LinkedHashMap<>();
        for (PicSureStatus status : PicSureStatus.values()) {
            seeded.put(UUID.randomUUID(), status.ordinal());
        }

        Map<UUID, String> after;
        try (Connection conn = h2()) {
            createLegacyQueryTable(conn);
            for (Map.Entry<UUID, Integer> row : seeded.entrySet()) {
                insertLegacyRow(conn, row.getKey(), String.valueOf(row.getValue()));
            }

            runMigration(conn);
            after = readStatuses(conn);
        }

        for (Map.Entry<UUID, Integer> row : seeded.entrySet()) {
            assertThat(after.get(row.getKey())).isEqualTo(PicSureStatus.values()[row.getValue()].name());
        }
        // Pins the ordinals themselves, so a reordering of the enum breaks here rather than silently remapping stored rows.
        assertThat(after.values()).containsExactlyInAnyOrder("QUEUED", "PENDING", "ERROR", "AVAILABLE");
    }

    /** A NULL status is "no status recorded", not QUEUED -- the migration must not invent one. */
    @Test
    void leavesNullStatusesNull() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection conn = h2()) {
            createLegacyQueryTable(conn);
            insertLegacyRow(conn, id, null);

            runMigration(conn);

            assertThat(readStatuses(conn).get(id)).isNull();
        }
    }

    /** Re-running the migration (or running it against a partially migrated table) must not corrupt already-named rows. */
    @Test
    void leavesAlreadyMigratedNamesUntouched() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection conn = h2()) {
            createLegacyQueryTable(conn);
            insertLegacyRow(conn, id, "2");

            runMigration(conn);
            runMigration(conn);

            assertThat(readStatuses(conn).get(id)).isEqualTo("ERROR");
        }
    }

    // --- harness ---

    private static Connection h2() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:v9-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** The pre-V9 shape of the columns V9 touches, per V1__CREATE_PICSURE_INITIAL.sql: {@code status int(11) DEFAULT NULL}. */
    private static void createLegacyQueryTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE `query` (`uuid` VARCHAR(36) NOT NULL PRIMARY KEY, `status` INT DEFAULT NULL)");
        }
    }

    private static void insertLegacyRow(Connection conn, UUID id, String status) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO `query` (`uuid`, `status`) VALUES ('" + id + "', " + (status == null ? "NULL" : status) + ")");
        }
    }

    /**
     * Replays the checked-in migration statement by statement. {@code USE `picsure`;} selects the schema in the real deployment and has no
     * H2 equivalent, so it is skipped -- everything else runs verbatim, which is the point of reading the file rather than restating it.
     */
    private static void runMigration(Connection conn) throws IOException, SQLException {
        for (String statement : statements(Files.readString(MIGRATION, StandardCharsets.UTF_8))) {
            try (Statement st = conn.createStatement()) {
                st.execute(statement);
            }
        }
    }

    private static List<String> statements(String sql) {
        String stripped = Arrays.stream(sql.split("\n")).filter(line -> !line.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        List<String> statements = new ArrayList<>();
        for (String candidate : stripped.split(";")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty() && !trimmed.toUpperCase().startsWith("USE ")) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private static Map<UUID, String> readStatuses(Connection conn) throws SQLException {
        Map<UUID, String> statuses = new LinkedHashMap<>();
        try (
            Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT `uuid`, `status` FROM `query` ORDER BY `uuid`")
        ) {
            while (rs.next()) {
                statuses.put(UUID.fromString(rs.getString(1)), rs.getString(2));
            }
        }
        return statuses;
    }
}
