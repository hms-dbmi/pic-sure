package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.PrivilegeService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserMetadataMappingService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins that an admin endpoint naming a record by path identifier rejects a value that is not a UUID with a 400 before any service runs, and
 * never repeats the value in the response.
 */
class PathIdentifierWebTest {

    private static final String NOT_A_UUID = "not-a-uuid<script>";

    private final ApplicationService applicationService = mock(ApplicationService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final AccessRuleService accessRuleService = mock(AccessRuleService.class);
    private final PrivilegeService privilegeService = mock(PrivilegeService.class);
    private final UserService userService = mock(UserService.class);
    private final UserMetadataMappingService mappingService = mock(UserMetadataMappingService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
        new ApplicationController(applicationService), new RoleController(roleService), new AccessRuleController(accessRuleService),
        new PrivilegeController(privilegeService), new UserController(userService), new UserMetadataMappingWebController(mappingService)
    ).setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void applicationReadRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/application/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(applicationService);
    }

    @Test
    void applicationDeleteRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(delete("/application/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(applicationService);
    }

    @Test
    void applicationTokenRefreshRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/application/refreshToken/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(applicationService);
    }

    @Test
    void roleReadRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/role/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(roleService);
    }

    @Test
    void roleDeleteRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(delete("/role/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(roleService);
    }

    @Test
    void accessRuleDeleteRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(delete("/accessRule/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(accessRuleService);
    }

    @Test
    void userReadRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/user/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(userService);
    }

    @Test
    void privilegeReadRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/privilege/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(privilegeService);
    }

    @Test
    void privilegeDeleteRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(delete("/privilege/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(privilegeService);
    }

    @Test
    void mappingDeleteRejectsAMalformedIdentifier() throws Exception {
        mockMvc.perform(delete("/mapping/{id}", NOT_A_UUID)).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("script"))));
        verifyNoInteractions(mappingService);
    }
}
