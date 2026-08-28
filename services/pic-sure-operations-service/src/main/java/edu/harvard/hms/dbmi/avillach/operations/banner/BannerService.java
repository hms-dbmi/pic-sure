package edu.harvard.hms.dbmi.avillach.operations.banner;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final BannerPriorityAllocatorRepository priorityAllocatorRepository;
    private final Clock clock;
    private final BannerPresentationHasher hasher;
    private final BannerAuditService auditService;

    public BannerService(
        BannerRepository repository, BannerVersionRepository versionRepository,
        BannerPriorityAllocatorRepository priorityAllocatorRepository, @Qualifier("bannerClock") Clock clock,
        BannerPresentationHasher hasher, BannerAuditService auditService
    ) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.priorityAllocatorRepository = priorityAllocatorRepository;
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
        validateNewSchedule(request, now);
        String actor = user.getUserId();
        Instant startAt = publicationStart(request, now);
        BannerOccurrence banner = apply(request, new BannerOccurrence()).setStatus(BannerStatus.PUBLISHED).setStartAt(startAt)
            .setEndAt(request.endAt()).setPriority(allocateBottomPriority(now)).setCreatedAt(now).setCreatedBy(actor).setUpdatedAt(now)
            .setUpdatedBy(actor).setPublishedAt(now).setPublishedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        versionRepository.saveAndFlush(BannerVersion.snapshot(saved, 1, now, actor));
        auditService.registerMutationAudit(publicationAction(startAt, now), saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    @Transactional
    public ManagementBannerDto saveDraft(PublishBannerRequest request, GatewayUser user) {
        validate(request);
        Instant now = clock.instant();
        validateNewSchedule(request, now);
        String actor = user.getUserId();
        BannerOccurrence banner = apply(request, new BannerOccurrence()).setStatus(BannerStatus.SAVED).setCreatedAt(now).setCreatedBy(actor)
            .setUpdatedAt(now).setUpdatedBy(actor).setStartAt(request.startAt()).setEndAt(request.endAt());
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
        Instant now = clock.instant();
        validateNewSchedule(request, now);
        BannerPriorityAllocator allocator = lockAllocator();
        BannerOccurrence banner = requireSavedForPublication(uuid);
        String actor = user.getUserId();
        Instant startAt = publicationStart(request, now);
        apply(request, banner).setStatus(BannerStatus.PUBLISHED).setStartAt(startAt).setEndAt(request.endAt())
            .setPriority(allocateBottomPriority(allocator, now)).setUpdatedAt(now).setUpdatedBy(actor).setPublishedAt(now)
            .setPublishedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        versionRepository.saveAndFlush(BannerVersion.snapshot(saved, 1, now, actor));
        auditService.registerMutationAudit(publicationAction(startAt, now), saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    @Transactional
    public List<ManagementBannerDto> reorder(List<UUID> bannerUuids, GatewayUser user) {
        if (new HashSet<>(bannerUuids).size() != bannerUuids.size()) {
            throw PicsureExceptions.badRequest("Banner order must not contain duplicate UUIDs");
        }

        BannerPriorityAllocator allocator = lockAllocator();
        Instant now = clock.instant();
        List<BannerOccurrence> current = repository.findOrderableForUpdate(now);
        Map<UUID, BannerOccurrence> currentByUuid = new HashMap<>();
        current.forEach(banner -> currentByUuid.put(banner.getUuid(), banner));
        if (current.size() != bannerUuids.size() || !currentByUuid.keySet().equals(new HashSet<>(bannerUuids))) {
            throw PicsureExceptions.badRequest("Banner order must contain every current active and scheduled banner exactly once");
        }

        List<BannerOccurrence> reordered = bannerUuids.stream().map(currentByUuid::get).toList();
        for (int index = 0; index < reordered.size(); index++) {
            reordered.get(index).setPriority(index + 1);
        }
        repository.saveAllAndFlush(reordered);
        allocator.setNextPriority(reordered.size() + 1);
        priorityAllocatorRepository.save(allocator);
        auditService.registerReorderAudit(bannerUuids, now, user.getUserId());
        return reordered.stream().map(banner -> managementDto(banner, now)).toList();
    }

    private int allocateBottomPriority(Instant now) {
        return allocateBottomPriority(lockAllocator(), now);
    }

    private int allocateBottomPriority(BannerPriorityAllocator allocator, Instant now) {
        int priority = Math.max(allocator.getNextPriority(), repository.findMaximumOrderablePriority(now) + 1);
        allocator.setNextPriority(priority + 1);
        priorityAllocatorRepository.save(allocator);
        return priority;
    }

    private BannerPriorityAllocator lockAllocator() {
        return priorityAllocatorRepository.lockSingleton()
            .orElseThrow(() -> new IllegalStateException("Banner priority allocator is not initialized"));
    }

    @Transactional
    public ManagementBannerDto disable(UUID uuid, GatewayUser user) {
        BannerOccurrence banner = repository.findByIdForUpdate(uuid).orElseThrow(() -> PicsureExceptions.notFound("Banner", uuid));
        Instant now = clock.instant();
        BannerLifecycle lifecycle = ManagementBannerDto.from(banner, now).map(ManagementBannerDto::lifecycle)
            .orElseThrow(() -> PicsureExceptions.conflict("Archived banners cannot be disabled"));
        if (lifecycle != BannerLifecycle.ACTIVE && lifecycle != BannerLifecycle.SCHEDULED) {
            throw PicsureExceptions.conflict("Only active or scheduled banners can be disabled");
        }

        String actor = user.getUserId();
        // Disabling changes only lifecycle bookkeeping: content, schedule, priority, and every published version stay as they are.
        BannerOccurrence saved = repository.saveAndFlush(
            banner.setStatus(BannerStatus.DISABLED).setDisabledAt(now).setDisabledBy(actor).setUpdatedAt(now).setUpdatedBy(actor)
        );
        auditService.registerMutationAudit(BannerAuditService.DISABLED_ACTION, saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    @Transactional
    public ArchivedBannerDto archive(UUID uuid, GatewayUser user) {
        BannerOccurrence banner = repository.findByIdForUpdate(uuid).orElseThrow(() -> PicsureExceptions.notFound("Banner", uuid));
        Instant now = clock.instant();
        BannerLifecycle lifecycle = ManagementBannerDto.from(banner, now).map(ManagementBannerDto::lifecycle)
            .orElseThrow(() -> PicsureExceptions.conflict("Archived banners cannot be archived again"));
        if (lifecycle == BannerLifecycle.ACTIVE || lifecycle == BannerLifecycle.SCHEDULED) {
            throw PicsureExceptions.conflict("Active and scheduled banners must be disabled before they can be archived");
        }

        String actor = user.getUserId();
        BannerOccurrence saved = markArchived(banner, now, actor);
        auditService.registerMutationAudit(BannerAuditService.ARCHIVED_ACTION, saved.getUuid(), now, saved.getPresentationHash(), actor);
        return ArchivedBannerDto.from(saved);
    }

    /**
     * Retires an occurrence without auditing, so a caller that archives as part of a larger action reports only its own event. Archiving is
     * retention bookkeeping: content, schedule, priority, provenance, and every stored version stay as they are, and the priority allocator
     * is untouched because an archiveable occurrence was already outside the orderable queue.
     */
    private BannerOccurrence markArchived(BannerOccurrence banner, Instant now, String actor) {
        return repository.saveAndFlush(
            banner.setStatus(BannerStatus.ARCHIVED).setArchivedAt(now).setArchivedBy(actor).setUpdatedAt(now).setUpdatedBy(actor)
        );
    }

    private ManagementBannerDto updateSaved(BannerOccurrence banner, PublishBannerRequest request, GatewayUser user) {
        Instant now = clock.instant();
        validateChangedDraftSchedule(banner, request, now);
        String actor = user.getUserId();
        apply(request, banner).setStartAt(request.startAt()).setEndAt(request.endAt()).setUpdatedAt(now).setUpdatedBy(actor);
        banner.setPresentationHash(hasher.hash(banner));
        BannerOccurrence saved = repository.saveAndFlush(banner);
        auditService.registerMutationAudit(BannerAuditService.UPDATED_ACTION, saved.getUuid(), now, saved.getPresentationHash(), actor);
        return managementDto(saved, now);
    }

    private ManagementBannerDto updatePublished(BannerOccurrence banner, PublishBannerRequest request, GatewayUser user) {
        Instant now = clock.instant();
        Instant requestedStart = request.startAt() == null ? banner.getStartAt() : request.startAt();
        validateChangedPublishedSchedule(banner, requestedStart, request.endAt(), now);
        int currentVersionNumber = versionRepository.findMaximumVersionNumber(banner.getUuid());
        if (currentVersionNumber == 0) {
            String publishedBy = banner.getPublishedBy();
            String versionActor = publishedBy == null || publishedBy.isEmpty() ? SYSTEM_MIGRATION_ACTOR : publishedBy;
            versionRepository.saveAndFlush(BannerVersion.snapshot(banner, 1, publicationTime(banner), versionActor));
            currentVersionNumber = 1;
        }

        BannerOccurrence candidate = apply(request, new BannerOccurrence()).setStartAt(requestedStart).setEndAt(request.endAt());
        candidate.setPresentationHash(hasher.hash(candidate));
        if (!hasMaterialChange(banner, candidate)) {
            return managementDto(banner, now);
        }

        String actor = user.getUserId();
        String previousHash = banner.getPresentationHash();
        apply(request, banner).setStartAt(requestedStart).setEndAt(request.endAt()).setPresentationHash(candidate.getPresentationHash())
            .setUpdatedAt(now).setUpdatedBy(actor);
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
        BannerPageTargets.normalize(request.pageTargets());
    }

    private static void validateNewSchedule(PublishBannerRequest request, Instant now) {
        validateSchedule(request.startAt(), request.endAt(), now, true, true);
    }

    private static void validateChangedDraftSchedule(BannerOccurrence banner, PublishBannerRequest request, Instant now) {
        boolean startChanged = !Objects.equals(banner.getStartAt(), request.startAt());
        boolean endChanged = !Objects.equals(banner.getEndAt(), request.endAt());
        if (startChanged || endChanged) {
            validateSchedule(request.startAt(), request.endAt(), now, startChanged, endChanged);
        }
    }

    private static void validateChangedPublishedSchedule(
        BannerOccurrence banner, Instant requestedStart, Instant requestedEnd, Instant now
    ) {
        boolean startChanged = !Objects.equals(banner.getStartAt(), requestedStart);
        boolean endChanged = !Objects.equals(banner.getEndAt(), requestedEnd);
        if ((startChanged || endChanged) && banner.getEndAt() != null && !banner.getEndAt().isAfter(now)) {
            throw PicsureExceptions.conflict("Expired banners cannot be rescheduled");
        }
        if (
            (startChanged && !hasMinutePrecision(requestedStart))
                || (endChanged && requestedEnd != null && !hasMinutePrecision(requestedEnd))
        ) {
            throw PicsureExceptions.badRequest("Banner schedule timestamps must use minute precision");
        }
        if (startChanged && requestedStart.isBefore(now)) {
            throw PicsureExceptions.badRequest("Banner start must not be in the past");
        }
        if (requestedEnd != null && !requestedEnd.isAfter(requestedStart)) {
            throw PicsureExceptions.badRequest("Banner end must be after its start");
        }
    }

    private static void validateSchedule(Instant startAt, Instant endAt, Instant now, boolean validateStart, boolean validateEnd) {
        if ((startAt != null && !hasMinutePrecision(startAt)) || (endAt != null && !hasMinutePrecision(endAt))) {
            throw PicsureExceptions.badRequest("Banner schedule timestamps must use minute precision");
        }
        if (validateStart && startAt != null && startAt.isBefore(now)) {
            throw PicsureExceptions.badRequest("Banner start must not be in the past");
        }
        if (validateEnd && endAt != null && !endAt.isAfter(now)) {
            throw PicsureExceptions.badRequest("Banner end must be in the future");
        }
        Instant effectiveStart = startAt == null ? now : startAt;
        if (endAt != null && !endAt.isAfter(effectiveStart)) {
            throw PicsureExceptions.badRequest("Banner end must be after its start");
        }
    }

    private static boolean hasMinutePrecision(Instant instant) {
        return instant.equals(instant.truncatedTo(ChronoUnit.MINUTES));
    }

    private static Instant publicationStart(PublishBannerRequest request, Instant now) {
        return request.startAt() == null ? now : request.startAt();
    }

    private static String publicationAction(Instant startAt, Instant now) {
        return startAt.isAfter(now) ? BannerAuditService.SCHEDULED_ACTION : BannerAuditService.PUBLISHED_ACTION;
    }

    private static BannerOccurrence apply(PublishBannerRequest request, BannerOccurrence banner) {
        String normalizedTitle = BannerPresentationHasher.normalizeTitle(request.title());
        return banner.setHtmlContent(request.htmlContent()).setTitle(normalizedTitle.isEmpty() ? null : normalizedTitle)
            .setAppearance(request.appearance()).setIcon(request.icon()).setDismissible(request.dismissible())
            .setAudience(request.audience()).setPlacement(request.placement())
            .setPageTargets(BannerPageTargets.normalize(request.pageTargets()));
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
