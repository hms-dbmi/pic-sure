package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class BannerPresentationHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BannerPresentationHasher hasher = new BannerPresentationHasher(objectMapper);

    @Test
    void hashUsesExactHtmlBytesAndNormalizedStructuredFields() throws Exception {
        PublishBannerRequest first =
            request("<p>Exact  bytes</p>", " Notice ", "[{\"path\":\"/help\",\"kind\":\"EXACT\"},{\"kind\":\"ALL\"}]");
        PublishBannerRequest sameMeaning =
            request("<p>Exact  bytes</p>", "Notice", "[{\"kind\":\"ALL\"},{\"kind\":\"EXACT\",\"path\":\"/help\"}]");
        PublishBannerRequest changedHtmlBytes =
            request("<p>Exact bytes</p>", "Notice", "[{\"kind\":\"ALL\"},{\"kind\":\"EXACT\",\"path\":\"/help\"}]");

        assertThat(hash(first)).isEqualTo(hash(sameMeaning)).matches("[0-9a-f]{64}");
        assertThat(hash(changedHtmlBytes)).isNotEqualTo(hash(first));
    }

    @Test
    void hashChangesWhenStructuredPresentationOrTargetingChanges() throws Exception {
        PublishBannerRequest original = request("<p>Same HTML</p>", "Notice", "[{\"kind\":\"ALL\"}]");
        PublishBannerRequest changedAppearance = new PublishBannerRequest(
            original.htmlContent(), original.title(), BannerAppearance.WARNING, original.icon(), original.dismissible(),
            original.audience(), original.placement(), original.pageTargets()
        );
        PublishBannerRequest changedIcon = new PublishBannerRequest(
            original.htmlContent(), original.title(), original.appearance(), BannerIcon.ERROR, original.dismissible(), original.audience(),
            original.placement(), original.pageTargets()
        );
        PublishBannerRequest changedDismissibility = new PublishBannerRequest(
            original.htmlContent(), original.title(), original.appearance(), original.icon(), false, original.audience(),
            original.placement(), original.pageTargets()
        );
        PublishBannerRequest changedAudience = new PublishBannerRequest(
            original.htmlContent(), original.title(), original.appearance(), original.icon(), original.dismissible(),
            BannerAudience.SIGNED_IN, original.placement(), original.pageTargets()
        );
        PublishBannerRequest changedTargets = request("<p>Same HTML</p>", "Notice", "[{\"kind\":\"EXACT\",\"path\":\"/help\"}]");

        assertThat(hash(changedAppearance)).isNotEqualTo(hash(original));
        assertThat(hash(changedIcon)).isNotEqualTo(hash(original));
        assertThat(hash(changedDismissibility)).isNotEqualTo(hash(original));
        assertThat(hash(changedAudience)).isNotEqualTo(hash(original));
        assertThat(hash(changedTargets)).isNotEqualTo(hash(original));
    }

    private String hash(PublishBannerRequest request) {
        return hasher.hash(
            request.htmlContent(), request.title(), request.appearance(), request.icon(), request.dismissible(), request.audience(),
            request.placement(), request.pageTargets()
        );
    }

    private PublishBannerRequest request(String html, String title, String pageTargets) throws Exception {
        return new PublishBannerRequest(
            html, title, BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            objectMapper.readTree(pageTargets)
        );
    }
}
