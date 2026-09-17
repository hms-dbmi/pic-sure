package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.config.SelfRegistrationConfig;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.auth.model.UserRegistrationRequest;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dispatch-level tests for {@link UserController}, exercising real request routing through MockMvc. A real request catches mapping and
 * method-parameter mismatches that a direct controller call cannot detect.
 */
public class UserControllerTest {

    private final UserService userService = mock(UserService.class);
    private final SelfRegistrationConfig selfRegistrationConfig = mock(SelfRegistrationConfig.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService, selfRegistrationConfig)).build();

    @Test
    public void consentsEndpointDispatchesAndReturnsConsents() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.getUserConsents())
            .thenReturn(new UserConsents().setUserId(userId).setConsents(Map.of("consents", Set.of("phs1234.c1", "phs5678.c2"))));

        mockMvc.perform(get("/user/me/consents")).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId.toString()))
            .andExpect(jsonPath("$.consents.consents", containsInAnyOrder("phs1234.c1", "phs5678.c2")));
    }

    @Test
    public void consentsEndpointReportsApplicationErrorWhenServiceReturnsNull() throws Exception {
        when(userService.getUserConsents()).thenReturn(null);

        mockMvc.perform(get("/user/me/consents")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("Application error"))
            .andExpect(jsonPath("$.content").value("Inner application error, please contact admin."));
    }

    @Test
    public void registerEndpointReturns503WhenSelfRegistrationDisabled() throws Exception {
        when(selfRegistrationConfig.isEnabled()).thenReturn(false);

        mockMvc.perform(
            post("/user/register").contentType("application/json").content("{\"email\":\"new.user@example.com\"}")
        ).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("Self-registration is not enabled on this deployment"));

        verify(userService, never()).registerUser(any(UserRegistrationRequest.class));
    }

    @Test
    public void registerEndpointDelegatesToServiceWhenEnabled() throws Exception {
        when(selfRegistrationConfig.isEnabled()).thenReturn(true);
        when(userService.registerUser(any(UserRegistrationRequest.class))).thenReturn(new User());

        mockMvc.perform(
            post("/user/register").contentType("application/json").content("{\"email\":\"new.user@example.com\"}")
        ).andExpect(status().isOk());

        verify(userService).registerUser(any(UserRegistrationRequest.class));
    }
}
