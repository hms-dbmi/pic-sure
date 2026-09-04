package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dispatch-level tests for {@link UserController}, exercising real request routing through MockMvc. A real request catches mapping and
 * method-parameter mismatches that a direct controller call cannot detect.
 */
public class UserControllerTest {

    private final UserService userService = mock(UserService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();

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
}
