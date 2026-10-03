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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.PrivilegeService;

/**
 * Each privilege endpoint returns the JSON the {@link Privilege} entity serialized to: {@code accessRules} before {@code application}, the
 * nested application without its {@code url} and token, null and empty members left out, and the nested access rules without their merged
 * members. The one error path the controller owns keeps its 400.
 */
class PrivilegeControllerTest {

    private final PrivilegeService privilegeService = mock(PrivilegeService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new PrivilegeController(privilegeService));

    private static Privilege fullPrivilege() {
        return AdminFixtures.privilege(
            "PRIV_FENCE_phs000007_c1", AdminFixtures.application("PICSURE"),
            AdminFixtures.accessRule("AR_ONLY_SEARCH", AdminFixtures.accessRule("AR_GATE_A"))
        );
    }

    private static Privilege barePrivilege() {
        Privilege privilege = AdminFixtures.privilege("PRIV_BARE", null);
        privilege.setDescription(null);
        return privilege;
    }

    @Test
    void readingOnePrivilegeReturnsTheEntityJson() throws Exception {
        Privilege privilege = fullPrivilege();
        when(privilegeService.getPrivilegeById(privilege.getUuid().toString())).thenReturn(privilege);

        mockMvc.perform(get("/privilege/{privilegeId}", privilege.getUuid())).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(privilege))).andExpect(content().string(not(containsString("canary"))))
            .andExpect(content().string(not(containsString("\"url\""))));
    }

    @Test
    void listingPrivilegesReturnsABareArrayThatLeavesOutEmptyMembers() throws Exception {
        List<Privilege> privileges = List.of(fullPrivilege(), barePrivilege());
        when(privilegeService.getPrivilegesAll()).thenReturn(privileges);

        mockMvc.perform(get("/privilege")).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(privileges)));
    }

    @Test
    void creatingPrivilegesReturnsABareArray() throws Exception {
        List<Privilege> created = List.of(fullPrivilege());
        when(privilegeService.createFrom(anyList())).thenReturn(created);

        mockMvc.perform(
            post("/privilege").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"name\":\"PRIV_FENCE_phs000007_c1\",\"application\":{\"uuid\":\"" + UUID.randomUUID() + "\"}}]")
        ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(created)));
    }

    @Test
    void updatingPrivilegesReturnsEveryPrivilegeAsABareArray() throws Exception {
        List<Privilege> all = List.of(fullPrivilege(), barePrivilege());
        when(privilegeService.updateFrom(anyList())).thenReturn(all);

        mockMvc.perform(
            put("/privilege").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"uuid\":\"" + all.getFirst().getUuid() + "\",\"description\":\"edited\"}]")
        ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(all)));
    }

    @Test
    void deletingAPrivilegeReturnsTheRemainingOnesAsABareArray() throws Exception {
        UUID deleted = UUID.randomUUID();
        List<Privilege> remaining = List.of(fullPrivilege());
        when(privilegeService.deletePrivilegeByPrivilegeId(deleted.toString())).thenReturn(remaining);

        mockMvc.perform(delete("/privilege/{privilegeId}", deleted)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.json(remaining)));
    }

    @Test
    void readingAnUnknownPrivilegeIs400InTheErrorEnvelope() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(privilegeService.getPrivilegeById(unknown.toString())).thenReturn(null);

        mockMvc.perform(get("/privilege/{privilegeId}", unknown)).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"Privilege not found\"}"));
    }
}
