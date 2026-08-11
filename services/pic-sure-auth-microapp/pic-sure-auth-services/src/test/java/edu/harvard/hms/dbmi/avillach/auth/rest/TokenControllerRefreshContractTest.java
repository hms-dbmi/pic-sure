package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.model.InvalidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.ValidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TokenService;

/** The wire contract of {@code GET /token/refresh}. */
class TokenControllerRefreshContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenService tokenService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TokenController(tokenService)).setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    @Test
    void returnsTheTypedRecordWithItsHistoricalKeys() throws Exception {
        when(tokenService.refreshToken(anyString())).thenReturn(new ValidRefreshToken("jwt", "2026-08-01T00:00:00Z"));

        MvcResult result = mockMvc.perform(get("/token/refresh").header("Authorization", "Bearer x")).andExpect(status().isOk())
            .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("jwt", body.get("token").asText());
        assertEquals("2026-08-01T00:00:00Z", body.get("expirationDate").asText());
        assertEquals(2, body.size(), "no envelope, no extra keys");
        assertFalse(body.has("content"), "the response envelope must be gone");
    }

    /** A refusal used to be a 400 whose body was a bare JSON string. It is now the uniform error contract, message preserved. */
    @Test
    void refusalCarriesTheReasonInTheUniformErrorContract() throws Exception {
        when(tokenService.refreshToken(anyString())).thenReturn(new InvalidRefreshToken("Token is expired"));

        MvcResult result = mockMvc.perform(get("/token/refresh").header("Authorization", "Bearer x")).andExpect(status().isBadRequest())
            .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("bad_request", body.get("errorType").asText());
        assertEquals("Token is expired", body.get("message").asText());
    }
}
