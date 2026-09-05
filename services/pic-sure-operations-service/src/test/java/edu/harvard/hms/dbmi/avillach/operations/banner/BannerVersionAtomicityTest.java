package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@SpringBootTest
class BannerVersionAtomicityTest {

    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerService service;

    @MockitoSpyBean
    private BannerRepository bannerRepository;

    @Autowired
    private BannerPriorityAllocatorRepository allocatorRepository;

    @MockitoSpyBean
    private BannerVersionRepository versionRepository;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabase() {
        versionRepository.deleteAll();
        bannerRepository.deleteAll();
        allocatorRepository.saveAndFlush(
            new BannerPriorityAllocator().setId(BannerPriorityAllocator.SINGLETON_ID).setNextPriority(1)
        );
    }

    @Test
    void versionAppendFailureRollsBackTheCurrentOccurrenceUpdate() {
        ManagementBannerDto published = service.publish(request("<p>Original</p>"), ADMIN);
        doThrow(new IllegalStateException("version storage unavailable")).when(versionRepository).saveAndFlush(any(BannerVersion.class));

        assertThatThrownBy(() -> service.update(published.uuid(), request("<p>Changed</p>"), ADMIN))
            .isInstanceOf(IllegalStateException.class).hasMessage("version storage unavailable");

        BannerOccurrence occurrence = bannerRepository.findById(published.uuid()).orElseThrow();
        assertThat(occurrence.getHtmlContent()).isEqualTo("<p>Original</p>");
        assertThat(occurrence.getPresentationHash()).isEqualTo(published.presentationHash());
        assertThat(versionRepository.findAll()).hasSize(1);
    }

    @Test
    void versionAppendFailureRollsBackAPublishedScheduleEdit() {
        ManagementBannerDto published = service.publish(request("<p>Original</p>"), ADMIN);
        Instant futureStart = published.startAt().plusSeconds(600).truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
        doThrow(new IllegalStateException("version storage unavailable")).when(versionRepository).saveAndFlush(any(BannerVersion.class));

        assertThatThrownBy(() -> service.update(published.uuid(), request("<p>Original</p>", futureStart, null), ADMIN))
            .isInstanceOf(IllegalStateException.class).hasMessage("version storage unavailable");

        BannerOccurrence occurrence = bannerRepository.findById(published.uuid()).orElseThrow();
        assertThat(occurrence.getStartAt()).isEqualTo(published.startAt());
        assertThat(occurrence.getEndAt()).isNull();
        assertThat(versionRepository.findAll()).hasSize(1);
    }

    @Test
    void destinationVersionFailureRollsBackTheEntireRestore() {
        ManagementBannerDto published = service.publish(request("<p>Original</p>"), ADMIN);
        service.disable(published.uuid(), ADMIN);
        int nextPriority = allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority();
        doThrow(new IllegalStateException("version storage unavailable")).when(versionRepository).saveAndFlush(any(BannerVersion.class));

        assertThatThrownBy(() -> service.restore(published.uuid(), request("<p>Restored</p>"), ADMIN))
            .isInstanceOf(IllegalStateException.class).hasMessage("version storage unavailable");

        BannerOccurrence source = bannerRepository.findById(published.uuid()).orElseThrow();
        assertThat(source.getStatus()).isEqualTo(BannerStatus.DISABLED);
        assertThat(source.getArchivedAt()).isNull();
        assertThat(bannerRepository.count()).isOne();
        assertThat(versionRepository.findAll()).hasSize(1);
        assertThat(allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority())
            .isEqualTo(nextPriority);
    }

    @Test
    void sourceArchiveFailureAfterDestinationVersionRollsBackTheEntireRestore() {
        ManagementBannerDto published = service.publish(request("<p>Original</p>"), ADMIN);
        service.disable(published.uuid(), ADMIN);
        int nextPriority = allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority();
        doThrow(new IllegalStateException("source archive unavailable")).when(bannerRepository).saveAndFlush(
            argThat(banner -> published.uuid().equals(banner.getUuid()) && banner.getStatus() == BannerStatus.ARCHIVED)
        );

        assertThatThrownBy(() -> service.restore(published.uuid(), request("<p>Restored</p>"), ADMIN))
            .isInstanceOf(IllegalStateException.class).hasMessage("source archive unavailable");

        BannerOccurrence source = bannerRepository.findById(published.uuid()).orElseThrow();
        assertThat(source.getStatus()).isEqualTo(BannerStatus.DISABLED);
        assertThat(source.getArchivedAt()).isNull();
        assertThat(bannerRepository.count()).isOne();
        assertThat(versionRepository.findAll()).hasSize(1);
        assertThat(allocatorRepository.findById(BannerPriorityAllocator.SINGLETON_ID).orElseThrow().getNextPriority())
            .isEqualTo(nextPriority);
    }

    private PublishBannerRequest request(String htmlContent) {
        return request(htmlContent, null, null);
    }

    private PublishBannerRequest request(String htmlContent, Instant startAt, Instant endAt) {
        return new PublishBannerRequest(
            htmlContent, "Notice", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            List.of(BannerPageTarget.all()), startAt, endAt
        );
    }
}
