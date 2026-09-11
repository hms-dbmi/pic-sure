package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Over-posting cover for the access rule endpoints. A create must generate its own identifier, an update must keep the identifier and any field the request omits, and gates must be resolved from storage rather than taken from the request body.
 */
class AccessRuleOverPostingTest {

    @Test
    void creatingAnAccessRuleGeneratesItsOwnIdentifier() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new AccessRuleCreateRequest("RULE", "desc", 1, "$.x", "v", null, null, null, null, null, null)));

        AccessRule saved = captureSaved(repo);
        assertNull(saved.getUuid(), "a create must not carry a caller-chosen identifier");
        assertEquals("RULE", saved.getName());
        assertEquals(Boolean.FALSE, saved.getCheckMapKeyOnly(), "the boolean defaults the service has always applied still apply");
    }

    @Test
    void updatingAnAccessRuleKeepsItsIdentityAndUnlistedFields() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        UUID ruleId = UUID.randomUUID();
        AccessRule stored = new AccessRule();
        stored.setUuid(ruleId);
        stored.setName("ORIGINAL");
        stored.setRule("$.original");
        stored.setValue("original-value");
        when(repo.findById(ruleId)).thenReturn(Optional.of(stored));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(
            List.of(new AccessRuleUpdateRequest(ruleId, "RENAMED", null, null, null, null, null, null, null, null, null, null))
        );

        AccessRule saved = captureSaved(repo);
        assertEquals(ruleId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("$.original", saved.getRule(), "an absent member leaves the stored value alone");
        assertEquals("original-value", saved.getValue());
    }

    @Test
    void accessRuleGatesAreResolvedFromStorageRatherThanTakenFromTheRequest() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        UUID gateId = UUID.randomUUID();
        AccessRule persistedGate = new AccessRule();
        persistedGate.setUuid(gateId);
        persistedGate.setName("PERSISTED_GATE");
        when(repo.findAllById(Set.of(gateId))).thenReturn(List.of(persistedGate));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(
            List.of(
                new AccessRuleCreateRequest("RULE", null, 1, "$.x", null, null, null, null, null, Set.of(new EntityIdRef(gateId)), null)
            )
        );

        assertEquals(Set.of(persistedGate), captureSaved(repo).getGates());
    }

    @Test
    void accessRuleUpdateRejectsAnUnknownGate() {
        AccessRuleRepository repo = mock(AccessRuleRepository.class);
        AccessRuleService service = new AccessRuleService(repo, "[]");
        UUID missing = UUID.randomUUID();
        when(repo.findAllById(Set.of(missing))).thenReturn(List.of());

        assertThrows(
            IllegalArgumentException.class,
            () -> service.createFrom(
                List.of(
                    new AccessRuleCreateRequest(
                        "RULE", null, 1, "$.x", null, null, null, null, null, Set.of(new EntityIdRef(missing)), null
                    )
                )
            )
        );
    }

    private static <T> T captureSaved(org.springframework.data.jpa.repository.JpaRepository<T, ?> repo) {
        ArgumentCaptor<List<T>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }

}
