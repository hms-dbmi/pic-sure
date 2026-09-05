package edu.harvard.hms.dbmi.avillach.operations.banner;

import static edu.harvard.hms.dbmi.avillach.operations.banner.BannerVersionTestSupport.versionsFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

/**
 * Lifecycle contract for archiving an occurrence. The clock is fixed so the end-exclusive Expired boundary ({@code endAt == now}) is
 * exercised as a table row instead of depending on wall time.
 */
@SpringBootTest
class BannerArchiveTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final GatewayUser ARCHIVER =
        new GatewayUser("archivist-id", "archivist-subject", "archivist@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private BannerPriorityAllocatorRepository priorityAllocatorRepository;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabase() {
        versionRepository.deleteAll();
        repository.deleteAll();
    }

    private static Stream<Arguments> archiveableOccurrences() {
        return Stream.of(
            Arguments.of("saved draft", BannerStatus.SAVED, null, null),
            Arguments.of("disabled occurrence", BannerStatus.DISABLED, NOW.minusSeconds(600), null),
            Arguments.of("expired occurrence", BannerStatus.PUBLISHED, NOW.minusSeconds(600), NOW.minusSeconds(60)),
            Arguments.of("occurrence whose end is exactly now", BannerStatus.PUBLISHED, NOW.minusSeconds(600), NOW)
        );
    }

    private static Stream<Arguments> rejectedOccurrences() {
        return Stream.of(
            Arguments.of("active occurrence without an end", BannerStatus.PUBLISHED, NOW.minusSeconds(600), null),
            Arguments
                .of("active occurrence whose end is one second away", BannerStatus.PUBLISHED, NOW.minusSeconds(600), NOW.plusSeconds(1)),
            Arguments.of("occurrence starting exactly now", BannerStatus.PUBLISHED, NOW, null),
            Arguments.of("scheduled occurrence", BannerStatus.PUBLISHED, NOW.plusSeconds(600), null),
            Arguments.of("already archived occurrence", BannerStatus.ARCHIVED, NOW.minusSeconds(600), null)
        );
    }

    @ParameterizedTest(name = "archives a {0}")
    @MethodSource("archiveableOccurrences")
    void archiveMovesAnInactiveOccurrenceToArchivedFromOneInstantAndActor(
        String label, BannerStatus status, Instant startAt, Instant endAt
    ) {
        UUID uuid = repository.saveAndFlush(banner(status, startAt, endAt, label)).getUuid();

        ArchivedBannerDto archived = service.archive(uuid, ARCHIVER);

        assertThat(archived).isEqualTo(new ArchivedBannerDto(uuid, BannerStatus.ARCHIVED, NOW, "archivist-id"));
        BannerOccurrence stored = repository.findById(uuid).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BannerStatus.ARCHIVED);
        assertThat(stored.getArchivedAt()).isEqualTo(NOW);
        assertThat(stored.getArchivedBy()).isEqualTo("archivist-id");
        assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        assertThat(stored.getUpdatedBy()).isEqualTo("archivist-id");
    }

    @ParameterizedTest(name = "refuses to archive an {0}")
    @MethodSource("rejectedOccurrences")
    void archiveRejectsDisplayableAndAlreadyArchivedOccurrencesWithoutMutation(
        String label, BannerStatus status, Instant startAt, Instant endAt
    ) {
        BannerOccurrence before = repository.saveAndFlush(banner(status, startAt, endAt, label));

        PicsureException exception = assertThrows(PicsureException.class, () -> service.archive(before.getUuid(), ARCHIVER));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        BannerOccurrence stored = repository.findById(before.getUuid()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getArchivedAt()).isNull();
        assertThat(stored.getArchivedBy()).isNull();
        assertThat(stored.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(stored.getUpdatedBy()).isEqualTo(before.getUpdatedBy());
    }

    @Test
    void archiveRejectsAnUnknownOccurrenceWithoutTouchingTheStore() {
        UUID known = repository.saveAndFlush(banner(BannerStatus.SAVED, null, null, "Unrelated")).getUuid();

        PicsureException exception =
            assertThrows(PicsureException.class, () -> service.archive(UUID.fromString("00000000-0000-0000-0000-000000000099"), ARCHIVER));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repository.findById(known).orElseThrow().getStatus()).isEqualTo(BannerStatus.SAVED);
    }

    @Test
    void archivePreservesEveryOtherOccurrenceFieldItsVersionsAndTheAllocator() {
        BannerOccurrence before = repository.saveAndFlush(
            banner(BannerStatus.DISABLED, NOW.minusSeconds(600), NOW.plusSeconds(600), "Retained")
                .setRestoredFromUuid(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
        );
        UUID uuid = before.getUuid();
        versionRepository.saveAndFlush(BannerVersion.snapshot(before, 1, NOW.minusSeconds(600), "publisher-id"));
        versionRepository.saveAndFlush(BannerVersion.snapshot(before, 2, NOW.minusSeconds(300), "editor-id"));
        List<VersionState> versionsBefore = storedVersions(uuid);
        int nextPriority = priorityAllocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority();

        service.archive(uuid, ARCHIVER);

        BannerOccurrence stored = repository.findById(uuid).orElseThrow();
        assertThat(stored.getUuid()).isEqualTo(uuid);
        assertThat(stored.getHtmlContent()).isEqualTo("<p>Retained content</p>");
        assertThat(stored.getTitle()).isEqualTo("Retained");
        assertThat(stored.getAppearance()).isEqualTo(BannerAppearance.WARNING);
        assertThat(stored.getIcon()).isEqualTo(BannerIcon.WARNING);
        assertThat(stored.isDismissible()).isFalse();
        assertThat(stored.getAudience()).isEqualTo(BannerAudience.SIGNED_IN);
        assertThat(stored.getPlacement()).isEqualTo(BannerPlacement.SITE_TOP);
        assertThat(stored.getPageTargets()).isEqualTo(before.getPageTargets());
        assertThat(stored.getStartAt()).isEqualTo(NOW.minusSeconds(600));
        assertThat(stored.getEndAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(stored.getPriority()).isEqualTo(17);
        assertThat(stored.getPresentationHash()).isEqualTo("hash-Retained");
        assertThat(stored.getCreatedAt()).isEqualTo(NOW.minusSeconds(1_200));
        assertThat(stored.getCreatedBy()).isEqualTo("creator-id");
        assertThat(stored.getPublishedAt()).isEqualTo(NOW.minusSeconds(600));
        assertThat(stored.getPublishedBy()).isEqualTo("publisher-id");
        assertThat(stored.getDisabledAt()).isEqualTo(NOW.minusSeconds(120));
        assertThat(stored.getDisabledBy()).isEqualTo("disabler-id");
        assertThat(stored.getRestoredFromUuid()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));

        assertThat(storedVersions(uuid)).isEqualTo(versionsBefore);
        assertThat(priorityAllocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority())
            .isEqualTo(nextPriority);
    }

    @Test
    void archivedOccurrencesLeaveThePublicFeedAndNormalManagementViews() {
        UUID disabled = repository.saveAndFlush(banner(BannerStatus.DISABLED, NOW.minusSeconds(600), null, "Disabled")).getUuid();
        UUID active = repository.saveAndFlush(banner(BannerStatus.PUBLISHED, NOW.minusSeconds(600), null, "Active")).getUuid();

        service.archive(disabled, ARCHIVER);

        assertThat(service.managedBanners()).extracting(ManagementBannerDto::uuid).containsExactly(active);
        assertThat(service.activeBanners()).extracting(ActiveBannerDto::uuid).containsExactly(active);
    }

    private List<VersionState> storedVersions(UUID uuid) {
        return versionsFor(versionRepository, uuid).stream().map(VersionState::of).toList();
    }

    private static BannerOccurrence banner(BannerStatus status, Instant startAt, Instant endAt, String title) {
        return new BannerOccurrence().setStatus(status).setHtmlContent("<p>" + title + " content</p>").setTitle(title)
            .setAppearance(BannerAppearance.WARNING).setIcon(BannerIcon.WARNING).setDismissible(false).setAudience(BannerAudience.SIGNED_IN)
            .setPlacement(BannerPlacement.SITE_TOP).setPageTargets(List.of(BannerPageTarget.all())).setStartAt(startAt).setEndAt(endAt)
            .setPriority(17).setPresentationHash("hash-" + title).setCreatedAt(NOW.minusSeconds(1_200)).setCreatedBy("creator-id")
            .setUpdatedAt(NOW.minusSeconds(300)).setUpdatedBy("editor-id").setPublishedAt(startAt == null ? null : NOW.minusSeconds(600))
            .setPublishedBy(startAt == null ? null : "publisher-id")
            .setDisabledAt(status == BannerStatus.DISABLED ? NOW.minusSeconds(120) : null)
            .setDisabledBy(status == BannerStatus.DISABLED ? "disabler-id" : null);
    }

    /** Field-by-field snapshot of a stored version row, so preservation is asserted against values rather than managed instances. */
    private record VersionState(
        UUID uuid, UUID bannerUuid, int versionNumber, String htmlContent, String title, BannerAppearance appearance, BannerIcon icon,
        boolean dismissible, BannerAudience audience, BannerPlacement placement, String pageTargets, Instant startAt, Instant endAt,
        String presentationHash, Instant effectiveAt, String actor
    ) {
        private static VersionState of(BannerVersion version) {
            return new VersionState(
                version.getUuid(), version.getBannerUuid(), version.getVersionNumber(), version.getHtmlContent(), version.getTitle(),
                version.getAppearance(), version.getIcon(), version.isDismissible(), version.getAudience(), version.getPlacement(),
                version.getPageTargets().toString(), version.getStartAt(), version.getEndAt(), version.getPresentationHash(),
                version.getEffectiveAt(), version.getActor()
            );
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        @Qualifier("bannerClock")
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
