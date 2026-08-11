package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.model.request.AuthenticationRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.AuthenticationResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;

/** The wire contract of {@code POST /authentication/{idpProvider}}: the login surface every PIC-SURE client hits first. */
class AuthenticationControllerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuthenticationServiceRegistry registry;
    private SessionService sessionService;
    private AuthenticationService authenticationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registry = mock(AuthenticationServiceRegistry.class);
        sessionService = mock(SessionService.class);
        authenticationService = mock(AuthenticationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthenticationController(registry, sessionService))
            .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
            .build();
    }

    private AuthenticationResponse profile() {
        return new AuthenticationResponse("jwt", "okta|123", "u@example.com", "true", "2026-08-01T00:00:00Z", "uuid-1", null);
    }

    /** Every key today's identity providers read binds onto the record, including Auth0's snake_case {@code access_token}. */
    @Test
    void bindsEveryIdpKeyOntoTheTypedRequest() throws Exception {
        when(registry.getAuthenticationService("auth0")).thenReturn(authenticationService);
        when(authenticationService.authenticate(any(), anyString())).thenReturn(profile());

        mockMvc.perform(
            post("/authentication/auth0").contentType("application/json")
                .content("{\"code\":\"c\",\"access_token\":\"at\",\"redirectURI\":\"https://ui/callback\"}")
        ).andExpect(status().isOk());

        ArgumentCaptor<AuthenticationRequest> captor = ArgumentCaptor.forClass(AuthenticationRequest.class);
        verify(authenticationService).authenticate(captor.capture(), anyString());
        AuthenticationRequest bound = captor.getValue();
        assertEquals("c", bound.code());
        assertEquals("at", bound.accessToken());
        assertEquals("https://ui/callback", bound.redirectURI());
    }

    /** Login is not a place to discover a strict binder: an unmodelled key must not lock the user out. */
    @Test
    void unknownKeysAreTolerated() throws Exception {
        when(registry.getAuthenticationService("fence")).thenReturn(authenticationService);
        when(authenticationService.authenticate(any(), anyString())).thenReturn(profile());

        mockMvc.perform(post("/authentication/fence").contentType("application/json").content("{\"code\":\"c\",\"state\":\"xyz\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void answersTheTypedProfileBareWithItsHistoricalKeys() throws Exception {
        when(registry.getAuthenticationService("ras")).thenReturn(authenticationService);
        when(authenticationService.authenticate(any(), anyString())).thenReturn(profile());

        MvcResult result = mockMvc.perform(post("/authentication/ras").contentType("application/json").content("{\"code\":\"c\"}"))
            .andExpect(status().isOk()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("jwt", body.get("token").asText());
        assertEquals("okta|123", body.get("userId").asText());
        assertEquals("u@example.com", body.get("email").asText());
        assertEquals("2026-08-01T00:00:00Z", body.get("expirationDate").asText());
        assertEquals("uuid-1", body.get("uuid").asText());
        // The map put "" + acceptedTOS; the wire has always carried a string here.
        assertEquals("true", body.get("acceptedTOS").asText());
        assertFalse(body.get("acceptedTOS").isBoolean(), "acceptedTOS is frozen as a string");
        assertFalse(body.has("oktaIdToken"), "absent for non-Okta providers, exactly as the map had no such key");
        assertFalse(body.has("content"), "the response envelope must be gone");
    }

    @Test
    void startsTheSessionOnTheUserIdItReturns() throws Exception {
        when(registry.getAuthenticationService("ras")).thenReturn(authenticationService);
        when(authenticationService.authenticate(any(), anyString())).thenReturn(profile());

        mockMvc.perform(post("/authentication/ras").contentType("application/json").content("{\"code\":\"c\"}"))
            .andExpect(status().isOk());

        verify(sessionService).startSession("okta|123");
    }

    /** A failed login answers the uniform error contract, still carrying the message the UI shows. */
    @Test
    void deniesWithTheUniformErrorContract() throws Exception {
        when(registry.getAuthenticationService("ras")).thenReturn(authenticationService);
        when(authenticationService.authenticate(any(), anyString())).thenReturn(null);

        MvcResult result = mockMvc.perform(post("/authentication/ras").contentType("application/json").content("{\"code\":\"c\"}"))
            .andExpect(status().isUnauthorized()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("unauthorized", body.get("errorType").asText());
        assertEquals("User not authenticated.", body.get("message").asText());
        verify(sessionService, never()).startSession(anyString());
    }

    @Test
    void unknownIdpIsABadRequestInTheUniformShape() throws Exception {
        when(registry.getAuthenticationService("nope")).thenReturn(null);

        MvcResult result = mockMvc.perform(post("/authentication/nope").contentType("application/json").content("{\"code\":\"c\"}"))
            .andExpect(status().isBadRequest()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("bad_request", body.get("errorType").asText());
    }
}
