package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.auth.model.response.UserForDisplay;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;

/**
 * Dispatch-level tests for the three {@code /user/me} endpoints of {@link UserController}, exercising real request routing through MockMvc.
 * Each success body is pinned to the JSON the endpoint wrote before it returned a record: the profile to the text the
 * {@code User.UserForDisplay} class serialized to, the consents to the {@link UserConsents} entity's JSON, the long-term token to the
 * one-entry map's JSON. Each error path answers 500 in the {@code {message, content}} body.
 */
public class UserControllerTest {

    private static final String ERROR_BODY =
        "{\"message\":\"Application error\",\"content\":\"Inner application error, please contact admin.\"}";

    private final UserService userService = mock(UserService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new UserController(userService));

    @Test
    public void profileIsTheUserForDisplayJson() throws Exception {
        Set<String> privileges = new LinkedHashSet<>(List.of("SUPER_ADMIN", "PRIV_FENCE_phs000007_c1"));
        UserForDisplay profile =
            new UserForDisplay("8694e3d4-5cb4-410f-8431-993445e6d3f6", "researcher@example.org", privileges, "long-term-token", true);
        when(userService.getCurrentUser("Bearer session-token", null)).thenReturn(profile);

        mockMvc.perform(get("/user/me").header("Authorization", "Bearer session-token")).andExpect(status().isOk()).andExpect(
            content().string(
                "{\"uuid\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"email\":\"researcher@example.org\","
                    + "\"privileges\":[\"SUPER_ADMIN\",\"PRIV_FENCE_phs000007_c1\"],\"token\":\"long-term-token\",\"acceptedTOS\":true}"
            )
        );
    }

    @Test
    public void profileLeavesOutEmptyMembersAndAcceptsHasToken() throws Exception {
        UserForDisplay profile = new UserForDisplay("8694e3d4-5cb4-410f-8431-993445e6d3f6", null, Set.of(), "long-term-token", false);
        when(userService.getCurrentUser(eq("Bearer session-token"), eq(Boolean.TRUE))).thenReturn(profile);

        mockMvc.perform(get("/user/me").param("hasToken", "true").header("Authorization", "Bearer session-token"))
            .andExpect(status().isOk()).andExpect(
                content().string("{\"uuid\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"token\":\"long-term-token\",\"acceptedTOS\":false}")
            );
    }

    @Test
    public void profileWithoutACallerIs500InTheErrorEnvelope() throws Exception {
        when(userService.getCurrentUser(any(), any())).thenReturn(null);

        mockMvc.perform(get("/user/me").header("Authorization", "Bearer session-token")).andExpect(status().isInternalServerError())
            .andExpect(content().string(ERROR_BODY));
    }

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
