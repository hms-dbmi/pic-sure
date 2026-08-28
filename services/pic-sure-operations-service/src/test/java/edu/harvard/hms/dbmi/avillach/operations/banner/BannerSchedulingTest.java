package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
            Arguments.of("future window", NOW.plusSeconds(60), NOW.plusSeconds(120), NOW.plusSeconds(60), BannerLifecycle.SCHEDULED),
            Arguments.of("future no-end window", NOW.plusSeconds(60), null, NOW.plusSeconds(60), BannerLifecycle.SCHEDULED),
            Arguments.of(
                "fall-back earlier offset", Instant.parse("2026-11-01T05:30:00Z"), null, Instant.parse("2026-11-01T05:30:00Z"),
                BannerLifecycle.SCHEDULED
            ),
            Arguments.of(
                "fall-back later offset", Instant.parse("2026-11-01T06:30:00Z"), null, Instant.parse("2026-11-01T06:30:00Z"),
                BannerLifecycle.SCHEDULED
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

        ManagementBannerDto managed =
            service.managedBanners().stream().filter(banner -> banner.uuid().equals(scheduled.uuid())).findFirst().orElseThrow();
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

        assertThat(service.managedBanners()).singleElement()
            .satisfies(banner -> assertThat(banner.lifecycle()).isEqualTo(BannerLifecycle.ACTIVE));
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
    void unchangedHistoricalPublishedStartIsAValidNoOpWithoutAnotherVersion() {
        clock.set(NOW.plusSeconds(30));
        ManagementBannerDto published = service.publish(request(null, null), ADMIN);
        clock.set(NOW.plusSeconds(90));

        ManagementBannerDto unchanged = service.update(published.uuid(), request(published.startAt(), null), ADMIN);

        assertThat(unchanged).isEqualTo(published);
        assertThat(BannerVersionTestSupport.versionsFor(versionRepository, published.uuid())).hasSize(1);
    }

    @Test
    void changingPublishedStartToADifferentPastMinuteIsRejectedWithoutWrites() {
        ManagementBannerDto published = service.publish(request(null, null), ADMIN);
        clock.set(NOW.plusSeconds(120));

        PicsureException exception =
            assertThrows(PicsureException.class, () -> service.update(published.uuid(), request(NOW.plusSeconds(60), null), ADMIN));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(published.uuid()).orElseThrow().getStartAt()).isEqualTo(published.startAt());
        assertThat(BannerVersionTestSupport.versionsFor(versionRepository, published.uuid())).hasSize(1);
    }

    @ParameterizedTest(name = "published schedule edit derives {0}")
    @MethodSource("publishedScheduleTransitions")
    void publishedScheduleEditsKeepTheOccurrenceAndAppendTheDerivedStateVersion(
        BannerLifecycle expectedLifecycle, Instant editTime, Instant startAt, Instant endAt
    ) {
        ManagementBannerDto published = service.publish(request(null, null), ADMIN);
        clock.set(editTime);

        ManagementBannerDto updated = service.update(published.uuid(), request(startAt, endAt), ADMIN);

        assertThat(updated.uuid()).isEqualTo(published.uuid());
        assertThat(updated.lifecycle()).isEqualTo(expectedLifecycle);
        assertThat(updated.startAt()).isEqualTo(startAt);
        assertThat(updated.endAt()).isEqualTo(endAt);
        assertThat(BannerVersionTestSupport.versionsFor(versionRepository, published.uuid())).extracting(BannerVersion::getVersionNumber)
            .containsExactly(1, 2);
        assertThat(BannerVersionTestSupport.versionsFor(versionRepository, published.uuid()).getLast()).satisfies(version -> {
            assertThat(version.getStartAt()).isEqualTo(startAt);
            assertThat(version.getEndAt()).isEqualTo(endAt);
        });
    }

    static Stream<Arguments> publishedScheduleTransitions() {
        return Stream.of(
            Arguments.of(BannerLifecycle.SCHEDULED, NOW.plusSeconds(60), NOW.plusSeconds(180), null),
            Arguments.of(BannerLifecycle.EXPIRED, NOW.plusSeconds(180), NOW, NOW.plusSeconds(60))
        );
    }

    @Test
    void clearingAPublishedEndMakesTheSameOccurrenceNonExpiring() {
        Instant start = NOW.plusSeconds(60);
        Instant originalEnd = NOW.plusSeconds(180);
        ManagementBannerDto published = service.publish(request(start, originalEnd), ADMIN);
        clock.set(start);

        ManagementBannerDto updated = service.update(published.uuid(), request(start, null), ADMIN);

        assertThat(updated.uuid()).isEqualTo(published.uuid());
        assertThat(updated.lifecycle()).isEqualTo(BannerLifecycle.ACTIVE);
        assertThat(updated.endAt()).isNull();
        assertThat(BannerVersionTestSupport.versionsFor(versionRepository, published.uuid())).extracting(BannerVersion::getEndAt)
            .containsExactly(originalEnd, null);
    }

    @ParameterizedTest(name = "expired occurrence cannot be rescheduled to {0}")
    @MethodSource("expiredRevivalSchedules")
    void expiredPublishedOccurrencesCannotChangeSchedule(BannerLifecycle attemptedLifecycle, Instant requestedStart, Instant requestedEnd) {
        Instant originalStart = NOW.plusSeconds(60);
        Instant originalEnd = NOW.plusSeconds(120);
        ManagementBannerDto published = service.publish(request(originalStart, originalEnd), ADMIN);
        clock.set(NOW.plusSeconds(180));
        reset(loggingClient);

        PicsureException exception =
            assertThrows(PicsureException.class, () -> service.update(published.uuid(), request(requestedStart, requestedEnd), ADMIN));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(repository.findById(published.uuid()).orElseThrow()).satisfies(occurrence -> {
            assertThat(occurrence.getStartAt()).isEqualTo(originalStart);
            assertThat(occurrence.getEndAt()).isEqualTo(originalEnd);
        });
        assertThat(BannerVersionTestSupport.versionsFor(versionRepository, published.uuid())).hasSize(1);
        verifyNoInteractions(loggingClient);
    }

    static Stream<Arguments> expiredRevivalSchedules() {
        return Stream.of(
            Arguments.of(BannerLifecycle.ACTIVE, NOW.plusSeconds(60), null),
            Arguments.of(BannerLifecycle.SCHEDULED, NOW.plusSeconds(240), null)
        );
    }

    @Test
    void expiredPublishedOccurrencesStillAllowContentEditsWithTheirExactSchedule() {
        Instant start = NOW.plusSeconds(60);
        Instant end = NOW.plusSeconds(120);
        ManagementBannerDto published = service.publish(request(start, end), ADMIN);
        clock.set(NOW.plusSeconds(180));

        ManagementBannerDto updated = service.update(published.uuid(), request("<p>Corrected expired maintenance</p>", start, end), ADMIN);

        assertThat(updated.lifecycle()).isEqualTo(BannerLifecycle.EXPIRED);
        assertThat(updated.htmlContent()).isEqualTo("<p>Corrected expired maintenance</p>");
        assertThat(updated.startAt()).isEqualTo(start);
        assertThat(updated.endAt()).isEqualTo(end);
        assertThat(BannerVersionTestSupport.versionsFor(versionRepository, published.uuid())).hasSize(2);
    }

    @Test
    void scheduleEditEmitsOneUpdateAuditAndDerivedBoundaryCrossingsEmitNone() {
        ManagementBannerDto published = service.publish(request(null, null), ADMIN);
        reset(loggingClient);
        Instant futureStart = NOW.plusSeconds(120);
        ManagementBannerDto[] updated = new ManagementBannerDto[1];

        transactions.executeWithoutResult(status -> updated[0] = service.update(published.uuid(), request(futureStart, null), ADMIN));

        verify(loggingClient).send(org.mockito.ArgumentMatchers.argThat(event -> "banner.updated".equals(event.getAction())));
        reset(loggingClient);
        clock.set(futureStart);
        service.managedBanners();
        service.activeBanners();
        verifyNoInteractions(loggingClient);
        assertThat(updated[0].lifecycle()).isEqualTo(BannerLifecycle.SCHEDULED);
    }

    private PublishBannerRequest request(Instant startAt, Instant endAt) {
        return request("<p>Scheduled maintenance</p>", startAt, endAt);
    }

    private PublishBannerRequest request(String htmlContent, Instant startAt, Instant endAt) {
        return new PublishBannerRequest(
            htmlContent, "Maintenance", BannerAppearance.WARNING, BannerIcon.WARNING, true, BannerAudience.EVERYONE,
            BannerPlacement.SITE_TOP, List.of(BannerPageTarget.all()), startAt, endAt
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
