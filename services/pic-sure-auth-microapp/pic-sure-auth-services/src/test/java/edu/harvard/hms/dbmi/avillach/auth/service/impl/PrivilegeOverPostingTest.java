package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PrivilegeUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApplicationRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;

/**
 * A privilege create resolves its application from storage rather than accepting a nested entity, and an update keeps the identifier and
 * any field the request omits, including the access rules the privilege already holds.
 */
class PrivilegeOverPostingTest {

    private final PrivilegeRepository repo = mock(PrivilegeRepository.class);
    private final ApplicationRepository applicationRepo = mock(ApplicationRepository.class);
    private final PrivilegeService service = new PrivilegeService(repo, mock(AccessRuleService.class), applicationRepo);

    @Test
    void creatingAPrivilegeResolvesItsApplicationFromStorage() {
        UUID appId = UUID.randomUUID();
        Application persisted = new Application();
        persisted.setUuid(appId);
        persisted.setName("picsure");
        when(applicationRepo.findById(appId)).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new PrivilegeCreateRequest("PRIV", "desc", new EntityIdRef(appId), null)));

        Privilege saved = savedPrivilege();
        assertNull(saved.getUuid());
        assertSame(persisted, saved.getApplication());
    }

    @Test
    void updatingAPrivilegeKeepsItsIdentityAndUnlistedFields() {
        UUID privilegeId = UUID.randomUUID();
        AccessRule heldRule = new AccessRule();
        heldRule.setUuid(UUID.randomUUID());
        Privilege stored = new Privilege();
        stored.setUuid(privilegeId);
        stored.setName("ORIGINAL");
        stored.setDescription("original description");
        stored.setAccessRules(new HashSet<>(Set.of(heldRule)));
        when(repo.findById(privilegeId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new PrivilegeUpdateRequest(privilegeId, "RENAMED", null, null, null)));

        Privilege saved = savedPrivilege();
        assertEquals(privilegeId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("original description", saved.getDescription());
        assertEquals(Set.of(heldRule), saved.getAccessRules());
    }

    @Test
    void creatingAPrivilegeForAnUnknownApplicationIsRejected() {
        UUID unknown = UUID.randomUUID();
        when(applicationRepo.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createFrom(List.of(new PrivilegeCreateRequest("PRIV", null, new EntityIdRef(unknown), null)))
        );
        verify(repo, never()).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private Privilege savedPrivilege() {
        ArgumentCaptor<List<Privilege>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }
}
