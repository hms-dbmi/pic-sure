package edu.harvard.hms.dbmi.avillach.operations.banner;

import static edu.harvard.hms.dbmi.avillach.operations.banner.BannerVersionTestSupport.versionsFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import jakarta.persistence.EntityManager;

@SpringBootTest(
    properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:banner-version-migration",
        "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=1", "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.defer-datasource-initialization=false", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"}
)
@Testcontainers(disabledWithoutDocker = true)
class BannerMySqlMigrationTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-27T12:00:00Z");
    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("picsure")
        .withCommand("--log-bin-trust-function-creators=1").withInitScript("mysql-before-banner-version.sql");

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

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private LoggingClient loggingClient;

    private static List<MigratedVersion> migratedVersions;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @BeforeEach
    void captureInitialMigrationThenCleanDatabase() {
        synchronized (BannerMySqlMigrationTest.class) {
            if (migratedVersions == null) {
                migratedVersions = versionRepository.findAll().stream()
                    .map(
                        version -> new MigratedVersion(
                            version.getVersionNumber(), version.getHtmlContent(), version.getTitle(), version.getEffectiveAt(),
                            version.getActor()
                        )
                    ).toList();
            }
        }
        versionRepository.deleteAll();
        bannerRepository.deleteAll();
    }

    @Test
    void migrationCreatesTheTableAndBackfillsExactPublishedState() {
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'picsure' AND table_name = 'banner_version'",
                Integer.class
            )
        ).isEqualTo(1);
        assertThat(migratedVersions).hasSize(2);
        assertThat(migratedVersions).filteredOn(version -> version.htmlContent().equals("<p>Pre-migration bytes</p>")).singleElement()
            .satisfies(version -> {
                assertThat(version.versionNumber()).isEqualTo(1);
                assertThat(version.title()).isEqualTo("Pre-migration title");
                assertThat(version.effectiveAt()).isEqualTo(PUBLISHED_AT);
                assertThat(version.actor()).isEqualTo("publisher");
            });
        assertThat(migratedVersions).filteredOn(version -> version.htmlContent().equals("<p>Missing publication time</p>")).singleElement()
            .satisfies(version -> {
                assertThat(version.effectiveAt()).isEqualTo(PUBLISHED_AT.plusSeconds(3600));
                assertThat(version.actor()).isEqualTo(BannerService.SYSTEM_MIGRATION_ACTOR);
            });
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'picsure' AND table_name = 'banner_priority_allocator'",
                Integer.class
            )
        ).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT next_priority FROM banner_priority_allocator WHERE id = 1", Integer.class))
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void newBinaryPublicationCreatesExactlyOneFirstVersionAndTheMigrationEnforcesVersionIdentity() {
        PublishBannerRequest request = new PublishBannerRequest(
            "<p>New binary</p>", "New binary", BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE,
            BannerPlacement.SITE_TOP, List.of(BannerPageTarget.all())
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
        BannerOccurrence oldBinary = oldBinaryPublication("<p>Old binary</p>", "Old binary", "old-admin");

        ManagementBannerDto updated = service.update(oldBinary.getUuid(), request("<p>Corrected</p>", "Corrected"), ADMIN);

        List<BannerVersion> versions = versionsFor(versionRepository, oldBinary.getUuid());
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
        BannerOccurrence oldBinary = oldBinaryPublication("<p>Same</p>", "Same", null);
        Instant originalUpdatedAt = oldBinary.getUpdatedAt();

        ManagementBannerDto unchanged = service.update(oldBinary.getUuid(), request("<p>Same</p>", " Same "), ADMIN);

        assertThat(unchanged.updatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(versionsFor(versionRepository, oldBinary.getUuid())).singleElement().satisfies(version -> {
            assertThat(version.getHtmlContent()).isEqualTo("<p>Same</p>");
            assertThat(version.getEffectiveAt()).isEqualTo(PUBLISHED_AT);
            assertThat(version.getActor()).isEqualTo(BannerService.SYSTEM_MIGRATION_ACTOR);
        });
    }

    @Test
    void concurrentEditsSerializeBootstrapAndVersionNumbers() throws Exception {
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

        List<BannerVersion> versions = versionsFor(versionRepository, oldBinary.getUuid());
        assertThat(versions).extracting(BannerVersion::getVersionNumber).containsExactly(1, 2, 3);
        assertThat(versions.getFirst().getHtmlContent()).isEqualTo("<p>Initial</p>");
        assertThat(versions).extracting(BannerVersion::getHtmlContent)
            .containsExactlyInAnyOrder("<p>Initial</p>", "<p>First</p>", "<p>Second</p>");
        BannerOccurrence current = bannerRepository.findById(oldBinary.getUuid()).orElseThrow();
        assertThat(versions.get(2).getPresentationHash()).isEqualTo(current.getPresentationHash());
    }

    @Test
    void concurrentPublicationsAllocateDistinctBottomPriorities() throws Exception {
        jdbcTemplate.execute("CREATE TRIGGER delay_banner_insert BEFORE INSERT ON banner_occurrence FOR EACH ROW DO SLEEP(0.5)");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return service.publish(request("<p>First publication</p>", "First"), ADMIN);
            });
            var second = executor.submit(() -> {
                start.await();
                return service.publish(request("<p>Second publication</p>", "Second"), ADMIN);
            });
            start.countDown();
            List<Integer> priorities =
                List.of(first.get(20, TimeUnit.SECONDS).priority(), second.get(20, TimeUnit.SECONDS).priority()).stream().sorted().toList();
            assertThat(priorities).doesNotHaveDuplicates();
            assertThat(priorities.get(1)).isEqualTo(priorities.getFirst() + 1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS delay_banner_insert");
        }
    }

    @Test
    void laterConcurrentReorderControlsTheFinalSharedMemberOrder() throws Exception {
        ManagementBannerDto first = service.publish(request("<p>First</p>", "First"), ADMIN);
        ManagementBannerDto second = service.publish(request("<p>Second</p>", "Second"), ADMIN);
        ManagementBannerDto third = service.publish(request("<p>Third</p>", "Third"), ADMIN);
        installDelayedReorderTrigger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var earlier = executor.submit(() -> service.reorder(List.of(third.uuid(), first.uuid(), second.uuid()), ADMIN));
            awaitActiveMySqlTransaction();
            var later = executor.submit(() -> service.reorder(List.of(second.uuid(), third.uuid(), first.uuid()), ADMIN));

            assertThat(earlier.get(20, TimeUnit.SECONDS)).extracting(ManagementBannerDto::uuid)
                .containsExactly(third.uuid(), first.uuid(), second.uuid());
            assertThat(later.get(20, TimeUnit.SECONDS)).extracting(ManagementBannerDto::uuid)
                .containsExactly(second.uuid(), third.uuid(), first.uuid());
        } finally {
            removeDelayedReorderTrigger();
        }

        assertThat(service.managedBanners()).filteredOn(banner -> banner.lifecycle() == BannerLifecycle.ACTIVE)
            .extracting(ManagementBannerDto::uuid).containsExactly(second.uuid(), third.uuid(), first.uuid());
    }

    @Test
    void publicationWaitsForAnOverlappingReorderAndAllocatesAfterItsCanonicalQueue() throws Exception {
        ManagementBannerDto first = service.publish(request("<p>First</p>", "First"), ADMIN);
        ManagementBannerDto second = service.publish(request("<p>Second</p>", "Second"), ADMIN);
        installDelayedReorderTrigger();

        ManagementBannerDto arrival;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reorder = executor.submit(() -> service.reorder(List.of(second.uuid(), first.uuid()), ADMIN));
            awaitActiveMySqlTransaction();
            var publish = executor.submit(() -> service.publish(request("<p>Arrival</p>", "Arrival"), ADMIN));

            assertThat(reorder.get(20, TimeUnit.SECONDS)).extracting(ManagementBannerDto::uuid)
                .containsExactly(second.uuid(), first.uuid());
            arrival = publish.get(20, TimeUnit.SECONDS);
        } finally {
            removeDelayedReorderTrigger();
        }

        assertThat(service.managedBanners()).filteredOn(banner -> banner.lifecycle() == BannerLifecycle.ACTIVE)
            .extracting(ManagementBannerDto::uuid).containsExactly(second.uuid(), first.uuid(), arrival.uuid());
        assertThat(arrival.priority()).isEqualTo(3);
    }

    @Test
    void emptyPreTargetingJsonRoundTripsAsLegacyAllPages() {
        ManagementBannerDto published = service.publish(request("<p>Legacy target bytes</p>", "Legacy target bytes"), ADMIN);
        jdbcTemplate.update("UPDATE banner_occurrence SET page_targets = CAST(? AS JSON) WHERE uuid = UUID_TO_BIN(?)", "[]",
            published.uuid().toString());
        jdbcTemplate.update("UPDATE banner_version SET page_targets = CAST(? AS JSON) WHERE banner_uuid = UUID_TO_BIN(?)", "[]",
            published.uuid().toString());
        entityManager.clear();

        assertThat(service.managedBanners()).singleElement()
            .extracting(ManagementBannerDto::pageTargets).isEqualTo(List.of(BannerPageTarget.all()));
        assertThat(service.targetedActiveBanners()).singleElement()
            .extracting(ActiveBannerDto::pageTargets).isEqualTo(List.of(BannerPageTarget.all()));
        assertThat(versionRepository.findAll()).singleElement()
            .extracting(BannerVersion::getPageTargets).isEqualTo(List.of(BannerPageTarget.all()));
    }

    @Test
    void malformedAndUnknownStoredTargetsAreOmittedWithoutTakingListsDown() {
        ManagementBannerDto valid = service.publish(
            request("<p>Valid targeted</p>", "Valid targeted", List.of(new BannerPageTarget(BannerPageTargetKind.EXACT, "/help"))), ADMIN
        );
        ManagementBannerDto malformed = service.publish(request("<p>Malformed</p>", "Malformed"), ADMIN);
        ManagementBannerDto unknown = service.publish(request("<p>Unknown</p>", "Unknown"), ADMIN);
        jdbcTemplate.update(
            "UPDATE banner_occurrence SET page_targets = CAST(? AS JSON) WHERE uuid = UUID_TO_BIN(?)", "[\"legacy\"]",
            malformed.uuid().toString()
        );
        jdbcTemplate.update(
            "UPDATE banner_version SET page_targets = CAST(? AS JSON) WHERE banner_uuid = UUID_TO_BIN(?)", "[\"legacy\"]",
            malformed.uuid().toString()
        );
        jdbcTemplate.update(
            "UPDATE banner_occurrence SET page_targets = CAST(? AS JSON) WHERE uuid = UUID_TO_BIN(?)",
            "[{\"kind\":\"FUTURE\",\"path\":\"/future\"}]", unknown.uuid().toString()
        );
        jdbcTemplate.update(
            "UPDATE banner_version SET page_targets = CAST(? AS JSON) WHERE banner_uuid = UUID_TO_BIN(?)",
            "[{\"kind\":\"FUTURE\",\"path\":\"/future\"}]", unknown.uuid().toString()
        );
        entityManager.clear();

        assertThat(service.managedBanners()).extracting(ManagementBannerDto::uuid).containsExactly(valid.uuid());
        assertThat(service.targetedActiveBanners()).extracting(ActiveBannerDto::uuid).containsExactly(valid.uuid());
        assertThat(service.legacyAllPagesActiveBanners()).isEmpty();
        assertThat(versionRepository.findAll()).filteredOn(version -> !version.getBannerUuid().equals(valid.uuid()))
            .allSatisfy(version -> assertThat(version.getPageTargets()).isNull());
    }

    @Test
    void currentTargetedJsonRoundTripsThroughOccurrenceVersionAndFeed() {
        ManagementBannerDto published = service.publish(
            request(
                "<p>Current targeted</p>", "Current targeted",
                List.of(new BannerPageTarget(BannerPageTargetKind.EXACT, "/help /"))
            ),
            ADMIN
        );
        entityManager.clear();
        List<BannerPageTarget> expected = List.of(new BannerPageTarget(BannerPageTargetKind.EXACT, "/help"));

        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(page_targets, '$[0].path')) FROM banner_occurrence WHERE uuid = UUID_TO_BIN(?)",
                String.class, published.uuid().toString()
            )
        ).isEqualTo("/help");
        assertThat(bannerRepository.findById(published.uuid()).orElseThrow().getPageTargets()).isEqualTo(expected);
        assertThat(versionRepository.findAll()).singleElement().extracting(BannerVersion::getPageTargets).isEqualTo(expected);
        assertThat(service.targetedActiveBanners()).singleElement().extracting(ActiveBannerDto::pageTargets).isEqualTo(expected);
    }

    @Test
    void legacyEmptyTargetsNoOpPreservesDismissalIdentityUntilOneMaterialUpdate() throws Exception {
        PublishBannerRequest unchanged = request("<p>Legacy all pages</p>", "Legacy all pages");
        ManagementBannerDto published = service.publish(unchanged, ADMIN);
        String legacyHash = legacyEmptyTargetsHash(unchanged);
        assertThat(legacyHash).isNotEqualTo(published.presentationHash());
        jdbcTemplate.update(
            "UPDATE banner_occurrence SET page_targets = CAST(? AS JSON), presentation_hash = ? WHERE uuid = UUID_TO_BIN(?)", "[]",
            legacyHash, published.uuid().toString()
        );
        jdbcTemplate.update(
            "UPDATE banner_version SET page_targets = CAST(? AS JSON), presentation_hash = ? WHERE banner_uuid = UUID_TO_BIN(?)", "[]",
            legacyHash, published.uuid().toString()
        );
        entityManager.clear();
        reset(loggingClient);

        ManagementBannerDto noOp = service.update(published.uuid(), unchanged, ADMIN);

        assertThat(noOp.presentationHash()).isEqualTo(legacyHash);
        assertThat(storedPresentationHash(published.uuid())).isEqualTo(legacyHash);
        assertThat(storedTargetCounts("banner_occurrence", "uuid", "created_at", published.uuid())).containsExactly(0);
        assertThat(storedTargetCounts("banner_version", "banner_uuid", "version_number", published.uuid())).containsExactly(0);
        assertThat(versionsFor(versionRepository, published.uuid())).singleElement()
            .extracting(BannerVersion::getPresentationHash).isEqualTo(legacyHash);
        verifyNoInteractions(loggingClient);

        PublishBannerRequest changed = request("<p>Materially changed</p>", "Legacy all pages");
        ManagementBannerDto updated = service.update(published.uuid(), changed, ADMIN);

        assertThat(updated.presentationHash()).isNotEqualTo(legacyHash);
        assertThat(storedPresentationHash(published.uuid())).isEqualTo(updated.presentationHash());
        assertThat(storedTargetCounts("banner_occurrence", "uuid", "created_at", published.uuid())).containsExactly(1);
        assertThat(storedTargetCounts("banner_version", "banner_uuid", "version_number", published.uuid())).containsExactly(0, 1);
        assertThat(hasher.hash(bannerRepository.findById(published.uuid()).orElseThrow())).isEqualTo(updated.presentationHash());
        assertThat(versionsFor(versionRepository, published.uuid())).extracting(BannerVersion::getPresentationHash)
            .containsExactly(legacyHash, updated.presentationHash());
        ArgumentCaptor<LoggingEvent> audit = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("banner.updated");
        assertThat(audit.getValue().getMetadata()).containsAllEntriesOf(
            Map.of("previousPresentationHash", legacyHash, "presentationHash", updated.presentationHash())
        );
    }

    private String storedPresentationHash(UUID bannerUuid) {
        return jdbcTemplate.queryForObject(
            "SELECT presentation_hash FROM banner_occurrence WHERE uuid = UUID_TO_BIN(?)", String.class, bannerUuid.toString()
        );
    }

    private void awaitActiveMySqlTransaction() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer signaled = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM banner_reorder_test_signal", Integer.class);
            if (signaled != null && signaled > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for the overlapping MySQL transaction");
    }

    private void installDelayedReorderTrigger() {
        jdbcTemplate.execute("CREATE TABLE banner_reorder_test_signal (id INT PRIMARY KEY) ENGINE=MyISAM");
        jdbcTemplate.execute("""
            CREATE TRIGGER delay_banner_reorder BEFORE UPDATE ON banner_occurrence FOR EACH ROW
            BEGIN
              INSERT IGNORE INTO banner_reorder_test_signal VALUES (1);
              DO SLEEP(0.2);
            END
            """);
    }

    private void removeDelayedReorderTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS delay_banner_reorder");
        jdbcTemplate.execute("DROP TABLE IF EXISTS banner_reorder_test_signal");
    }

    private List<Integer> storedTargetCounts(String table, String bannerColumn, String orderColumn, UUID bannerUuid) {
        return jdbcTemplate.queryForList(
            "SELECT JSON_LENGTH(page_targets) FROM " + table + " WHERE " + bannerColumn + " = UUID_TO_BIN(?) ORDER BY " + orderColumn,
            Integer.class, bannerUuid.toString()
        );
    }

    private static String legacyEmptyTargetsHash(PublishBannerRequest request) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> fields = List.of(
            request.htmlContent(), BannerPresentationHasher.normalizeTitle(request.title()), request.appearance().name(), request.icon().name(),
            Boolean.toString(request.dismissible()), request.audience().name(), request.placement().name(), "[]"
        );
        for (String field : fields) {
            byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
            digest.update(encoded);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private BannerOccurrence oldBinaryPublication(String htmlContent, String title, String publishedBy) {
        BannerOccurrence banner = new BannerOccurrence().setStatus(BannerStatus.PUBLISHED).setHtmlContent(htmlContent).setTitle(title)
            .setAppearance(BannerAppearance.PRIMARY).setIcon(BannerIcon.INFORMATION).setDismissible(true)
            .setAudience(BannerAudience.EVERYONE).setPlacement(BannerPlacement.SITE_TOP).setPageTargets(List.of(BannerPageTarget.all()))
            .setStartAt(PUBLISHED_AT).setPriority(1).setCreatedAt(PUBLISHED_AT.minusSeconds(60)).setCreatedBy("old-admin")
            .setUpdatedAt(PUBLISHED_AT).setUpdatedBy("old-admin").setPublishedAt(PUBLISHED_AT).setPublishedBy(publishedBy);
        banner.setPresentationHash(hasher.hash(banner));
        return bannerRepository.saveAndFlush(banner);
    }

    private PublishBannerRequest request(String htmlContent, String title) {
        return request(htmlContent, title, List.of(BannerPageTarget.all()));
    }

    private PublishBannerRequest request(String htmlContent, String title, List<BannerPageTarget> pageTargets) {
        return new PublishBannerRequest(
            htmlContent, title, BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            pageTargets
        );
    }

    private record MigratedVersion(int versionNumber, String htmlContent, String title, Instant effectiveAt, String actor) {
    }

}
