package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.operations.error.PicsureExceptions;

@Service
public class BannerService {

    static final String SYSTEM_MIGRATION_ACTOR = "SYSTEM_MIGRATION";

    private static final Comparator<ManagementBannerDto> MANAGEMENT_ORDER =
        Comparator.comparingInt((ManagementBannerDto banner) -> lifecycleOrder(banner.lifecycle()))
            .thenComparing(BannerService::orderablePriority, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ManagementBannerDto::createdAt).thenComparing(banner -> banner.uuid().toString());

    private final BannerRepository repository;
    private final BannerVersionRepository versionRepository;
    private final Clock clock;
    private final BannerPresentationHasher hasher;
    private final BannerAuditService auditService;

    public BannerService(
        BannerRepository repository, BannerVersionRepository versionRepository, @Qualifier("bannerClock") Clock clock,
        BannerPresentationHasher hasher, BannerAuditService auditService
    ) {
        this.repository = repository;
        this.versionRepository = versionRepository;
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
        return repository.findAllManaged().stream().flatMap(banner -> ManagementBannerDto.from(banner, now).stream())
            .sorted(MANAGEMENT_ORDER).toList();
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
        versionRepository.saveAndFlush(BannerVersion.snapshot(saved, 1, now, actor));
        auditService.registerMutationAudit(BannerAuditService.PUBLISHED_ACTION, saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
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
        auditService.registerMutationAudit(BannerAuditService.SAVED_ACTION, saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    @Transactional
    public ManagementBannerDto update(UUID uuid, PublishBannerRequest request, GatewayUser user) {
        validate(request);
        BannerOccurrence banner = repository.findByIdForUpdate(uuid).orElseThrow(() -> PicsureExceptions.notFound("Banner", uuid));
        return switch (banner.getStatus()) {
            case SAVED -> updateSaved(banner, request, user);
            case PUBLISHED -> updatePublished(banner, request, user);
            case DISABLED, ARCHIVED -> throw PicsureExceptions.conflict("Disabled and archived banners cannot be updated");
        };
    }

    @Transactional
    public ManagementBannerDto publishDraft(UUID uuid, PublishBannerRequest request, GatewayUser user) {
        validate(request);
        BannerOccurrence banner = requireSavedForPublication(uuid);
        Instant now = clock.instant();
        String actor = user.getUserId();
        apply(request, banner).setStatus(BannerStatus.PUBLISHED).setStartAt(now)
            .setPriority(repository.findMaximumOrderablePriority(now) + 1).setUpdatedAt(now).setUpdatedBy(actor).setPublishedAt(now)
            .setPublishedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        versionRepository.saveAndFlush(BannerVersion.snapshot(saved, 1, now, actor));
        auditService.registerMutationAudit(BannerAuditService.PUBLISHED_ACTION, saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    private ManagementBannerDto updateSaved(BannerOccurrence banner, PublishBannerRequest request, GatewayUser user) {
        Instant now = clock.instant();
        String actor = user.getUserId();
        apply(request, banner).setUpdatedAt(now).setUpdatedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        auditService.registerMutationAudit(BannerAuditService.UPDATED_ACTION, saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    private ManagementBannerDto updatePublished(BannerOccurrence banner, PublishBannerRequest request, GatewayUser user) {
        int currentVersionNumber = versionRepository.findMaximumVersionNumber(banner.getUuid());
        if (currentVersionNumber == 0) {
            String publishedBy = banner.getPublishedBy();
            String versionActor = publishedBy == null || publishedBy.isEmpty() ? SYSTEM_MIGRATION_ACTOR : publishedBy;
            versionRepository.saveAndFlush(BannerVersion.snapshot(banner, 1, publicationTime(banner), versionActor));
            currentVersionNumber = 1;
        }

        BannerOccurrence candidate = apply(request, new BannerOccurrence()).setStartAt(banner.getStartAt()).setEndAt(banner.getEndAt());
        candidate.setPresentationHash(hasher.hash(candidate));
        if (!hasMaterialChange(banner, candidate)) {
            return managementDto(banner, clock.instant());
        }

        Instant now = clock.instant();
        String actor = user.getUserId();
        String previousHash = banner.getPresentationHash();
        apply(request, banner).setPresentationHash(candidate.getPresentationHash()).setUpdatedAt(now).setUpdatedBy(actor);
        BannerOccurrence saved = repository.saveAndFlush(banner);
        versionRepository.saveAndFlush(BannerVersion.snapshot(saved, currentVersionNumber + 1, now, actor));
        auditService.registerUpdateAudit(saved.getUuid(), now, previousHash, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    private BannerOccurrence requireSavedForPublication(UUID uuid) {
        BannerOccurrence banner = repository.findByIdForUpdate(uuid).orElseThrow(() -> PicsureExceptions.notFound("Banner", uuid));
        if (banner.getStatus() != BannerStatus.SAVED) {
            throw PicsureExceptions.conflict("Only saved banners can be published through this operation");
        }
        return banner;
    }

    private static ManagementBannerDto managementDto(BannerOccurrence banner, Instant now) {
        return ManagementBannerDto.from(banner, now).orElseThrow(() -> PicsureExceptions.notFound("Banner", banner.getUuid()));
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

    static boolean hasMaterialChange(BannerOccurrence current, BannerOccurrence candidate) {
        return !candidate.getPresentationHash().equals(current.getPresentationHash())
            || !Objects.equals(candidate.getStartAt(), current.getStartAt()) || !Objects.equals(candidate.getEndAt(), current.getEndAt());
    }

    private static Instant publicationTime(BannerOccurrence banner) {
        if (banner.getPublishedAt() != null) {
            return banner.getPublishedAt();
        }
        return banner.getUpdatedAt() != null ? banner.getUpdatedAt() : banner.getCreatedAt();
    }

    private static int lifecycleOrder(BannerLifecycle lifecycle) {
        return switch (lifecycle) {
            case ACTIVE, SCHEDULED -> 0;
            case SAVED, DISABLED -> 1;
            case EXPIRED -> 2;
        };
    }

    private static Integer orderablePriority(ManagementBannerDto banner) {
        return switch (banner.lifecycle()) {
            case ACTIVE, SCHEDULED -> banner.priority();
            case SAVED, DISABLED, EXPIRED -> null;
        };
    }
}
