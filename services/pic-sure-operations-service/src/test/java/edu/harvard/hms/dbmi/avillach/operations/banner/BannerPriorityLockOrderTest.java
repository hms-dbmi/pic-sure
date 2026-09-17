package edu.harvard.hms.dbmi.avillach.operations.banner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;

class BannerPriorityLockOrderTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final GatewayUser ADMIN = new GatewayUser("admin-id", "admin-subject", "admin@example.org", "ADMIN", Set.of("ADMIN"));

    @Test
    void publishingDraftLocksAllocatorBeforeOccurrence() {
        BannerRepository repository = mock(BannerRepository.class);
        BannerVersionRepository versionRepository = mock(BannerVersionRepository.class);
        BannerPriorityAllocatorRepository allocatorRepository = mock(BannerPriorityAllocatorRepository.class);
        BannerPresentationHasher hasher = mock(BannerPresentationHasher.class);
        BannerAuditService auditService = mock(BannerAuditService.class);
        BannerService service =
            new BannerService(repository, versionRepository, allocatorRepository, Clock.fixed(NOW, ZoneOffset.UTC), hasher, auditService);
        UUID uuid = UUID.randomUUID();
        BannerOccurrence draft = new BannerOccurrence().setStatus(BannerStatus.SAVED).setCreatedAt(NOW).setCreatedBy("admin-id")
            .setUpdatedAt(NOW).setUpdatedBy("admin-id");
        ReflectionTestUtils.setField(draft, "uuid", uuid);
        BannerPriorityAllocator allocator = new BannerPriorityAllocator().setId(BannerPriorityAllocator.SINGLETON_ID).setNextPriority(1);
        when(allocatorRepository.lockSingleton()).thenReturn(Optional.of(allocator));
        when(repository.findByIdForUpdate(uuid)).thenReturn(Optional.of(draft));
        when(repository.findMaximumOrderablePriority(NOW)).thenReturn(0);
        when(repository.saveAndFlush(any(BannerOccurrence.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.saveAndFlush(any(BannerVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(hasher.hash(any(BannerOccurrence.class))).thenReturn("hash");

        service.publishDraft(uuid, request(), ADMIN);

        InOrder lockOrder = inOrder(allocatorRepository, repository);
        lockOrder.verify(allocatorRepository).lockSingleton();
        lockOrder.verify(repository).findByIdForUpdate(uuid);
    }

    @Test
    void reorderLocksAllocatorBeforeCurrentOccurrences() {
        BannerRepository repository = mock(BannerRepository.class);
        BannerPriorityAllocatorRepository allocatorRepository = mock(BannerPriorityAllocatorRepository.class);
        BannerService service = new BannerService(
            repository, mock(BannerVersionRepository.class), allocatorRepository, Clock.fixed(NOW, ZoneOffset.UTC),
            mock(BannerPresentationHasher.class), mock(BannerAuditService.class)
        );
        BannerPriorityAllocator allocator = new BannerPriorityAllocator().setId(BannerPriorityAllocator.SINGLETON_ID).setNextPriority(1);
        when(allocatorRepository.lockSingleton()).thenReturn(Optional.of(allocator));
        when(repository.findOrderableForUpdate(NOW)).thenReturn(List.of());
        when(repository.findAllById(List.of())).thenReturn(List.of());

        service.reorder(List.of(), ADMIN);

        InOrder lockOrder = inOrder(allocatorRepository, repository);
        lockOrder.verify(allocatorRepository).lockSingleton();
        lockOrder.verify(repository).findOrderableForUpdate(NOW);
    }

    @Test
    void restoringLocksAllocatorBeforeSourceOccurrence() {
        BannerRepository repository = mock(BannerRepository.class);
        BannerVersionRepository versionRepository = mock(BannerVersionRepository.class);
        BannerPriorityAllocatorRepository allocatorRepository = mock(BannerPriorityAllocatorRepository.class);
        BannerPresentationHasher hasher = mock(BannerPresentationHasher.class);
        BannerAuditService auditService = mock(BannerAuditService.class);
        BannerService service =
            new BannerService(repository, versionRepository, allocatorRepository, Clock.fixed(NOW, ZoneOffset.UTC), hasher, auditService);
        UUID uuid = UUID.randomUUID();
        BannerOccurrence source = new BannerOccurrence().setStatus(BannerStatus.DISABLED).setCreatedAt(NOW).setCreatedBy("admin-id")
            .setUpdatedAt(NOW).setUpdatedBy("admin-id");
        ReflectionTestUtils.setField(source, "uuid", uuid);
        BannerPriorityAllocator allocator = new BannerPriorityAllocator().setId(BannerPriorityAllocator.SINGLETON_ID).setNextPriority(1);
        when(allocatorRepository.lockSingleton()).thenReturn(Optional.of(allocator));
        when(repository.findByIdForUpdate(uuid)).thenReturn(Optional.of(source));
        when(repository.findMaximumOrderablePriority(NOW)).thenReturn(0);
        when(repository.saveAndFlush(any(BannerOccurrence.class))).thenAnswer(invocation -> {
            BannerOccurrence occurrence = invocation.getArgument(0);
            if (occurrence.getUuid() == null) {
                ReflectionTestUtils.setField(occurrence, "uuid", UUID.randomUUID());
            }
            return occurrence;
        });
        when(versionRepository.saveAndFlush(any(BannerVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(hasher.hash(any(BannerOccurrence.class))).thenReturn("hash");

        service.restore(uuid, request(), ADMIN);

        InOrder lockOrder = inOrder(allocatorRepository, repository);
        lockOrder.verify(allocatorRepository).lockSingleton();
        lockOrder.verify(repository).findByIdForUpdate(uuid);
    }

    private static PublishBannerRequest request() {
        return new PublishBannerRequest(
            "<p>Draft</p>", "Draft", BannerAppearance.PRIMARY, BannerIcon.NONE, true, BannerAudience.EVERYONE, BannerPlacement.SITE_TOP,
            List.of(BannerPageTarget.all())
        );
    }
}
