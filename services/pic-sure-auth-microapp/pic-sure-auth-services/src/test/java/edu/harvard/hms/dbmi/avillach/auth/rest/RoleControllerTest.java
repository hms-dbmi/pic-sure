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

import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;

/**
 * The two role reads return the JSON the {@link Role} entity serialized to, bare. Create, update and delete return it inside the
 * {@code {message, content}} envelope with a fixed message for each endpoint. The three error paths the controller owns answer 400.
 */
class RoleControllerTest {

    private final RoleService roleService = mock(RoleService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new RoleController(roleService));

    private static Role fullRole() {
        return AdminFixtures.role(
            "PIC-SURE Top Admin",
            AdminFixtures.privilege("SUPER_ADMIN", AdminFixtures.application("PICSURE"), AdminFixtures.accessRule("AR_ONLY_SEARCH")),
            AdminFixtures.privilege("ADMIN", null)
        );
    }

    private static Role roleWithoutPrivileges() {
        Role role = AdminFixtures.role("EMPTY_ROLE");
        role.setDescription(null);
        return role;
    }

    @Test
    void readingOneRoleReturnsTheEntityJson() throws Exception {
        Role role = fullRole();
        when(roleService.getRoleById(role.getUuid().toString())).thenReturn(Optional.of(role));

        mockMvc.perform(get("/role/{roleId}", role.getUuid())).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(role)))
            .andExpect(content().string(not(containsString("canary"))));
    }

    @Test
    void listingRolesReturnsABareArrayThatLeavesOutEmptyMembers() throws Exception {
        List<Role> roles = List.of(fullRole(), roleWithoutPrivileges());
        when(roleService.getAllRoles()).thenReturn(roles);

        mockMvc.perform(get("/role")).andExpect(status().isOk()).andExpect(content().string(FrozenWire.json(roles)));
    }

    @Test
    void creatingRolesReturnsTheEnvelope() throws Exception {
        List<Role> created = List.of(fullRole());
        when(roleService.createFrom(anyList())).thenReturn(created);

        mockMvc.perform(post("/role").contentType(MediaType.APPLICATION_JSON).content("[{\"name\":\"PIC-SURE Top Admin\"}]"))
            .andExpect(status().isOk()).andExpect(content().string(FrozenWire.envelope("All roles are added.", FrozenWire.json(created))));
    }

    @Test
    void updatingRolesReturnsTheEnvelope() throws Exception {
        List<Role> updated = List.of(fullRole());
        when(roleService.updateFrom(anyList())).thenReturn(updated);

        mockMvc
            .perform(
                put("/role").contentType(MediaType.APPLICATION_JSON)
                    .content("[{\"uuid\":\"" + updated.getFirst().getUuid() + "\",\"description\":\"edited\"}]")
            ).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.envelope("All Roles are updated.", FrozenWire.json(updated))));
    }

    @Test
    void deletingARoleReturnsTheRemainingOnesInTheEnvelope() throws Exception {
        UUID deleted = UUID.randomUUID();
        List<Role> remaining = List.of(fullRole());
        when(roleService.removeRoleById(deleted.toString())).thenReturn(Optional.of(remaining));

        mockMvc.perform(delete("/role/{roleId}", deleted)).andExpect(status().isOk()).andExpect(
            content().string(
                FrozenWire.envelope(
                    "Successfully deleted role by id: " + deleted + ", listing rest of the role(s) as below", FrozenWire.json(remaining)
                )
            )
        );
    }

    @Test
    void readingAnUnknownRoleIs400InTheErrorEnvelope() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(roleService.getRoleById(unknown.toString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/role/{roleId}", unknown)).andExpect(status().isBadRequest()).andExpect(
            content().string("{\"message\":\"Invalid request\",\"content\":\"Role is not found by given role ID: " + unknown + "\"}")
        );
    }

    @Test
    void updatingNoRolesIs400InTheErrorEnvelope() throws Exception {
        when(roleService.updateFrom(anyList())).thenReturn(List.of());

        mockMvc.perform(put("/role").contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"No Role(s) has been updated.\"}"));
    }

    @Test
    void deletingAnUnknownRoleIs400InTheErrorEnvelope() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(roleService.removeRoleById(unknown.toString())).thenReturn(Optional.empty());

        mockMvc.perform(delete("/role/{roleId}", unknown)).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"Role not found - uuid: " + unknown + "\"}"));
    }
}
