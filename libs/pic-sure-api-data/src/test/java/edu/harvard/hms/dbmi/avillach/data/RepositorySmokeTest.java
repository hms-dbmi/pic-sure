package edu.harvard.hms.dbmi.avillach.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import edu.harvard.hms.dbmi.avillach.data.entity.Configuration;
import edu.harvard.hms.dbmi.avillach.data.entity.NamedDataset;
import edu.harvard.hms.dbmi.avillach.data.entity.Query;
import edu.harvard.hms.dbmi.avillach.data.entity.Site;
import edu.harvard.hms.dbmi.avillach.data.repository.ConfigurationRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.NamedDatasetRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.QueryRepository;
import edu.harvard.hms.dbmi.avillach.data.repository.SiteRepository;

/**
 * Boots a real (H2, MySQL-mode) JPA context to prove the ported entity mappings and Spring Data repositories actually work against a
 * database, not just that they compile.
 *
 * <p>Every persist-then-read case calls {@link TestEntityManager#flush()} + {@link TestEntityManager#clear()} before reading back through
 * the repository. Without that, a {@code save()} followed immediately by a {@code findById()} in the same persistence context can return
 * the still-attached Java object straight out of Hibernate's first-level cache without ever issuing a real SELECT -- which would silently
 * hide a broken mapping (this test suite caught exactly that: an initial version "passed" while the {@code named_dataset} table had
 * actually failed to even get created, see {@link #namedDatasetPhysicalColumnsMatchLegacySchemaExactly()}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class RepositorySmokeTest {

    @Autowired
    private QueryRepository queryRepository;

    @Autowired
    private ConfigurationRepository configurationRepository;

    @Autowired
    private NamedDatasetRepository namedDatasetRepository;

    @Autowired
    private SiteRepository siteRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void persistsAndReadsBackQueryWithBlobsStatusAndVersion() {
        Query query = new Query();
        query.setQuery("{\"select\":[\"foo\"]}");
        query.setMetadata("{\"commonAreaUUID\":\"not-used-here\"}".getBytes());
        query.setStatus(PicSureStatus.AVAILABLE);
        query.setVersion("3");
        query.setStartTime(new Date(System.currentTimeMillis()));

        UUID id = queryRepository.save(query).getUuid();
        assertThat(id).isNotNull();
        entityManager.flush();
        entityManager.clear();

        Query reloaded = queryRepository.findById(id).orElseThrow();
        assertThat(reloaded.getQuery()).isEqualTo("{\"select\":[\"foo\"]}");
        assertThat(new String(reloaded.getMetadata())).isEqualTo("{\"commonAreaUUID\":\"not-used-here\"}");
        assertThat(reloaded.getStatus()).isEqualTo(PicSureStatus.AVAILABLE);
        assertThat(reloaded.getVersion()).isEqualTo("3");
    }

    @Test
    void ordinalStatusRoundTripsAcrossAllEnumValues() {
        for (PicSureStatus status : PicSureStatus.values()) {
            Query query = new Query();
            query.setStatus(status);
            UUID id = queryRepository.save(query).getUuid();
            entityManager.flush();
            entityManager.clear();

            Query reloaded = queryRepository.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(status);
        }
    }

    @Test
    @Disabled("native CONVERT(metadata USING utf8) is MySQL-specific; verified via the query-service MySQL integration, not H2")
    void findsQueryByCommonAreaUUIDEmbeddedInMetadata() {
        UUID commonAreaUUID = UUID.randomUUID();

        Query match = new Query();
        match.setMetadata(("{\"commonAreaUUID\":\"" + commonAreaUUID + "\",\"site\":\"BCH\"}").getBytes());
        UUID matchId = queryRepository.save(match).getUuid();

        Query noise = new Query();
        noise.setMetadata("{\"commonAreaUUID\":\"11111111-1111-1111-1111-111111111111\"}".getBytes());
        queryRepository.save(noise);
        entityManager.flush();
        entityManager.clear();

        Query found = queryRepository.getQueryUUIDFromCommonAreaUUID(commonAreaUUID);

        assertThat(found).isNotNull();
        assertThat(found.getUuid()).isEqualTo(matchId);
    }

    @Test
    @Disabled("native CONVERT(metadata USING utf8) is MySQL-specific; verified via the query-service MySQL integration, not H2")
    void returnsNullWhenNoQueryMatchesCommonAreaUUID() {
        Query found = queryRepository.getQueryUUIDFromCommonAreaUUID(UUID.randomUUID());
        assertThat(found).isNull();
    }

    @Test
    void findsSiteByDomain() {
        Site site = new Site();
        site.setCode("BCH");
        site.setName("Boston Children's");
        site.setDomain("childrens.harvard.edu");
        siteRepository.save(site);
        entityManager.flush();
        entityManager.clear();

        Optional<Site> found = siteRepository.findByDomain("childrens.harvard.edu");

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("BCH");
    }

    @Test
    void findByDomainIsEmptyWhenNoSiteMatches() {
        assertThat(siteRepository.findByDomain("nope.example.org")).isEmpty();
    }

    @Test
    void configurationRepositoryPersistsAndReadsBack() {
        Configuration config = new Configuration();
        config.setName("some-name");
        config.setKind("some-kind");
        config.setValue("some-value");
        config.setDescription("a description");
        UUID configId = configurationRepository.save(config).getUuid();
        entityManager.flush();
        entityManager.clear();

        Configuration reloaded = configurationRepository.findById(configId).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("some-name");
        assertThat(reloaded.getKind()).isEqualTo("some-kind");
        assertThat(reloaded.getValue()).isEqualTo("some-value");
        assertThat(reloaded.getMarkForDelete()).isFalse();
    }

    @Test
    void namedDatasetPersistsAndLinksToQueryAcrossAKeywordUserColumn() {
        Query query = queryRepository.save(new Query());

        NamedDataset namedDataset = new NamedDataset();
        namedDataset.setQuery(query);
        namedDataset.setUser("someone@example.org");
        namedDataset.setName("my dataset");
        UUID namedDatasetId = namedDatasetRepository.save(namedDataset).getUuid();
        entityManager.flush();
        entityManager.clear();

        NamedDataset reloaded = namedDatasetRepository.findById(namedDatasetId).orElseThrow();
        assertThat(reloaded.getQuery().getUuid()).isEqualTo(query.getUuid());
        assertThat(reloaded.getUser()).isEqualTo("someone@example.org");
        assertThat(reloaded.getArchived()).isFalse();

        assertThat(namedDatasetRepository.findByUser("someone@example.org")).extracting(NamedDataset::getUuid)
            .containsExactly(namedDatasetId);
        assertThat(namedDatasetRepository.findByUuidAndUser(namedDatasetId, "someone@example.org")).isPresent();
        assertThat(namedDatasetRepository.findByUuidAndUser(namedDatasetId, "nobody@example.org")).isEmpty();
    }

    /**
     * Guards against Spring Boot's default {@code SpringPhysicalNamingStrategy}, which snake_cases even explicit
     * {@code @Column}/{@code @JoinColumn} names ("queryId" -> "query_id"). The physical columns in the real MySQL
     * {@code query}/{@code named_dataset} tables (see legacy's V1/V5 Flyway SQL) are camelCase; a schema-shape check is the only way to
     * catch a naming- strategy regression, since an H2 auto-created schema is self-consistent even if every column were silently renamed.
     */
    @Test
    void namedDatasetPhysicalColumnsMatchLegacySchemaExactly() throws Exception {
        // Unquoted identifiers are folded to upper case by H2's catalog (independent of MODE=MySQL);
        // "user"/"value" were explicitly quoted in the entities (H2 reserved words) and so keep
        // their exact given case. Case-folding is an H2 storage-catalog quirk, not a naming-strategy
        // bug -- what actually matters, and what this test guards, is that every *name* below
        // matches the legacy camelCase column name letter-for-letter (mod H2's forced casing).
        assertThat(columnNamesOf("QUERY"))
            .containsExactlyInAnyOrder("UUID", "STARTTIME", "READYTIME", "STATUS", "RESOURCERESULTID", "QUERY", "METADATA", "VERSION");
        assertThat(columnNamesOf("NAMED_DATASET")).containsExactlyInAnyOrder("UUID", "QUERYID", "user", "NAME", "ARCHIVED", "METADATA");
        assertThat(columnNamesOf("CONFIGURATION"))
            .containsExactlyInAnyOrder("UUID", "NAME", "KIND", "value", "DESCRIPTION", "MARKFORDELETE");
        assertThat(columnNamesOf("SITE")).containsExactlyInAnyOrder("UUID", "CODE", "NAME", "DOMAIN");
    }

    private Set<String> columnNamesOf(String tableName) throws Exception {
        Set<String> columns = new HashSet<>();
        try (var connection = dataSource.getConnection(); ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }
}
