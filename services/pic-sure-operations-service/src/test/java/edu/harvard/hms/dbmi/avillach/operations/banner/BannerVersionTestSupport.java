package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class BannerVersionTestSupport {

    private BannerVersionTestSupport() {}

    static List<BannerVersion> versionsFor(BannerVersionRepository repository, UUID bannerUuid) {
        return repository.findAll().stream().filter(version -> version.getBannerUuid().equals(bannerUuid))
            .sorted(Comparator.comparingInt(BannerVersion::getVersionNumber)).toList();
    }
}
