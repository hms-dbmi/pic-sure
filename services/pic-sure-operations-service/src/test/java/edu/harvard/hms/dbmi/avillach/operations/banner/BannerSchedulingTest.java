package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@SpringBootTest
class BannerSchedulingTest {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "admin-subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository repository;

    @Autowired
    private BannerVersionRepository versionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    @Qualifier("bannerClock")
    private MutableClock clock;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabaseAndResetClock() {
        versionRepository.deleteAll();
        repository.deleteAll();
        clock.set(NOW);
        reset(loggingClient);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedSchedules")
    void storesAcceptedUtcSchedules(
        String description, Instant selectedStart, Instant selectedEnd, Instant expectedStart, BannerLifecycle expectedLifecycle
    ) {
        ManagementBannerDto published = service.publish(request(selectedStart, selectedEnd), ADMIN);

        assertThat(published.startAt()).isEqualTo(expectedStart);
        assertThat(published.endAt()).isEqualTo(selectedEnd);
        assertThat(published.lifecycle()).isEqualTo(expectedLifecycle);
    }

    static Stream<Arguments> acceptedSchedules() {
        return Stream.of(
            Arguments.of("immediate server-now publication", null, null, NOW, BannerLifecycle.ACTIVE),
            Arguments.of(
                "future window", NOW.plusSeconds(60), NOW.plusSeconds(120), NOW.plusSeconds(60), BannerLifecycle.SCHEDULED
            ),
            Arguments.of("future no-end window", NOW.plusSeconds(60), null, NOW.plusSeconds(60), BannerLifecycle.SCHEDULED),
            Arguments.of(
                "fall-back earlier offset", Instant.parse("2026-11-01T05:30:00Z"), null,
                Instant.parse("2026-11-01T05:30:00Z"), BannerLifecycle.SCHEDULED
            ),
            Arguments.of(
                "fall-back later offset", Instant.parse("2026-11-01T06:30:00Z"), null,
                Instant.parse("2026-11-01T06:30:00Z"), BannerLifecycle.SCHEDULED
            )
        );
    }

    @ParameterizedTest(name = "at {0}, lifecycle is {1} and feed count is {2}")
    @MethodSource("windowBoundaries")
    void derivesLifecycleAndFeedFromTheInjectedClock(Instant observedAt, BannerLifecycle lifecycle, int activeCount) {
        Instant start = NOW.plusSeconds(60);
        Instant end = NOW.plusSeconds(180);
        ManagementBannerDto scheduled = service.publish(request(start, end), ADMIN);

        clock.set(observedAt);

        ManagementBannerDto managed = service.managedBanners().stream().filter(banner -> banner.uuid().equals(scheduled.uuid())).findFirst()
            .orElseThrow();
        assertThat(managed.lifecycle()).isEqualTo(lifecycle);
        assertThat(service.activeBanners()).hasSize(activeCount);
        assertThat(repository.findById(scheduled.uuid()).orElseThrow().getStatus()).isEqualTo(BannerStatus.PUBLISHED);
    }

    static Stream<Arguments> windowBoundaries() {
        return Stream.of(
            Arguments.of(NOW, BannerLifecycle.SCHEDULED, 0), Arguments.of(NOW.plusSeconds(60), BannerLifecycle.ACTIVE, 1),
            Arguments.of(NOW.plusSeconds(179), BannerLifecycle.ACTIVE, 1), Arguments.of(NOW.plusSeconds(180), BannerLifecycle.EXPIRED, 0)
        );
    }

    @Test
    void aScheduledBannerWithoutAnEndRemainsActiveAfterItsStart() {
        ManagementBannerDto scheduled = service.publish(request(NOW.plusSeconds(60), null), ADMIN);
        clock.set(NOW.plusSeconds(31_536_000));

        assertThat(service.managedBanners()).singleElement().satisfies(
            banner -> assertThat(banner.lifecycle()).isEqualTo(BannerLifecycle.ACTIVE)
        );
        assertThat(service.activeBanners()).extracting(ActiveBannerDto::uuid).containsExactly(scheduled.uuid());
    }

    @ParameterizedTest(name = "rejects start {0} and end {1}")
    @MethodSource("invalidWindows")
    void rejectsInvalidNewScheduleWindows(Instant start, Instant end) {
        PicsureException exception = assertThrows(PicsureException.class, () -> service.publish(request(start, end), ADMIN));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.count()).isZero();
        assertThat(versionRepository.count()).isZero();
    }

    static Stream<Arguments> invalidWindows() {
        return Stream.of(
            Arguments.of(NOW.minusSeconds(60), null), Arguments.of(NOW.plusSeconds(60), NOW.plusSeconds(60)),
            Arguments.of(NOW.plusSeconds(120), NOW.plusSeconds(60)), Arguments.of(null, NOW.minusSeconds(60)),
            Arguments.of(NOW.plusSeconds(61), null), Arguments.of(NOW.plusSeconds(60), NOW.plusSeconds(121))
        );
    }

    @Test
    void schedulingEmitsOneAuditAndCrossingEitherBoundaryEmitsNone() {
        ManagementBannerDto[] scheduled = new ManagementBannerDto[1];
        Instant start = NOW.plusSeconds(60);
        Instant end = NOW.plusSeconds(120);

        transactions.executeWithoutResult(status -> scheduled[0] = service.publish(request(start, end), ADMIN));

        verify(loggingClient).send(org.mockito.ArgumentMatchers.argThat(event -> "banner.scheduled".equals(event.getAction())));
        reset(loggingClient);
        clock.set(start);
        service.managedBanners();
        service.activeBanners();
        clock.set(end);
        service.managedBanners();
        service.activeBanners();
        verify(loggingClient, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any());
        assertThat(repository.findById(scheduled[0].uuid()).orElseThrow().getStatus()).isEqualTo(BannerStatus.PUBLISHED);
    }

    @Test
    void aSavedBannerCanKeepAFutureWindowAndPublishAsTheSameScheduledOccurrence() {
        Instant start = NOW.plusSeconds(60);
        Instant end = NOW.plusSeconds(120);
        ManagementBannerDto saved = service.saveDraft(request(start, end), ADMIN);

        assertThat(saved.status()).isEqualTo(BannerStatus.SAVED);
        assertThat(saved.startAt()).isEqualTo(start);
        assertThat(service.activeBanners()).isEmpty();

        ManagementBannerDto scheduled = service.publishDraft(saved.uuid(), request(start, end), ADMIN);

        assertThat(scheduled.uuid()).isEqualTo(saved.uuid());
        assertThat(scheduled.lifecycle()).isEqualTo(BannerLifecycle.SCHEDULED);
        assertThat(scheduled.startAt()).isEqualTo(start);
        assertThat(scheduled.endAt()).isEqualTo(end);
    }

    @Test
    void publishedContentEditsPreserveTheExistingWindowUntilTheReschedulingTicket() {
        Instant originalStart = NOW.plusSeconds(60);
        Instant originalEnd = NOW.plusSeconds(120);
        ManagementBannerDto published = service.publish(request(originalStart, originalEnd), ADMIN);
        PublishBannerRequest attemptedReschedule = new PublishBannerRequest(
            "<p>Corrected scheduled maintenance</p>", "Maintenance", BannerAppearance.WARNING, BannerIcon.WARNING, true,
            BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("kind", "ALL")), NOW.plusSeconds(180),
            NOW.plusSeconds(240)
        );

        ManagementBannerDto updated = service.update(published.uuid(), attemptedReschedule, ADMIN);

        assertThat(updated.htmlContent()).isEqualTo("<p>Corrected scheduled maintenance</p>");
        assertThat(updated.startAt()).isEqualTo(originalStart);
        assertThat(updated.endAt()).isEqualTo(originalEnd);
    }

    private PublishBannerRequest request(Instant startAt, Instant endAt) {
        return new PublishBannerRequest(
            "<p>Scheduled maintenance</p>", "Maintenance", BannerAppearance.WARNING, BannerIcon.WARNING, true,
            BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("kind", "ALL")), startAt, endAt
        );
    }

    @TestConfiguration
    static class MutableClockConfiguration {

        @Bean
        @Primary
        @Qualifier("bannerClock")
        MutableClock mutableClock() {
            return new MutableClock(NOW);
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
