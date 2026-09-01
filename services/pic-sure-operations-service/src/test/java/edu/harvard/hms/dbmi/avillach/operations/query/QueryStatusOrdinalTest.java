package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;

/**
 * Covers reading {@code query.status} values that {@link PicSureStatus} does not define. Rows written by two legacy branches that each
 * shipped an unmerged fifth constant hold a 4, and under {@code @Enumerated(EnumType.ORDINAL)} loading one threw
 * {@code ArrayIndexOutOfBoundsException: Index 4 out of bounds for length 4}. Because {@code named_dataset} select-fetches its query per
 * row, that failed the whole {@code GET /dataset/named} list rather than the one entry.
 *
 * <p>Uses its own H2 database rather than the shared {@code ops} one because {@link #dropStatusCheckConstraints} issues DDL, which no
 * surrounding transaction rolls back.
 */
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:ops-status-ordinal;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class QueryStatusOrdinalTest {

    @Autowired
    private QueryRepository repo;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void matchTheProductionColumn() {
        dropStatusCheckConstraints();
    }

    @Test
    void aStoredOrdinalThatTheEnumDoesNotDefineReadsAsNullRatherThanThrowing() {
        UUID id = insertQueryRowWithRawStatus(4);

        Query loaded = repo.findById(id).orElseThrow();

        assertThat(loaded.getStatus()).isNull();
    }

    @Test
    void aNegativeStoredOrdinalAlsoReadsAsNull() {
        UUID id = insertQueryRowWithRawStatus(-1);

        Query loaded = repo.findById(id).orElseThrow();

        assertThat(loaded.getStatus()).isNull();
    }

    @Test
    void theOrdinalsTheEnumDoesDefineStillReadAsTheirConstant() {
        UUID queued = insertQueryRowWithRawStatus(0);
        UUID available = insertQueryRowWithRawStatus(3);

        assertThat(repo.findById(queued).orElseThrow().getStatus()).isEqualTo(PicSureStatus.QUEUED);
        assertThat(repo.findById(available).orElseThrow().getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
    }

    /**
     * The on-disk encoding must not change: the legacy WildFly WAR reads the same column, and every row already in the table is an ordinal.
     */
    @Test
    void writingAStatusStillStoresItsOrdinal() {
        Query entity = new Query();
        entity.setStatus(PicSureStatus.AVAILABLE);

        Query saved = repo.save(entity);
        entityManager.flush();

        assertThat(rawStatusOf(saved.getUuid())).isEqualTo(3);
    }

    @Test
    void aNullStatusStaysNullInBothDirections() {
        UUID id = insertQueryRowWithRawStatus(null);

        assertThat(repo.findById(id).orElseThrow().getStatus()).isNull();
        assertThat(rawStatusOf(id)).isNull();
    }

    /** Clears the persistence context so the caller's subsequent {@code findById} is a real load rather than a first-level cache hit. */
    private UUID insertQueryRowWithRawStatus(Integer status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into query (uuid, status) values (?, ?)", toBinary16(id), status);
        entityManager.clear();
        return id;
    }

    private Integer rawStatusOf(UUID id) {
        return jdbc.queryForObject("select status from query where uuid = ?", Integer.class, (Object) toBinary16(id));
    }

    /**
     * Hibernate's generated DDL adds {@code check (status between 0 and 3)} for an ORDINAL enum. The real MySQL table is a hand-written
     * {@code status int(11)} with no such check (see V1__CREATE_PICSURE_INITIAL.sql), which is exactly why an out-of-range value could be
     * stored in the first place. Dropping it here makes the test schema match production instead of a stricter fiction.
     */
    private void dropStatusCheckConstraints() {
        List<String> names = jdbc.queryForList(
            "select constraint_name from information_schema.table_constraints "
                + "where table_name = 'QUERY' and constraint_type = 'CHECK'",
            String.class
        );
        names.forEach(name -> jdbc.execute("alter table query drop constraint \"" + name + "\""));
    }

    private static byte[] toBinary16(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
