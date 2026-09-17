package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class BannerPresentationHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BannerPresentationHasher hasher = new BannerPresentationHasher(objectMapper);

    @Test
    void hashUsesExactHtmlBytesAndNormalizedStructuredFields() throws Exception {
        PublishBannerRequest first = request("<p>Exact  bytes</p>", " Notice ", "[{\"kind\":\"EXACT\",\"path\":\"/help/\"}]");
        PublishBannerRequest sameMeaning = request("<p>Exact  bytes</p>", "Notice", "[{\"kind\":\"EXACT\",\"path\":\"/help\"}]");
        PublishBannerRequest changedHtmlBytes = request("<p>Exact bytes</p>", "Notice", "[{\"kind\":\"EXACT\",\"path\":\"/help\"}]");

        assertThat(hash(first)).isEqualTo(hash(sameMeaning)).isEqualTo("a57a362055168ce255c3a2e7665af1185383f15cd2bd22ab1500fbacdd94d42e");
        assertThat(hash(changedHtmlBytes)).isNotEqualTo(hash(first));
    }

    @Test
    void hashChangesWhenStructuredPresentationOrTargetingChanges() throws Exception {
        PublishBannerRequest original = request("<p>Same HTML</p>", "Notice", "[{\"kind\":\"ALL\"}]");
        PublishBannerRequest changedTitle = new PublishBannerRequest(
            original.htmlContent(), "Changed notice", original.appearance(), original.icon(), original.dismissible(), original.audience(),
            original.placement(), original.pageTargets()
        );
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

        assertThat(hash(changedTitle)).isNotEqualTo(hash(original));
        assertThat(hash(changedAppearance)).isNotEqualTo(hash(original));
        assertThat(hash(changedIcon)).isNotEqualTo(hash(original));
        assertThat(hash(changedDismissibility)).isNotEqualTo(hash(original));
        assertThat(hash(changedAudience)).isNotEqualTo(hash(original));
        assertThat(hash(changedTargets)).isNotEqualTo(hash(original));
    }

    @Test
    void hashExcludesScheduleAndPriority() throws Exception {
        BannerOccurrence original = occurrence(request("<p>Same HTML</p>", "Notice", "[{\"kind\":\"ALL\"}]"));
        BannerOccurrence changedStart = occurrence(request("<p>Same HTML</p>", "Notice", "[{\"kind\":\"ALL\"}]"))
            .setStartAt(Instant.parse("2026-08-28T12:00:00Z"));
        BannerOccurrence changedEnd = occurrence(request("<p>Same HTML</p>", "Notice", "[{\"kind\":\"ALL\"}]"))
            .setEndAt(Instant.parse("2026-08-29T12:00:00Z"));
        BannerOccurrence changedPriority = occurrence(request("<p>Same HTML</p>", "Notice", "[{\"kind\":\"ALL\"}]"))
            .setPriority(40);

        assertThat(hasher.hash(changedStart)).isEqualTo(hasher.hash(original));
        assertThat(hasher.hash(changedEnd)).isEqualTo(hasher.hash(original));
        assertThat(hasher.hash(changedPriority)).isEqualTo(hasher.hash(original));
    }

    @Test
    void presentationFieldOrderChangesTheDigest() throws Exception {
        BannerOccurrence original = occurrence(request("<p>Content</p>", "Title", "[{\"kind\":\"ALL\"}]"));
        BannerOccurrence transposed = occurrence(request("Title", "<p>Content</p>", "[{\"kind\":\"ALL\"}]"));

        assertThat(hasher.hash(original)).isNotEqualTo(hasher.hash(transposed));
    }

    @Test
    void requiredPresentationFieldsFailWithClearErrors() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> hasher.hash(null)).withMessage("Banner occurrence is required");
        assertMissing("htmlContent", occurrence(request("<p>Content</p>", "Title", "[{\"kind\":\"ALL\"}]")).setHtmlContent(null));
        assertMissing("appearance", occurrence(request("<p>Content</p>", "Title", "[{\"kind\":\"ALL\"}]")).setAppearance(null));
        assertMissing("icon", occurrence(request("<p>Content</p>", "Title", "[{\"kind\":\"ALL\"}]")).setIcon(null));
        assertMissing("audience", occurrence(request("<p>Content</p>", "Title", "[{\"kind\":\"ALL\"}]")).setAudience(null));
        assertMissing("placement", occurrence(request("<p>Content</p>", "Title", "[{\"kind\":\"ALL\"}]")).setPlacement(null));
        assertMissing("pageTargets", occurrence(request("<p>Content</p>", "Title", "[{\"kind\":\"ALL\"}]")).setPageTargets(null));
    }

    private void assertMissing(String field, BannerOccurrence occurrence) {
        assertThatIllegalArgumentException().isThrownBy(() -> hasher.hash(occurrence)).withMessage("Banner " + field + " is required");
    }

    private String hash(PublishBannerRequest request) {
        return hasher.hash(occurrence(request));
    }

    private BannerOccurrence occurrence(PublishBannerRequest request) {
        return new BannerOccurrence().setHtmlContent(request.htmlContent()).setTitle(request.title()).setAppearance(request.appearance())
            .setIcon(request.icon()).setDismissible(request.dismissible()).setAudience(request.audience()).setPlacement(request.placement())
            .setPageTargets(request.pageTargets());
    }

    private PublishBannerRequest request(String html, String title, String pageTargets) throws Exception {
        return new PublishBannerRequest(
            html, title, BannerAppearance.PRIMARY, BannerIcon.INFORMATION, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            objectMapper.readValue(pageTargets, new TypeReference<>() {})
        );
    }
}
