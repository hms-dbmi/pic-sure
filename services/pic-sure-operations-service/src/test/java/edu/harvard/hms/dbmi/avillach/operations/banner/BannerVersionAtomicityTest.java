package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@SpringBootTest
class BannerVersionAtomicityTest {

    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Autowired
    private BannerService service;

    @Autowired
    private BannerRepository bannerRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BannerPresentationHasher hasher;

    @MockitoSpyBean
    private BannerVersionRepository versionRepository;

    @MockitoBean
    private LoggingClient loggingClient;

    @BeforeEach
    void cleanDatabase() {
        versionRepository.deleteAll();
        bannerRepository.deleteAll();
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
    void lazyBootstrapFailureRollsBackWithoutChangingAnOldBinaryOccurrence() {
        Instant publishedAt = Instant.parse("2026-08-27T12:00:00Z");
        BannerOccurrence oldBinary = new BannerOccurrence().setStatus(BannerStatus.PUBLISHED).setHtmlContent("<p>Original</p>")
            .setTitle("Notice").setAppearance(BannerAppearance.PRIMARY).setIcon(BannerIcon.NONE).setDismissible(true)
            .setAudience(BannerAudience.EVERYONE).setPlacement(BannerPlacement.SITE_TOP)
            .setPageTargets(objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("kind", "ALL"))).setStartAt(publishedAt)
            .setPriority(1).setCreatedAt(publishedAt).setCreatedBy("old-admin").setUpdatedAt(publishedAt).setUpdatedBy("old-admin")
            .setPublishedAt(publishedAt).setPublishedBy("old-admin");
        oldBinary.setPresentationHash(hasher.hash(oldBinary));
        oldBinary = bannerRepository.saveAndFlush(oldBinary);
        UUID bannerUuid = oldBinary.getUuid();
        doThrow(new IllegalStateException("version storage unavailable")).when(versionRepository).saveAndFlush(any(BannerVersion.class));

        assertThatThrownBy(() -> service.update(bannerUuid, request("<p>Changed</p>"), ADMIN)).isInstanceOf(IllegalStateException.class)
            .hasMessage("version storage unavailable");

        BannerOccurrence occurrence = bannerRepository.findById(bannerUuid).orElseThrow();
        assertThat(occurrence.getHtmlContent()).isEqualTo("<p>Original</p>");
        assertThat(versionRepository.findAll()).isEmpty();
    }

    private PublishBannerRequest request(String htmlContent) {
        return request(htmlContent, null, null);
    }

    private PublishBannerRequest request(String htmlContent, Instant startAt, Instant endAt) {
        return new PublishBannerRequest(
            htmlContent, "Notice", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("kind", "ALL")), startAt, endAt
        );
    }
}
