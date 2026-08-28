package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;

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

    @Transactional(readOnly = true)
    public List<ManagementBannerDto> managedBanners() {
        Instant now = clock.instant();
        return repository.findAllManaged().stream().map(banner -> ManagementBannerDto.from(banner, now)).toList();
    }

    @Transactional
    public ManagementBannerDto publish(PublishBannerRequest request, GatewayUser user) {
        validate(request);
        Instant now = clock.instant();
        String actor = user.getUserId();
        BannerOccurrence banner = apply(request, new BannerOccurrence()).setStatus(BannerStatus.PUBLISHED).setStartAt(now)
            .setPriority(repository.findMaximumOrderablePriority(now) + 1).setCreatedAt(now).setCreatedBy(actor).setUpdatedAt(now)
            .setUpdatedBy(actor).setPublishedAt(now).setPublishedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        auditService.registerPublicationAudit(saved.getUuid(), now, saved.getPresentationHash(), actor);
        return ManagementBannerDto.from(saved, now);
    }

    @Transactional
    public ManagementBannerDto saveDraft(PublishBannerRequest request, GatewayUser user) {
        validate(request);
        Instant now = clock.instant();
        String actor = user.getUserId();
        BannerOccurrence banner = apply(request, new BannerOccurrence()).setStatus(BannerStatus.SAVED).setCreatedAt(now).setCreatedBy(actor)
            .setUpdatedAt(now).setUpdatedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        auditService.registerMutationAudit("banner.saved", saved.getUuid(), now, saved.getPresentationHash(), actor);
        return ManagementBannerDto.from(saved, now);
    }

    @Transactional
    public ManagementBannerDto updateDraft(UUID uuid, PublishBannerRequest request, GatewayUser user) {
        validate(request);
        BannerOccurrence banner = requireSaved(uuid);
        Instant now = clock.instant();
        String actor = user.getUserId();
        apply(request, banner).setUpdatedAt(now).setUpdatedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        auditService.registerMutationAudit("banner.updated", saved.getUuid(), now, saved.getPresentationHash(), actor);
        return ManagementBannerDto.from(saved, now);
    }

    @Transactional
    public ManagementBannerDto publishDraft(UUID uuid, PublishBannerRequest request, GatewayUser user) {
        validate(request);
        BannerOccurrence banner = requireSaved(uuid);
        Instant now = clock.instant();
        String actor = user.getUserId();
        apply(request, banner).setStatus(BannerStatus.PUBLISHED).setStartAt(now)
            .setPriority(repository.findMaximumOrderablePriority(now) + 1).setUpdatedAt(now).setUpdatedBy(actor).setPublishedAt(now)
            .setPublishedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        auditService.registerPublicationAudit(saved.getUuid(), now, saved.getPresentationHash(), actor);
        return ManagementBannerDto.from(saved, now);
    }

    private BannerOccurrence requireSaved(UUID uuid) {
        BannerOccurrence banner = repository.findById(uuid).orElseThrow(() -> PicsureExceptions.notFound("Banner", uuid));
        if (banner.getStatus() != BannerStatus.SAVED) {
            throw PicsureExceptions.conflict("Only saved banners can be changed before publication");
        }
        return banner;
    }

    private static void validate(PublishBannerRequest request) {
        if (!request.pageTargets().isArray()) {
            throw PicsureExceptions.badRequest("Page targets must be an array");
        }
    }

    private static BannerOccurrence apply(PublishBannerRequest request, BannerOccurrence banner) {
        String normalizedTitle = BannerPresentationHasher.normalizeTitle(request.title());
        return banner.setHtmlContent(request.htmlContent()).setTitle(normalizedTitle.isEmpty() ? null : normalizedTitle)
            .setAppearance(request.appearance()).setIcon(request.icon()).setDismissible(request.dismissible())
            .setAudience(request.audience()).setPlacement(request.placement()).setPageTargets(request.pageTargets().deepCopy());
    }
}
