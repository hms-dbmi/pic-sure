package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@SpringBootTest
@ContextConfiguration(classes = {AccessRuleService.class})
public class AccessRuleServiceTest {

    @MockBean
    private AccessRuleRepository accessRuleRepo;

    @Autowired
    private AccessRuleService accessRuleService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * Fail closed on a type this evaluator does not implement. Retiring type 17 from {@code TypeNaming} did not make its rows inert: it
     * moved them into the switch default, which used to return true, turning every surviving row into an unconditional grant.
     */
    @Test
    public void testDecisionMaker_unsupportedType_deniesInsteadOfGranting() {
        AccessRule retiredConsentRule = new AccessRule();
        retiredConsentRule.setName("GATE_QUERY_v3");
        retiredConsentRule.setType(17);
        retiredConsentRule.setValue("/v3/query");

        assertFalse(accessRuleService.decisionMaker(retiredConsentRule, "/hpds/auth/query/sync"));
    }

    @Test
    public void testDecisionMaker_unknownFutureType_deniesInsteadOfGranting() {
        AccessRule rule = new AccessRule();
        rule.setName("AR_FROM_THE_FUTURE");
        rule.setType(99);
        rule.setValue("anything");

        assertFalse(accessRuleService.decisionMaker(rule, "anything"));
    }

    @Test
    public void testGetAccessRuleById_found() {
        UUID id = UUID.randomUUID();
        AccessRule accessRule = new AccessRule();
        when(accessRuleRepo.findById(id)).thenReturn(Optional.of(accessRule));

        Optional<AccessRule> result = accessRuleService.getAccessRuleById(id);
        assertTrue(result.isPresent());
        assertSame(accessRule, result.get());
    }

    @Test
    public void testGetAccessRuleById_notFound() {
        UUID id = UUID.randomUUID();
        when(accessRuleRepo.findById(id)).thenReturn(Optional.empty());

        Optional<AccessRule> result = accessRuleService.getAccessRuleById(id);
        assertFalse(result.isPresent());
    }

    @Test
    public void testGetAllAccessRules_empty() {
        when(accessRuleRepo.findAll()).thenReturn(Collections.emptyList());

        List<AccessRule> result = accessRuleService.getAllAccessRules();
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetAllAccessRules_nonEmpty() {
        List<AccessRule> rules = Arrays.asList(new AccessRule(), new AccessRule());
        when(accessRuleRepo.findAll()).thenReturn(rules);

        List<AccessRule> result = accessRuleService.getAllAccessRules();
        assertEquals(2, result.size());
    }


    @Test
    public void testRemoveAccessRuleById() {
        UUID id = UUID.randomUUID();
        List<AccessRule> remainingRules = List.of(new AccessRule());
        doNothing().when(accessRuleRepo).deleteById(id);
        when(accessRuleRepo.findAll()).thenReturn(remainingRules);

        List<AccessRule> result = accessRuleService.removeAccessRuleById(id);
        assertEquals(1, result.size());
    }

}
