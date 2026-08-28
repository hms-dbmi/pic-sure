package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.List;
import java.util.UUID;

public record ActiveBannerDto(
    UUID uuid, String htmlContent, String title, BannerAppearance appearance, BannerIcon icon, boolean dismissible, BannerAudience audience,
    BannerPlacement placement, List<BannerPageTarget> pageTargets, Integer priority, String presentationHash
) {
    static ActiveBannerDto from(BannerOccurrence banner) {
        return new ActiveBannerDto(
            banner.getUuid(), banner.getHtmlContent(), banner.getTitle(), banner.getAppearance(), banner.getIcon(), banner.isDismissible(),
            banner.getAudience(), banner.getPlacement(), banner.getPageTargets(), banner.getPriority(), banner.getPresentationHash()
        );
    }
}
