package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@SpringBootTest(
    properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:banner-version-migration",
        "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=1", "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"}
)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BannerMySqlMigrationTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-27T12:00:00Z");
    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Container
    static final MySQLContainer<?> MYSQL =
        new MySQLContainer<>("mysql:8.4").withDatabaseName("picsure").withInitScript("mysql-before-banner-version.sql");

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository bannerRepository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BannerPresentationHasher hasher;

    @MockitoBean
    private LoggingClient loggingClient;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.locations", BannerMySqlMigrationTest::migrationLocation);
    }

    private static String migrationLocation() {
        String deploymentMigration = System.getProperty("banner.version.migration");
        if (deploymentMigration == null) {
            return "classpath:banner-version-migration";
        }
        try (
            InputStream fixtureStream =
                BannerMySqlMigrationTest.class.getResourceAsStream("/banner-version-migration/V2__CREATE_BANNER_VERSION.sql")
        ) {
            if (fixtureStream == null) {
                throw new IllegalStateException("The banner version migration fixture is missing");
            }
            Path deploymentPath = Path.of(deploymentMigration);
            if (!Arrays.equals(fixtureStream.readAllBytes(), Files.readAllBytes(deploymentPath))) {
                throw new IllegalStateException("The MySQL test fixture differs from " + deploymentPath);
            }
            Path stagingDirectory = Files.createTempDirectory("banner-version-migration-");
            stagingDirectory.toFile().deleteOnExit();
            Path stagedMigration = stagingDirectory.resolve("V2__CREATE_BANNER_VERSION.sql");
            Files.copy(deploymentPath, stagedMigration, StandardCopyOption.REPLACE_EXISTING);
            stagedMigration.toFile().deleteOnExit();
            return "filesystem:" + stagingDirectory;
        } catch (IOException e) {
            throw new IllegalStateException("The deployment banner version migration could not be staged", e);
        }
    }

    void cleanDatabase() {
        versionRepository.deleteAll();
        bannerRepository.deleteAll();
    }

    @Test
    @Order(1)
    void migrationCreatesTheTableAndBackfillsExactPublishedState() {
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'picsure' AND table_name = 'banner_version'",
                Integer.class
            )
        ).isEqualTo(1);
        assertThat(versionRepository.findAll()).singleElement().satisfies(version -> {
            assertThat(version.getVersionNumber()).isEqualTo(1);
            assertThat(version.getHtmlContent()).isEqualTo("<p>Pre-migration bytes</p>");
            assertThat(version.getTitle()).isEqualTo("Pre-migration title");
            assertThat(version.getEffectiveAt()).isEqualTo(PUBLISHED_AT);
            assertThat(version.getActor()).isEqualTo("publisher");
        });
    }

    @Test
    void newBinaryPublicationCreatesExactlyOneFirstVersionAndTheMigrationEnforcesVersionIdentity() {
        cleanDatabase();
        PublishBannerRequest request = new PublishBannerRequest(
            "<p>New binary</p>", "New binary", BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE,
            BannerPlacement.SITE_TOP, JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("kind", "ALL"))
        );

        ManagementBannerDto published = service.publish(request, ADMIN);

        assertThat(versionRepository.findAll()).singleElement().satisfies(version -> {
            assertThat(version.getBannerUuid()).isEqualTo(published.uuid());
            assertThat(version.getVersionNumber()).isEqualTo(1);
            assertThat(version.getHtmlContent()).isEqualTo("<p>New binary</p>");
            assertThat(version.getActor()).isEqualTo("admin-id");
        });

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO banner_version
            SELECT UUID_TO_BIN(UUID()), banner_uuid, version_number, html_content, title, appearance, icon,
                   dismissible, audience, placement, page_targets, start_at, end_at, presentation_hash,
                   effective_at, actor
            FROM banner_version
            LIMIT 1
            """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO banner_version
            SELECT UUID_TO_BIN(UUID()), UUID_TO_BIN('ffffffff-ffff-ffff-ffff-ffffffffffff'), 1, html_content,
                   title, appearance, icon, dismissible, audience, placement, page_targets, start_at, end_at,
                   presentation_hash, effective_at, actor
            FROM banner_version
            LIMIT 1
            """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oldBinaryPublicationIsLazilyBootstrappedBeforeItsFirstMaterialEdit() {
        cleanDatabase();
        BannerOccurrence oldBinary = oldBinaryPublication("<p>Old binary</p>", "Old binary", "old-admin");

        ManagementBannerDto updated = service.update(oldBinary.getUuid(), request("<p>Corrected</p>", "Corrected"), ADMIN);

        List<BannerVersion> versions = versionsFor(oldBinary.getUuid());
        assertThat(versions).extracting(BannerVersion::getVersionNumber).containsExactly(1, 2);
        assertThat(versions.getFirst().getHtmlContent()).isEqualTo("<p>Old binary</p>");
        assertThat(versions.getFirst().getTitle()).isEqualTo("Old binary");
        assertThat(versions.getFirst().getEffectiveAt()).isEqualTo(PUBLISHED_AT);
        assertThat(versions.getFirst().getActor()).isEqualTo("old-admin");
        assertThat(versions.get(1).getHtmlContent()).isEqualTo("<p>Corrected</p>");
        assertThat(updated.presentationHash()).isEqualTo(versions.get(1).getPresentationHash());
    }

    @Test
    void oldBinaryNoOpStillCommitsItsMissingFirstVersionWithActorFallback() {
        cleanDatabase();
        BannerOccurrence oldBinary = oldBinaryPublication("<p>Same</p>", "Same", null);
        Instant originalUpdatedAt = oldBinary.getUpdatedAt();

        ManagementBannerDto unchanged = service.update(oldBinary.getUuid(), request("<p>Same</p>", " Same "), ADMIN);

        assertThat(unchanged.updatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(versionsFor(oldBinary.getUuid())).singleElement().satisfies(version -> {
            assertThat(version.getHtmlContent()).isEqualTo("<p>Same</p>");
            assertThat(version.getEffectiveAt()).isEqualTo(PUBLISHED_AT);
            assertThat(version.getActor()).isEqualTo("SYSTEM_MIGRATION");
        });
    }

    @Test
    void concurrentEditsSerializeBootstrapAndVersionNumbers() throws Exception {
        cleanDatabase();
        BannerOccurrence oldBinary = oldBinaryPublication("<p>Initial</p>", "Initial", "old-admin");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return service.update(oldBinary.getUuid(), request("<p>First</p>", "First"), ADMIN);
            });
            var second = executor.submit(() -> {
                start.await();
                return service.update(oldBinary.getUuid(), request("<p>Second</p>", "Second"), ADMIN);
            });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }

        List<BannerVersion> versions = versionsFor(oldBinary.getUuid());
        assertThat(versions).extracting(BannerVersion::getVersionNumber).containsExactly(1, 2, 3);
        assertThat(versions.getFirst().getHtmlContent()).isEqualTo("<p>Initial</p>");
        assertThat(versions).extracting(BannerVersion::getHtmlContent)
            .containsExactlyInAnyOrder("<p>Initial</p>", "<p>First</p>", "<p>Second</p>");
        BannerOccurrence current = bannerRepository.findById(oldBinary.getUuid()).orElseThrow();
        assertThat(versions.get(2).getPresentationHash()).isEqualTo(current.getPresentationHash());
    }

    private BannerOccurrence oldBinaryPublication(String htmlContent, String title, String publishedBy) {
        BannerOccurrence banner = new BannerOccurrence().setStatus(BannerStatus.PUBLISHED).setHtmlContent(htmlContent).setTitle(title)
            .setAppearance(BannerAppearance.PRIMARY).setIcon(BannerIcon.INFORMATION).setDismissible(true)
            .setAudience(BannerAudience.EVERYONE).setPlacement(BannerPlacement.SITE_TOP)
            .setPageTargets(JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("kind", "ALL")))
            .setStartAt(PUBLISHED_AT).setPriority(1).setCreatedAt(PUBLISHED_AT.minusSeconds(60)).setCreatedBy("old-admin")
            .setUpdatedAt(PUBLISHED_AT).setUpdatedBy("old-admin").setPublishedAt(PUBLISHED_AT).setPublishedBy(publishedBy);
        banner.setPresentationHash(hasher.hash(banner));
        return bannerRepository.saveAndFlush(banner);
    }

    private PublishBannerRequest request(String htmlContent, String title) {
        return new PublishBannerRequest(
            htmlContent, title, BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("kind", "ALL"))
        );
    }

    private List<BannerVersion> versionsFor(UUID bannerUuid) {
        return versionRepository.findAll().stream().filter(version -> version.getBannerUuid().equals(bannerUuid))
            .sorted(java.util.Comparator.comparingInt(BannerVersion::getVersionNumber)).toList();
    }
}
