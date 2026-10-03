package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;

/**
 * Each access rule endpoint returns the JSON the {@link AccessRule} entity serialized to, minus {@code mergedValues} and {@code mergedName}
 * at every depth, and the two error paths the controller owns keep their status and their body's shape.
 */
class AccessRuleControllerTest {

    private static final String CREATE_BODY = "[{\"name\":\"AR_ONLY_SEARCH\",\"type\":4,\"rule\":\"$.path\",\"value\":\"/search/\"}]";

    private final AccessRuleService accessRuleService = mock(AccessRuleService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new AccessRuleController(accessRuleService));

    private static AccessRule gatedRule() {
        return AdminFixtures.accessRule("AR_ONLY_SEARCH", AdminFixtures.accessRule("AR_GATE_A"), AdminFixtures.accessRule("AR_GATE_B"));
    }

    @Test
    void readingOneAccessRuleReturnsTheEntityJsonWithoutTheMergedMembers() throws Exception {
        AccessRule rule = gatedRule();
        when(accessRuleService.getAccessRuleById(rule.getUuid().toString())).thenReturn(Optional.of(rule));

        mockMvc.perform(get("/accessRule/{accessRuleId}", rule.getUuid())).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(rule))).andExpect(content().string(not(containsString("merged"))));
    }

    @Test
    void listingAccessRulesReturnsABareArrayThatKeepsNullMembers() throws Exception {
        List<AccessRule> rules = List.of(gatedRule(), new AccessRule());
        when(accessRuleService.getAllAccessRules()).thenReturn(rules);

        mockMvc.perform(get("/accessRule")).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(rules)));
    }

    @Test
    void creatingAccessRulesReturnsABareArray() throws Exception {
        List<AccessRule> created = List.of(AdminFixtures.accessRule("AR_ONLY_SEARCH"));
        when(accessRuleService.createFrom(anyList())).thenReturn(created);

        mockMvc.perform(post("/accessRule").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(created)));
    }

    @Test
    void updatingAccessRulesReturnsABareArray() throws Exception {
        List<AccessRule> updated = List.of(gatedRule());
        when(accessRuleService.updateFrom(anyList())).thenReturn(updated);

        mockMvc.perform(
            put("/accessRule").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"uuid\":\"" + updated.getFirst().getUuid() + "\",\"value\":\"/search/\"}]")
        ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(updated)));
    }

    @Test
    void deletingAnAccessRuleReturnsTheRemainingOnesAsABareArray() throws Exception {
        UUID deleted = UUID.randomUUID();
        List<AccessRule> remaining = List.of(gatedRule());
        when(accessRuleService.removeAccessRuleById(deleted.toString())).thenReturn(remaining);

        mockMvc.perform(delete("/accessRule/{accessRuleId}", deleted)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(remaining)));
    }

    @Test
    void ruleTypesAreWrappedInTheirRecord() throws Exception {
        String types = FrozenWire.MAPPER.writeValueAsString(AccessRule.TypeNaming.getTypeNameMap());

        mockMvc.perform(get("/accessRule/allTypes").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
            .andExpect(content().string("{\"types\":" + types + "}"));
    }

    @Test
    void readingAnUnknownAccessRuleIs404InTheErrorEnvelope() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(accessRuleService.getAccessRuleById(unknown.toString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/accessRule/{accessRuleId}", unknown)).andExpect(status().isNotFound())
            .andExpect(content().string("{\"message\":\"AccessRule not found\",\"content\":null}"));
    }

    @Test
    void creatingNoAccessRulesIs400InTheErrorEnvelope() throws Exception {
        when(accessRuleService.createFrom(anyList())).thenReturn(List.of());

        mockMvc.perform(post("/accessRule").contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"No access rules added\",\"content\":null}"));
    }
}
