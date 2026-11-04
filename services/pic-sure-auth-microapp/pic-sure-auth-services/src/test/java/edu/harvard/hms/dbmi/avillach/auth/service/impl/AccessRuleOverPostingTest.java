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
 * An access rule create generates its own identifier, an update keeps the identifier and any field the request omits, gates are
 * resolved from storage rather than taken from the request body, and an update that would put a rule on a cycle of gates or sub-rules is
 * rejected.
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

    @Test
    void updatingARuleToGateItselfIsRejected() {
        AccessRule stored = rule("SELF");
        when(repo.findById(stored.getUuid())).thenReturn(Optional.of(stored));
        when(repo.findAllById(Set.of(stored.getUuid()))).thenReturn(List.of(stored));

        assertThrows(IllegalArgumentException.class, () -> service.updateFrom(List.of(linkRequest(stored, Set.of(idRef(stored)), null))));
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void updatingARuleIntoACycleThroughAnotherRulesSubRulesIsRejected() {
        AccessRule outer = rule("OUTER");
        AccessRule inner = rule("INNER");
        inner.setSubAccessRule(Set.of(outer));
        when(repo.findById(outer.getUuid())).thenReturn(Optional.of(outer));
        when(repo.findAllById(Set.of(inner.getUuid()))).thenReturn(List.of(inner));

        assertThrows(IllegalArgumentException.class, () -> service.updateFrom(List.of(linkRequest(outer, Set.of(idRef(inner)), null))));
        verify(repo, never()).saveAll(anyList());
    }

    @Test
    void updatingARuleToShareAGateWithItsSubRuleIsAccepted() {
        AccessRule shared = rule("SHARED");
        AccessRule sub = rule("SUB");
        sub.setGates(Set.of(shared));
        AccessRule stored = rule("TOP");
        when(repo.findById(stored.getUuid())).thenReturn(Optional.of(stored));
        when(repo.findAllById(Set.of(shared.getUuid()))).thenReturn(List.of(shared));
        when(repo.findAllById(Set.of(sub.getUuid()))).thenReturn(List.of(sub));
        when(repo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateFrom(List.of(linkRequest(stored, Set.of(idRef(shared)), Set.of(idRef(sub)))));

        assertEquals(Set.of(shared), savedRule().getGates());
    }

    private static AccessRule rule(String name) {
        AccessRule rule = new AccessRule();
        rule.setUuid(UUID.randomUUID());
        rule.setName(name);
        return rule;
    }

    private static EntityIdRef idRef(AccessRule rule) {
        return new EntityIdRef(rule.getUuid());
    }

    private static AccessRuleUpdateRequest linkRequest(AccessRule rule, Set<EntityIdRef> gates, Set<EntityIdRef> subAccessRule) {
        return new AccessRuleUpdateRequest(rule.getUuid(), null, null, null, null, null, null, null, null, null, gates, subAccessRule);
    }

    @SuppressWarnings("unchecked")
    private AccessRule savedRule() {
        ArgumentCaptor<List<AccessRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        return captor.getValue().getFirst();
    }
}
