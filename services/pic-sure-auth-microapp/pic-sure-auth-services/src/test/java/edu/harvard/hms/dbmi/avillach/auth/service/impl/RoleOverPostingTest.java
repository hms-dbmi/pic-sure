package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;

/**
 * A role create resolves its privileges from storage rather than accepting nested entities, and an update keeps the identifier and any
 * field the request omits.
 */
class RoleOverPostingTest {

    private final RoleRepository repo = mock(RoleRepository.class);
    private final PrivilegeService privilegeService = mock(PrivilegeService.class);
    private final RoleService service = new RoleService(repo, privilegeService);

    @Test
    void creatingARoleResolvesItsPrivilegesFromStorage() {
        UUID privilegeId = UUID.randomUUID();
        Privilege persisted = new Privilege();
        persisted.setUuid(privilegeId);
        persisted.setName("PERSISTED_PRIV");
        when(privilegeService.findById(privilegeId)).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new RoleCreateRequest("ROLE", "desc", Set.of(new EntityIdRef(privilegeId)))));

        Role saved = savedRole();
        assertNull(saved.getUuid());
        assertEquals(Set.of(persisted), saved.getPrivileges());
    }

    @Test
    void updatingARoleKeepsItsIdentityAndUnlistedFields() {
        UUID roleId = UUID.randomUUID();
        Role stored = new Role();
        stored.setUuid(roleId);
        stored.setName("ORIGINAL");
        stored.setDescription("original description");
        when(repo.findById(roleId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new RoleUpdateRequest(roleId, "RENAMED", null, null)));

        Role saved = savedRole();
        assertEquals(roleId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("original description", saved.getDescription());
    }

    @Test
    void creatingARoleWithAnUnknownPrivilegeIsRejected() {
        UUID unknown = UUID.randomUUID();
        when(privilegeService.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createFrom(List.of(new RoleCreateRequest("ROLE", null, Set.of(new EntityIdRef(unknown)))))
        );
        verify(repo, never()).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private Role savedRole() {
        ArgumentCaptor<List<Role>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }
}
