package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

@Service
public class BannerService {

    private final BannerRepository repository;
    private final Clock clock;
    private final BannerPresentationHasher hasher;
    private final BannerAuditService auditService;

    public BannerService(
        BannerRepository repository, @Qualifier("bannerClock") Clock clock, BannerPresentationHasher hasher, BannerAuditService auditService
    ) {
        this.repository = repository;
        this.clock = clock;
        this.hasher = hasher;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ActiveBannerDto> activeBanners() {
        Instant now = clock.instant();
        return repository.findActive(now).stream().map(ActiveBannerDto::from).toList();
    }

    @Transactional
    public BannerDto publish(PublishBannerRequest request, GatewayUser user) {
        if (!request.pageTargets().isArray()) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "Page targets must be an array");
        }
        Instant now = clock.instant();
        String actor = user.getUserId();
        String normalizedTitle = BannerPresentationHasher.normalizeTitle(request.title());
        BannerOccurrence banner = new BannerOccurrence().setStatus(BannerStatus.PUBLISHED).setHtmlContent(request.htmlContent())
            .setTitle(normalizedTitle.isEmpty() ? null : normalizedTitle).setAppearance(request.appearance()).setIcon(request.icon())
            .setDismissible(request.dismissible()).setAudience(request.audience()).setPlacement(request.placement())
            .setPageTargets(request.pageTargets().deepCopy()).setStartAt(now).setPriority(repository.findMaximumOrderablePriority(now) + 1)
            .setPresentationHash(
                hasher.hash(
                    request.htmlContent(), request.title(), request.appearance(), request.icon(), request.dismissible(), request.audience(),
                    request.placement(), request.pageTargets()
                )
            ).setCreatedAt(now).setCreatedBy(actor).setUpdatedAt(now).setUpdatedBy(actor).setPublishedAt(now).setPublishedBy(actor);
        BannerOccurrence saved = repository.saveAndFlush(banner);
        auditService.afterPublicationCommit(saved.getUuid(), now, saved.getPresentationHash(), actor);
        return BannerDto.from(saved);
    }
}
