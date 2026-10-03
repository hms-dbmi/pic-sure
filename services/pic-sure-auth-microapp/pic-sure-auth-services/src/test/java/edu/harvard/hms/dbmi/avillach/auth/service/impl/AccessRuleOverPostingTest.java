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

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleCreateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AccessRuleUpdateRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.EntityIdRef;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;

/**
 * An access rule create generates its own identifier, an update keeps the identifier and any field the request omits, and gates are
 * resolved from storage rather than taken from the request body.
 */
class AccessRuleOverPostingTest {

    private final AccessRuleRepository repo = mock(AccessRuleRepository.class);
    private final AccessRuleService service = new AccessRuleService(repo, "[]");

    @Test
    void creatingAnAccessRuleGeneratesItsOwnIdentifier() {
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createFrom(List.of(new AccessRuleCreateRequest("RULE", "desc", 1, "$.x", "v", null, null, null, null, null, null)));

        AccessRule saved = savedRule();
        assertNull(saved.getUuid());
        assertEquals("RULE", saved.getName());
        assertEquals(Boolean.FALSE, saved.getCheckMapKeyOnly());
    }

    @Test
    void updatingAnAccessRuleKeepsItsIdentityAndUnlistedFields() {
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

        AccessRule saved = savedRule();
        assertEquals(ruleId, saved.getUuid());
        assertEquals("RENAMED", saved.getName());
        assertEquals("$.original", saved.getRule());
        assertEquals("original-value", saved.getValue());
    }

    @Test
    void accessRuleGatesAreResolvedFromStorage() {
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

        assertEquals(Set.of(persistedGate), savedRule().getGates());
    }

    @Test
    void creatingAnAccessRuleWithAnUnknownGateIsRejected() {
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
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void updatingAnUnknownAccessRuleIsRejected() {
        UUID missing = UUID.randomUUID();
        when(repo.findById(missing)).thenReturn(Optional.empty());

        assertThrows(
            IllegalArgumentException.class,
            () -> service
                .updateFrom(List.of(new AccessRuleUpdateRequest(missing, "x", null, null, null, null, null, null, null, null, null, null)))
        );
        verify(repo, never()).saveAll(anyList());
    }

    @SuppressWarnings("unchecked")
    private AccessRule savedRule() {
        ArgumentCaptor<List<AccessRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }
}
