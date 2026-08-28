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

@SpringBootTest
class BannerRestoreTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final GatewayUser RESTORER =
        new GatewayUser("restorer-id", "restorer-subject", "restorer@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private BannerPriorityAllocatorRepository allocatorRepository;

    @Autowired
    private BannerPresentationHasher hasher;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabase() {
        versionRepository.deleteAll();
        repository.deleteAll();
        allocatorRepository.saveAndFlush(
            new BannerPriorityAllocator().setId(BannerPriorityAllocator.SINGLETON_ID).setNextPriority(1)
        );
    }

    @Test
    void restoresDisabledOccurrenceAsNewPublishedOccurrenceAndArchivesSource() {
        repository.saveAndFlush(banner(BannerStatus.PUBLISHED, NOW.minusSeconds(600), null, 40, "Existing"));
        BannerOccurrence source = repository.saveAndFlush(
            banner(BannerStatus.DISABLED, NOW.minusSeconds(1_200), null, 17, "Source").setDisabledAt(NOW.minusSeconds(60))
                .setDisabledBy("disabler-id").setRestoredFromUuid(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
        );
        versionRepository.saveAndFlush(BannerVersion.snapshot(source, 1, NOW.minusSeconds(1_200), "publisher-id"));
        PublishBannerRequest request = request(null, NOW.plusSeconds(3_600));

        ManagementBannerDto restored = service.restore(source.getUuid(), request, RESTORER);

        assertThat(restored.uuid()).isNotEqualTo(source.getUuid());
        assertThat(restored.status()).isEqualTo(BannerStatus.PUBLISHED);
        assertThat(restored.lifecycle()).isEqualTo(BannerLifecycle.ACTIVE);
        assertThat(restored.htmlContent()).isEqualTo("<p>Restored content</p>");
        assertThat(restored.title()).isEqualTo("Restored notice");
        assertThat(restored.appearance()).isEqualTo(BannerAppearance.PRIMARY);
        assertThat(restored.icon()).isEqualTo(BannerIcon.INFORMATION);
        assertThat(restored.dismissible()).isTrue();
        assertThat(restored.audience()).isEqualTo(BannerAudience.EVERYONE);
        assertThat(restored.placement()).isEqualTo(BannerPlacement.SITE_TOP);
        assertThat(restored.pageTargets()).containsExactly(
            new BannerPageTarget(BannerPageTargetKind.EXACT, "/about"),
            new BannerPageTarget(BannerPageTargetKind.PARAMETERIZED, "/studies/[studyId]")
        );
        assertThat(restored.startAt()).isEqualTo(NOW);
        assertThat(restored.endAt()).isEqualTo(NOW.plusSeconds(3_600));
        assertThat(restored.priority()).isEqualTo(41);
        assertThat(restored.restoredFromUuid()).isEqualTo(source.getUuid());
        assertThat(restored.createdAt()).isEqualTo(NOW);
        assertThat(restored.createdBy()).isEqualTo("restorer-id");
        assertThat(restored.updatedAt()).isEqualTo(NOW);
        assertThat(restored.updatedBy()).isEqualTo("restorer-id");
        assertThat(restored.publishedAt()).isEqualTo(NOW);
        assertThat(restored.publishedBy()).isEqualTo("restorer-id");
        assertThat(restored.presentationHash()).isEqualTo(hasher.hash(repository.findById(restored.uuid()).orElseThrow()));
        assertThat(restored.disabledAt()).isNull();
        assertThat(restored.disabledBy()).isNull();

        BannerOccurrence archived = repository.findById(source.getUuid()).orElseThrow();
        assertThat(archived.getStatus()).isEqualTo(BannerStatus.ARCHIVED);
        assertThat(archived.getArchivedAt()).isEqualTo(NOW);
        assertThat(archived.getArchivedBy()).isEqualTo("restorer-id");
        assertThat(archived.getUpdatedAt()).isEqualTo(NOW);
        assertThat(archived.getUpdatedBy()).isEqualTo("restorer-id");
        assertThat(archived.getHtmlContent()).isEqualTo(source.getHtmlContent());
        assertThat(archived.getPriority()).isEqualTo(source.getPriority());
        assertThat(archived.getDisabledAt()).isEqualTo(source.getDisabledAt());
        assertThat(archived.getRestoredFromUuid()).isEqualTo(source.getRestoredFromUuid());
        assertThat(versionsFor(versionRepository, source.getUuid())).hasSize(1);

        List<BannerVersion> restoredVersions = versionsFor(versionRepository, restored.uuid());
        assertThat(restoredVersions).hasSize(1);
        assertThat(restoredVersions.getFirst().getVersionNumber()).isEqualTo(1);
        assertThat(restoredVersions.getFirst().getPresentationHash()).isEqualTo(restored.presentationHash());
        assertThat(restoredVersions.getFirst().getActor()).isEqualTo("restorer-id");
        assertThat(allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority()).isEqualTo(42);
    }

    @Test
    void restoresAnExpiredOccurrenceAtTheEndExclusiveBoundaryWithAFutureStart() {
        BannerOccurrence source = repository.saveAndFlush(banner(BannerStatus.PUBLISHED, NOW.minusSeconds(600), NOW, 9, "Expired"));
        Instant futureStart = NOW.plusSeconds(600);

        ManagementBannerDto restored = service.restore(source.getUuid(), request(futureStart, null), RESTORER);

        assertThat(restored.lifecycle()).isEqualTo(BannerLifecycle.SCHEDULED);
        assertThat(restored.startAt()).isEqualTo(futureStart);
        assertThat(repository.findById(source.getUuid()).orElseThrow().getStatus()).isEqualTo(BannerStatus.ARCHIVED);
    }

    private static Stream<Arguments> rejectedOccurrences() {
        return Stream.of(
            Arguments.of("saved", BannerStatus.SAVED, null, null),
            Arguments.of("active", BannerStatus.PUBLISHED, NOW.minusSeconds(600), null),
            Arguments.of("scheduled", BannerStatus.PUBLISHED, NOW.plusSeconds(600), null),
            Arguments.of("archived", BannerStatus.ARCHIVED, NOW.minusSeconds(600), NOW.minusSeconds(60))
        );
    }

    @ParameterizedTest(name = "rejects a {0} occurrence")
    @MethodSource("rejectedOccurrences")
    void rejectsIneligibleSourcesWithoutWriting(
        String label, BannerStatus status, Instant startAt, Instant endAt
    ) {
        BannerOccurrence source = repository.saveAndFlush(banner(status, startAt, endAt, 7, label));
        int nextPriority = allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority();

        PicsureException exception = assertThrows(PicsureException.class, () -> service.restore(source.getUuid(), request(null, null), RESTORER));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repository.count()).isOne();
        assertThat(repository.findById(source.getUuid()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(versionRepository.count()).isZero();
        assertThat(allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority())
            .isEqualTo(nextPriority);
    }

    @Test
    void rejectsUnknownSourceWithoutWriting() {
        PicsureException exception = assertThrows(
            PicsureException.class,
            () -> service.restore(UUID.fromString("00000000-0000-0000-0000-000000000099"), request(null, null), RESTORER)
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repository.count()).isZero();
        assertThat(versionRepository.count()).isZero();
    }

    @Test
    void rejectsAnExplicitStartAtTheCurrentInstantWithoutChangingTheSourceOrAllocator() {
        BannerOccurrence source = repository.saveAndFlush(banner(BannerStatus.DISABLED, NOW.minusSeconds(600), null, 7, "Disabled"));

        PicsureException exception = assertThrows(PicsureException.class, () -> service.restore(source.getUuid(), request(NOW, null), RESTORER));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(source.getUuid()).orElseThrow().getStatus()).isEqualTo(BannerStatus.DISABLED);
        assertThat(repository.count()).isOne();
        assertThat(allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority()).isOne();
    }

    private static BannerOccurrence banner(BannerStatus status, Instant startAt, Instant endAt, int priority, String title) {
        return new BannerOccurrence().setStatus(status).setHtmlContent("<p>" + title + " content</p>").setTitle(title)
            .setAppearance(BannerAppearance.WARNING).setIcon(BannerIcon.WARNING).setDismissible(false).setAudience(BannerAudience.SIGNED_IN)
            .setPlacement(BannerPlacement.SITE_TOP).setPageTargets(List.of(BannerPageTarget.all())).setStartAt(startAt).setEndAt(endAt)
            .setPriority(priority).setPresentationHash("hash-" + title).setCreatedAt(NOW.minusSeconds(1_800)).setCreatedBy("creator-id")
            .setUpdatedAt(NOW.minusSeconds(300)).setUpdatedBy("editor-id").setPublishedAt(startAt == null ? null : NOW.minusSeconds(1_200))
            .setPublishedBy(startAt == null ? null : "publisher-id");
    }

    private static PublishBannerRequest request(Instant startAt, Instant endAt) {
        return new PublishBannerRequest(
            "<p>Restored content</p>", " Restored notice ", BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true,
            BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            List.of(
                new BannerPageTarget(BannerPageTargetKind.PARAMETERIZED, "/studies/[studyId]/"),
                new BannerPageTarget(BannerPageTargetKind.EXACT, "/about"),
                new BannerPageTarget(BannerPageTargetKind.EXACT, "/about")
            ), startAt, endAt
        );
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
