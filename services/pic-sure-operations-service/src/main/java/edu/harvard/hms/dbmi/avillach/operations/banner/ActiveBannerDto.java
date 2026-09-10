package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record ActiveBannerDto(
    UUID uuid, String htmlContent, String title, BannerAppearance appearance, BannerIcon icon, boolean dismissible, BannerAudience audience,
    BannerPlacement placement, List<BannerPageTarget> pageTargets, Integer priority, String presentationHash
) {
    static Optional<ActiveBannerDto> from(BannerOccurrence banner) {
        List<BannerPageTarget> pageTargets = banner.getPageTargets();
        if (pageTargets == null) {
            return Optional.empty();
        }
        return Optional.of(new ActiveBannerDto(
            banner.getUuid(), banner.getHtmlContent(), banner.getTitle(), banner.getAppearance(), banner.getIcon(), banner.isDismissible(),
            banner.getAudience(), banner.getPlacement(), pageTargets, banner.getPriority(), banner.getPresentationHash()
        ));
    }
}
