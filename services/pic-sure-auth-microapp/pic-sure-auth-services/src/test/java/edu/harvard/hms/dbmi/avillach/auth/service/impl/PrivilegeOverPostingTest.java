package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for the privilege endpoints. A create resolves its application from storage rather than accepting a nested entity, and an update keeps the identifier and any field the request omits.
 */
class PrivilegeOverPostingTest {

    @Test
    void creatingAPrivilegeResolvesItsApplicationFromStorage() {
        PrivilegeRepository repo = mock(PrivilegeRepository.class);
        ApplicationRepository applicationRepo = mock(ApplicationRepository.class);
        PrivilegeService service = new PrivilegeService(repo, mock(AccessRuleService.class), applicationRepo);
        UUID appId = UUID.randomUUID();
        Application persisted = new Application();
        persisted.setUuid(appId);
        persisted.setName("picsure");
        persisted.setToken("stored-application-token");
        when(applicationRepo.findById(appId)).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new PrivilegeCreateRequest("PRIV", "desc", new EntityIdRef(appId), null)));

        Privilege saved = captureSaved(repo);
        assertNull(saved.getUuid());
        assertSame(persisted, saved.getApplication(), "the association is the stored application, not the caller's copy");
    }

    @Test
    void updatingAPrivilegeKeepsItsIdentityAndUnlistedFields() {
        PrivilegeRepository repo = mock(PrivilegeRepository.class);
        PrivilegeService service = new PrivilegeService(repo, mock(AccessRuleService.class), mock(ApplicationRepository.class));
        UUID privilegeId = UUID.randomUUID();
        Privilege stored = new Privilege();
        stored.setUuid(privilegeId);
        stored.setName("ORIGINAL");
        stored.setDescription("original description");
        when(repo.findById(privilegeId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new PrivilegeUpdateRequest(privilegeId, "RENAMED", null, null, null)));

        Privilege saved = captureSaved(repo);
        assertEquals(privilegeId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("original description", saved.getDescription());
    }

    private static <T> T captureSaved(org.springframework.data.jpa.repository.JpaRepository<T, ?> repo) {
        ArgumentCaptor<List<T>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }

}
