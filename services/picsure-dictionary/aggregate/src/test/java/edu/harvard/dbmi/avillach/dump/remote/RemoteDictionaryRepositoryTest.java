package edu.harvard.dbmi.avillach.dump.remote;

import edu.harvard.dbmi.avillach.dump.entities.ConceptNodeDump;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;


@Testcontainers
@SpringBootTest
class RemoteDictionaryRepositoryTest {

    @Autowired
    JdbcTemplate template;

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void mySQLProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @Autowired
    RemoteDictionaryRepository subject;

    @Test
    void shouldDropValuesForSite() {
        String sql = """
            SELECT count(*)
            FROM concept_node__remote_dictionary
            WHERE REMOTE_DICTIONARY_ID = (
                SELECT REMOTE_DICTIONARY_ID
                FROM remote_dictionary
                WHERE NAME = 'bch'
            )
            """;
        Integer count = template.queryForObject(sql, Integer.class);
        Integer siteCount = template.queryForObject("SELECT count(*) FROM remote_dictionary WHERE NAME = 'bch'", Integer.class);
        Assertions.assertEquals(7, count);
        Assertions.assertEquals(1, siteCount);

        subject.dropValuesForSite("bch");

        count = template.queryForObject(sql, Integer.class);
        Assertions.assertEquals(0, count);
    }

    @Test
    void shouldGetTimestampForSite() {
        LocalDateTime actual = subject.getUpdateTimestamp("foo");
        LocalDateTime expected = LocalDateTime.of(1800, Month.JANUARY, 1, 0, 0);

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldGetDefaultTimestampForSiteThatDNE() {
        LocalDateTime actual = subject.getUpdateTimestamp("brap");
        LocalDateTime expected = LocalDateTime.MIN;

        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldUpdateTimestampForSite() {
        LocalDateTime actual = subject.getUpdateTimestamp("bar");
        LocalDateTime expected = LocalDateTime.of(2000, Month.JANUARY, 1, 0, 0);
        Assertions.assertEquals(expected, actual);

        subject.setUpdateTimestamp("bar", LocalDateTime.of(2025, Month.JANUARY, 1, 0, 0));

        expected = LocalDateTime.of(2025, Month.JANUARY, 1, 0, 0);
        actual = subject.getUpdateTimestamp("bar");
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void shouldPruneHangingEntries() {
        String orphanSQL = """
            SELECT count(*)
            FROM facet__concept_node
            WHERE NOT EXISTS (
                SELECT 1
                FROM concept_node__remote_dictionary
                WHERE facet__concept_node.concept_node_id = concept_node__remote_dictionary.concept_node_id
            )
            UNION ALL
            SELECT count(*)
            FROM concept_node_meta
            WHERE NOT EXISTS (
                SELECT 1
                FROM concept_node__remote_dictionary
                WHERE concept_node_meta.concept_node_id = concept_node__remote_dictionary.concept_node_id
            )
            UNION ALL
            SELECT count(*)
            FROM facet_meta
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet__concept_node
                WHERE facet__concept_node.facet_id = facet_meta.facet_id
            )
            UNION ALL
            SELECT count(*)
            FROM facet
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet__concept_node
                WHERE facet__concept_node.facet_id = facet.facet_id
            )
            UNION ALL
            SELECT count(*)
            FROM concept_node
            WHERE
                NOT EXISTS (
                    SELECT 1
                    FROM concept_node child
                    WHERE child.parent_id = concept_node.concept_node_id
                )
                AND
                NOT EXISTS (
                    SELECT 1
                    FROM concept_node__remote_dictionary
                    WHERE concept_node.concept_node_id = concept_node__remote_dictionary.concept_node_id
                )
            UNION ALL
            SELECT count(*)
            FROM facet_category
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet
                WHERE facet.facet_category_id = facet_category.facet_category_id
            )
            UNION ALL
            SELECT count(*)
            FROM facet_category_meta
            WHERE NOT EXISTS (
                SELECT 1
                FROM facet
                WHERE facet.facet_category_id = facet_category_meta.facet_category_id
            )
            """;
        String allSQL = """
            SELECT count(*)
            FROM facet__concept_node
            UNION ALL
            SELECT count(*)
            FROM concept_node_meta
            UNION ALL
            SELECT count(*)
            FROM facet_meta
            UNION ALL
            SELECT count(*)
            FROM facet
            UNION ALL
            SELECT count(*)
            FROM concept_node
            UNION ALL
            SELECT count(*)
            FROM facet_category
            UNION ALL
            SELECT count(*)
            FROM facet_category_meta
            """;
        List<Integer> beforeActual = template.queryForList(orphanSQL, Integer.class);
        List<Integer> beforeAllActual = template.queryForList(allSQL, Integer.class);
        List<Integer> beforeExpected = List.of(2, 2, 0, 6, 1, 1, 0);
        List<Integer> beforeAllExpected = List.of(94, 117, 3, 18, 92, 3, 1);
        Assertions.assertEquals(beforeExpected, beforeActual);
        Assertions.assertEquals(beforeAllExpected, beforeAllActual);

        subject.pruneHangingEntries();

        List<Integer> afterActual = template.queryForList(orphanSQL, Integer.class);
        List<Integer> afterAllActual = template.queryForList(allSQL, Integer.class);
        List<Integer> afterExpected = List.of(0, 0, 0, 0, 0, 0, 0);
        List<Integer> afterAllExpected = List.of(92, 115, 3, 12, 91, 2, 1);
        Assertions.assertEquals(afterExpected, afterActual);
        Assertions.assertEquals(afterAllExpected, afterAllActual);
    }
}
