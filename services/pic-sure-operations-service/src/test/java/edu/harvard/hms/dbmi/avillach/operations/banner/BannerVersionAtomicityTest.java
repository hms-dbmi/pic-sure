package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Set;

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

    @MockitoSpyBean
    private BannerVersionRepository versionRepository;

    @MockitoBean
    private LoggingClient loggingClient;

    @Test
    void versionAppendFailureRollsBackTheCurrentOccurrenceUpdate() {
        ManagementBannerDto published = service.publish(request("<p>Original</p>"), ADMIN);
        doThrow(new IllegalStateException("version storage unavailable")).when(versionRepository).saveAndFlush(any(BannerVersion.class));

        assertThatThrownBy(() -> service.update(published.uuid(), request("<p>Changed</p>"), ADMIN))
            .isInstanceOf(IllegalStateException.class).hasMessage("version storage unavailable");

        BannerOccurrence occurrence = bannerRepository.findById(published.uuid()).orElseThrow();
        assertThat(occurrence.getHtmlContent()).isEqualTo("<p>Original</p>");
        assertThat(occurrence.getPresentationHash()).isEqualTo(published.presentationHash());
        assertThat(versionRepository.findByBannerUuidOrderByVersionNumber(published.uuid())).hasSize(1);
    }

    private PublishBannerRequest request(String htmlContent) {
        return new PublishBannerRequest(
            htmlContent, "Notice", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("kind", "ALL"))
        );
    }
}
