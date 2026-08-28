package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static edu.harvard.hms.dbmi.avillach.operations.banner.BannerVersionTestSupport.versionsFor;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@SpringBootTest
class BannerVersioningTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-27T12:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-27T13:00:00Z");
    private static final GatewayUser FIRST_ADMIN =
        new GatewayUser("first-admin", "first-subject", "first@example.org", "ADMIN", Set.of("ADMIN"));
    private static final GatewayUser SECOND_ADMIN =
        new GatewayUser("second-admin", "second-subject", "second@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository bannerRepository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("bannerClock")
    private MutableClock clock;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabase() {
        versionRepository.deleteAll();
        bannerRepository.deleteAll();
        clock.set(PUBLISHED_AT);
    }

    @Test
    void firstPublicationCreatesVersionOneWithTheExactPublishedState() throws Exception {
        PublishBannerRequest request = request(
            "<p>Exact bytes:\r\n  two spaces</p>", " Notice ", BannerAppearance.WARNING, BannerIcon.INFORMATION, false,
            BannerAudience.SIGNED_IN, "[{\"kind\":\"EXACT\",\"path\":\"/help\"}]"
        );

        ManagementBannerDto published = service.publish(request, FIRST_ADMIN);

        List<BannerVersion> versions = versionsFor(versionRepository, published.uuid());
        assertThat(versions).singleElement().satisfies(version -> {
            assertThat(version.getVersionNumber()).isEqualTo(1);
            assertThat(version.getBannerUuid()).isEqualTo(published.uuid());
            assertThat(version.getHtmlContent()).isEqualTo("<p>Exact bytes:\r\n  two spaces</p>");
            assertThat(version.getTitle()).isEqualTo("Notice");
            assertThat(version.getAppearance()).isEqualTo(BannerAppearance.WARNING);
            assertThat(version.getIcon()).isEqualTo(BannerIcon.INFORMATION);
            assertThat(version.isDismissible()).isFalse();
            assertThat(version.getAudience()).isEqualTo(BannerAudience.SIGNED_IN);
            assertThat(version.getPlacement()).isEqualTo(BannerPlacement.SITE_TOP);
            assertThat(version.getPageTargets()).isEqualTo(request.pageTargets());
            assertThat(version.getStartAt()).isEqualTo(PUBLISHED_AT);
            assertThat(version.getEndAt()).isNull();
            assertThat(version.getPresentationHash()).isEqualTo(published.presentationHash());
            assertThat(version.getEffectiveAt()).isEqualTo(PUBLISHED_AT);
            assertThat(version.getActor()).isEqualTo("first-admin");
        });
    }

    @Test
    void materialEditAppendsAnImmutableVersionAndPreservesExactPriorReconstruction() throws Exception {
        PublishBannerRequest original = request(
            "<p>Original  bytes</p>", "Original", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE,
            "[{\"kind\":\"ALL\"}]"
        );
        ManagementBannerDto published = service.publish(original, FIRST_ADMIN);
        clock.set(UPDATED_AT);
        PublishBannerRequest changed = request(
            "<p>Corrected bytes</p>", "Corrected", BannerAppearance.ERROR, BannerIcon.ERROR, false, BannerAudience.SIGNED_OUT,
            "[{\"kind\":\"EXACT\",\"path\":\"/status\"}]"
        );

        ManagementBannerDto updated = service.update(published.uuid(), changed, SECOND_ADMIN);

        assertThat(updated.uuid()).isEqualTo(published.uuid());
        assertThat(updated.htmlContent()).isEqualTo(changed.htmlContent());
        assertThat(updated.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(updated.updatedBy()).isEqualTo("second-admin");
        assertThat(updated.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(updated.publishedBy()).isEqualTo("first-admin");
        assertThat(updated.presentationHash()).isNotEqualTo(published.presentationHash());

        List<BannerVersion> versions = versionsFor(versionRepository, published.uuid());
        assertThat(versions).extracting(BannerVersion::getVersionNumber).containsExactly(1, 2);
        BannerVersion prior = versions.getFirst();
        assertThat(prior.getHtmlContent()).isEqualTo(original.htmlContent());
        assertThat(prior.getTitle()).isEqualTo(original.title());
        assertThat(prior.getAppearance()).isEqualTo(original.appearance());
        assertThat(prior.getIcon()).isEqualTo(original.icon());
        assertThat(prior.isDismissible()).isEqualTo(original.dismissible());
        assertThat(prior.getAudience()).isEqualTo(original.audience());
        assertThat(prior.getPlacement()).isEqualTo(original.placement());
        assertThat(prior.getPageTargets()).isEqualTo(original.pageTargets());
        assertThat(prior.getStartAt()).isEqualTo(PUBLISHED_AT);
        assertThat(prior.getEndAt()).isNull();
        assertThat(prior.getPresentationHash()).isEqualTo(published.presentationHash());
        assertThat(prior.getEffectiveAt()).isEqualTo(PUBLISHED_AT);
        assertThat(prior.getActor()).isEqualTo("first-admin");

        BannerVersion current = versions.get(1);
        assertThat(current.getHtmlContent()).isEqualTo(changed.htmlContent());
        assertThat(current.getPresentationHash()).isEqualTo(updated.presentationHash());
        assertThat(current.getEffectiveAt()).isEqualTo(UPDATED_AT);
        assertThat(current.getActor()).isEqualTo("second-admin");
    }

    @Test
    void normalizedNoOpReturnsTheAuthoritativeOccurrenceWithoutAppendingHistory() throws Exception {
        PublishBannerRequest original = request(
            "<p>Same exact bytes</p>", "Notice", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE,
            "[{\"kind\":\"SUBTREE\",\"path\":\"/admin\"},{\"kind\":\"EXACT\",\"path\":\"/help\"}]"
        );
        ManagementBannerDto published = service.publish(original, FIRST_ADMIN);
        clock.set(UPDATED_AT);
        PublishBannerRequest normalizedNoOp = request(
            original.htmlContent(), " Notice ", original.appearance(), original.icon(), original.dismissible(), original.audience(),
            "[{\"path\":\"/help/\",\"kind\":\"EXACT\"},{\"kind\":\"SUBTREE\",\"path\":\"/admin/\"}]"
        );

        ManagementBannerDto result = service.update(published.uuid(), normalizedNoOp, SECOND_ADMIN);

        assertThat(result).isEqualTo(published);
        assertThat(versionsFor(versionRepository, published.uuid())).hasSize(1);
    }

    @Test
    void exactHtmlByteChangeIsMaterialEvenWhenRenderedTextMatches() throws Exception {
        ManagementBannerDto published = service.publish(
            request(
                "<p>Two spaces</p>", "Notice", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE,
                "[{\"kind\":\"ALL\"}]"
            ), FIRST_ADMIN
        );
        clock.set(UPDATED_AT);

        ManagementBannerDto updated = service.update(
            published.uuid(),
            request(
                "<p>Two  spaces</p>", "Notice", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE,
                "[{\"kind\":\"ALL\"}]"
            ), SECOND_ADMIN
        );

        assertThat(updated.presentationHash()).isNotEqualTo(published.presentationHash());
        assertThat(versionsFor(versionRepository, published.uuid())).hasSize(2);
    }

    @Test
    void updatePublishedRejectsAnUnknownUuidWithoutWritingAVersion() throws Exception {
        PicsureException exception = assertThrows(
            PicsureException.class,
            () -> service.update(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                request(
                    "<p>Missing</p>", "Missing", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE,
                    "[{\"kind\":\"ALL\"}]"
                ), FIRST_ADMIN
            )
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(versionRepository.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = BannerStatus.class, names = {"DISABLED", "ARCHIVED"})
    void updateRejectsDisabledAndArchivedWithoutWritingAVersion(BannerStatus status) throws Exception {
        ManagementBannerDto published = service.publish(
            request(
                "<p>Original</p>", "Original", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE,
                "[{\"kind\":\"ALL\"}]"
            ), FIRST_ADMIN
        );
        BannerOccurrence occurrence = bannerRepository.findById(published.uuid()).orElseThrow().setStatus(status);
        bannerRepository.saveAndFlush(occurrence);
        versionRepository.deleteAll();

        PicsureException exception = assertThrows(
            PicsureException.class,
            () -> service.update(
                published.uuid(),
                request(
                    "<p>Rejected</p>", "Rejected", BannerAppearance.ERROR, BannerIcon.ERROR, false, BannerAudience.SIGNED_IN,
                    "[{\"kind\":\"ALL\"}]"
                ), SECOND_ADMIN
            )
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(versionRepository.count()).isZero();
    }

    @Test
    void lazyBootstrapFallsBackToStoredUpdateTimeWhenPublicationTimeIsMissing() throws Exception {
        ManagementBannerDto published = service.publish(
            request(
                "<p>Legacy state</p>", "Legacy", BannerAppearance.WARNING, BannerIcon.WARNING, false, BannerAudience.SIGNED_IN,
                "[{\"kind\":\"EXACT\",\"path\":\"/legacy\"}]"
            ), FIRST_ADMIN
        );
        versionRepository.deleteAll();
        BannerOccurrence legacy = bannerRepository.findById(published.uuid()).orElseThrow();
        Instant storedUpdateTime = PUBLISHED_AT.plusSeconds(900);
        legacy.setPublishedAt(null).setPublishedBy(null).setUpdatedAt(storedUpdateTime);
        bannerRepository.saveAndFlush(legacy);
        clock.set(UPDATED_AT);

        service.update(
            published.uuid(),
            request(
                "<p>Corrected state</p>", "Corrected", BannerAppearance.ERROR, BannerIcon.ERROR, true, BannerAudience.EVERYONE,
                "[{\"kind\":\"ALL\"}]"
            ), SECOND_ADMIN
        );

        List<BannerVersion> versions = versionsFor(versionRepository, published.uuid());
        assertThat(versions).extracting(BannerVersion::getVersionNumber).containsExactly(1, 2);
        assertThat(versions.getFirst().getHtmlContent()).isEqualTo("<p>Legacy state</p>");
        assertThat(versions.getFirst().getEffectiveAt()).isEqualTo(storedUpdateTime);
        assertThat(versions.getFirst().getActor()).isEqualTo(BannerService.SYSTEM_MIGRATION_ACTOR);
    }

    private PublishBannerRequest request(
        String htmlContent, String title, BannerAppearance appearance, BannerIcon icon, boolean dismissible, BannerAudience audience,
        String pageTargets
    ) throws Exception {
        return new PublishBannerRequest(
            htmlContent, title, appearance, icon, dismissible, audience, BannerPlacement.SITE_TOP,
            objectMapper.readValue(pageTargets, new TypeReference<>() {})
        );
    }

    @TestConfiguration
    static class MutableClockConfiguration {

        @Bean
        @Primary
        @Qualifier("bannerClock")
        MutableClock mutableClock() {
            return new MutableClock(PUBLISHED_AT);
        }
    }

    static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Banner test clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
