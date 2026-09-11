package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.RoleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for the role endpoints. A create resolves its privileges from storage rather than accepting nested entities, and an update keeps the identifier and any field the request omits.
 */
class RoleOverPostingTest {

    @Test
    void creatingARoleResolvesItsPrivilegesFromStorage() {
        RoleRepository repo = mock(RoleRepository.class);
        PrivilegeService privilegeService = mock(PrivilegeService.class);
        RoleService service = new RoleService(repo, privilegeService);
        UUID privilegeId = UUID.randomUUID();
        Privilege persisted = new Privilege();
        persisted.setUuid(privilegeId);
        persisted.setName("PERSISTED_PRIV");
        when(privilegeService.findById(privilegeId)).thenReturn(Optional.of(persisted));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new RoleCreateRequest("ROLE", "desc", Set.of(new EntityIdRef(privilegeId)))));

        Role saved = captureSaved(repo);
        assertNull(saved.getUuid());
        assertEquals(Set.of(persisted), saved.getPrivileges());
    }

    @Test
    void updatingARoleKeepsItsIdentityAndUnlistedFields() {
        RoleRepository repo = mock(RoleRepository.class);
        RoleService service = new RoleService(repo, mock(PrivilegeService.class));
        UUID roleId = UUID.randomUUID();
        Role stored = new Role();
        stored.setUuid(roleId);
        stored.setName("ORIGINAL");
        stored.setDescription("original description");
        when(repo.findById(roleId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(new RoleUpdateRequest(roleId, "RENAMED", null, null)));

        Role saved = captureSaved(repo);
        assertEquals(roleId, saved.getUuid());
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
