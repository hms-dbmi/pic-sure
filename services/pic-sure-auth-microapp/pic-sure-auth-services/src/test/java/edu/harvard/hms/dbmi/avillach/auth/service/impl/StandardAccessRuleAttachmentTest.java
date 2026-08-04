package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AR_DICTIONARY_REQUESTS and AR_LOGGING_REQUESTS are inserted by database migration with no privilege attachment. The startup attachment
 * loop in {@link PrivilegeService#updateAllPrivilegesOnStartup()} is the only thing that binds them to a privilege, and therefore to a
 * user. If it regresses, /dictionary and /logging silently 403 for every user. <p> These tests assert against the {@link Privilege}
 * instances handed to {@link PrivilegeRepository#save(Object)}, so they fail if the rules are attached but never persisted as well as if
 * they are never attached at all.
 */
class StandardAccessRuleAttachmentTest {

    private static final String STANDARD_RULES = "AR_ONLY_INFO,AR_ONLY_SEARCH,AR_DICTIONARY_REQUESTS,AR_LOGGING_REQUESTS";

    private AccessRuleRepository accessRuleRepository;
    private PrivilegeRepository privilegeRepository;
    private PrivilegeService privilegeService;

    private static AccessRule named(String name) {
        AccessRule accessRule = new AccessRule();
        accessRule.setUuid(UUID.randomUUID());
        accessRule.setName(name);
        return accessRule;
    }

    private static Privilege privilege(String name, AccessRule... existingRules) {
        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        privilege.setName(name);
        privilege.setAccessRules(new HashSet<>(Set.of(existingRules)));
        return privilege;
    }

    private static Set<String> ruleNamesOf(Privilege privilege) {
        return privilege.getAccessRules().stream().map(AccessRule::getName).collect(Collectors.toSet());
    }

    /**
     * The privileges actually handed to the repository, so an attachment that is never persisted fails the assertions.
     */
    private List<Privilege> savedPrivileges(int expectedCount) {
        ArgumentCaptor<Privilege> captor = ArgumentCaptor.forClass(Privilege.class);
        verify(privilegeRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    @BeforeEach
    void setUp() {
        accessRuleRepository = mock(AccessRuleRepository.class);
        privilegeRepository = mock(PrivilegeRepository.class);

        when(accessRuleRepository.findByName("AR_ONLY_INFO")).thenReturn(named("AR_ONLY_INFO"));
        when(accessRuleRepository.findByName("AR_ONLY_SEARCH")).thenReturn(named("AR_ONLY_SEARCH"));
        when(accessRuleRepository.findByName("AR_DICTIONARY_REQUESTS")).thenReturn(named("AR_DICTIONARY_REQUESTS"));
        when(accessRuleRepository.findByName("AR_LOGGING_REQUESTS")).thenReturn(named("AR_LOGGING_REQUESTS"));
        when(privilegeRepository.save(any(Privilege.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccessRuleService accessRuleService = new AccessRuleService(accessRuleRepository, STANDARD_RULES);
        privilegeService = new PrivilegeService(privilegeRepository, accessRuleService);
    }

    @Test
    void startupAttachesEveryStandardRuleToEveryPrivilege() {
        Privilege authAccess = privilege("MANAGED_PRIV_AUTH_ACCESS");
        Privilege openAccess = privilege("MANAGED_PRIV_OPEN_ACCESS");
        when(privilegeRepository.findAll()).thenReturn(List.of(authAccess, openAccess));

        privilegeService.updateAllPrivilegesOnStartup();

        List<Privilege> saved = savedPrivileges(2);
        for (Privilege privilege : saved) {
            Set<String> attached = ruleNamesOf(privilege);
            assertTrue(attached.contains("AR_DICTIONARY_REQUESTS"), "dictionary routing rule must be attached to " + privilege.getName());
            assertTrue(attached.contains("AR_LOGGING_REQUESTS"), "logging routing rule must be attached to " + privilege.getName());
            assertTrue(attached.contains("AR_ONLY_SEARCH"), "search rule must be attached to " + privilege.getName());
            assertTrue(attached.contains("AR_ONLY_INFO"), "info rule must be attached to " + privilege.getName());
        }
    }

    @Test
    void startupKeepsAccessRulesThePrivilegeAlreadyHad() {
        AccessRule preExisting = named("AR_PRIV_SPECIFIC_RULE");
        Privilege authAccess = privilege("MANAGED_PRIV_AUTH_ACCESS", preExisting);
        when(privilegeRepository.findAll()).thenReturn(List.of(authAccess));

        privilegeService.updateAllPrivilegesOnStartup();

        Set<String> attached = ruleNamesOf(savedPrivileges(1).getFirst());
        assertEquals(
            Set.of("AR_PRIV_SPECIFIC_RULE", "AR_ONLY_INFO", "AR_ONLY_SEARCH", "AR_DICTIONARY_REQUESTS", "AR_LOGGING_REQUESTS"), attached,
            "startup attachment must add the standard rules without discarding the privilege's own rules"
        );
    }

    /**
     * CHARACTERIZATION of current behaviour, not a desired invariant. A configured standard rule that is absent from the database is
     * skipped with a single WARN and startup proceeds, so a migration that has not run costs every user /dictionary access silently. If
     * that path is ever hardened to fail loudly, update this test rather than treating it as a requirement to preserve. What this test
     * genuinely locks in is only the second half: one unresolvable rule must not prevent the remaining rules from attaching.
     */
    @Test
    void oneStandardRuleMissingFromTheDatabaseDoesNotStopTheOthersAttaching() {
        when(accessRuleRepository.findByName("AR_DICTIONARY_REQUESTS")).thenReturn(null);
        Privilege authAccess = privilege("MANAGED_PRIV_AUTH_ACCESS");
        when(privilegeRepository.findAll()).thenReturn(List.of(authAccess));

        privilegeService.updateAllPrivilegesOnStartup();

        Set<String> attached = ruleNamesOf(savedPrivileges(1).getFirst());
        assertEquals(Set.of("AR_ONLY_INFO", "AR_ONLY_SEARCH", "AR_LOGGING_REQUESTS"), attached);
    }
}
