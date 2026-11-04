package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;

import edu.harvard.hms.dbmi.avillach.auth.model.InvalidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.ValidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TokenService;

/**
 * {@code POST /token/inspect} writes the same members with the same values as the {@code HashMap} the token service builds, on the accepted
 * path with every claim of the token and on the rejected path with {@code active} and {@code message} alone, and the service receives the
 * token and the request description as a plain map.
 *
 * <p>The success bodies are compared as JSON trees rather than as text, each tree parsed from the text the service's mapper writes so that
 * a number compares by value. The service builds a {@code HashMap}, whose iteration order is not part of any contract.</p>
 *
 * <p>{@code GET /token/refresh} writes the two members of a valid refresh and answers 400 in the {@code {message, content}} body for an
 * invalid one. The success body compares as a JSON tree too.</p>
 */
class TokenControllerTest {

    private final TokenService tokenService = mock(TokenService.class);
    private final MockMvc mockMvc = FrozenWire.mockMvc(new TokenController(tokenService, FrozenWire.MAPPER));

    private static Map<String, Object> acceptedToken() {
        Map<String, Object> result = new HashMap<>();
        result.put("active", true);
        result.put("tokenRefreshed", false);
        result.put("sub", "fence|12345");
        result.put("uuid", "8694e3d4-5cb4-410f-8431-993445e6d3f6");
        result.put("email", "researcher@example.org");
        result.put("name", "Researcher");
        result.put("idp", "fence");
        result.put("sid", "9a0d4a3a-1b7e-4a3c-9c1e-2f1d6b1c7a55");
        result.put("jti", "whatever");
        result.put("iss", "edu.harvard.hms.dbmi.psama");
        result.put("iat", 1790777100L);
        result.put("exp", 1790780700L);
        result.put("roles", "PIC-SURE Top Admin,MANAGED_phs000007_c1");
        result.put("privileges", new HashSet<>(Set.of("SUPER_ADMIN", "PRIV_FENCE_phs000007_c1")));
        return result;
    }

    private static JsonNode tree(String json) throws Exception {
        return FrozenWire.MAPPER.readTree(json);
    }

    private static JsonNode wire(Object value) throws Exception {
        return tree(FrozenWire.MAPPER.writeValueAsString(value));
    }

    @Test
    void anAcceptedTokenWritesEveryClaimBesideTheNamedMembers() throws Exception {
        Map<String, Object> result = acceptedToken();
        when(tokenService.inspectToken(anyMap())).thenReturn(result);

        String body = mockMvc.perform(
            post("/token/inspect").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"signed-session-token\",\"request\":{\"Target Service\":\"/picsure/query/sync\"}}")
        ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(tree(body)).isEqualTo(wire(result));
    }

    @Test
    void aRefreshedTokenWritesTheReplacementToken() throws Exception {
        Map<String, Object> result = acceptedToken();
        result.put("tokenRefreshed", true);
        result.put("token", "replacement-token");
        when(tokenService.inspectToken(anyMap())).thenReturn(result);

        String body = mockMvc.perform(post("/token/inspect").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(tree(body)).isEqualTo(wire(result));
    }

    @Test
    void aRejectedTokenWritesActiveAndMessageOnly() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("active", false);
        result.put("message", "Token not found");
        when(tokenService.inspectToken(anyMap())).thenReturn(result);

        mockMvc.perform(post("/token/inspect").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())
            .andExpect(content().string("{\"active\":false,\"message\":\"Token not found\"}"));
    }

    @Test
    void theServiceReceivesTheTokenAndThePlainRequestTree() throws Exception {
        when(tokenService.inspectToken(anyMap())).thenReturn(new HashMap<>(Map.of("active", false)));

        mockMvc.perform(
            post("/token/inspect").contentType(MediaType.APPLICATION_JSON).content(
                "{\"token\":\"signed-session-token\",\"request\":{\"Target Service\":\"/picsure/query/sync\","
                    + "\"query\":{\"resourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"expectedResultType\":\"COUNT\","
                    + "\"limit\":10}},\"extra\":\"ignored\"}"
            )
        ).andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.captor();
        verify(tokenService).inspectToken(input.capture());
        assertThat(input.getValue()).isInstanceOf(HashMap.class).containsOnlyKeys("token", "request");
        assertThat(input.getValue().get("token")).isEqualTo("signed-session-token");
        assertThat(input.getValue().get("request")).isEqualTo(
            Map.of(
                "Target Service", "/picsure/query/sync", "query",
                Map.of("resourceUUID", "8694e3d4-5cb4-410f-8431-993445e6d3f6", "expectedResultType", "COUNT", "limit", 10)
            )
        );
    }

    @Test
    void privilegesAreWrittenAsAnArrayAndClaimsKeepTheirTypes() throws Exception {
        when(tokenService.inspectToken(anyMap())).thenReturn(acceptedToken());

        String body = mockMvc.perform(post("/token/inspect").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}"))
            .andReturn().getResponse().getContentAsString();

        JsonNode tree = tree(body);
        assertThat(tree.path("privileges").isArray()).isTrue();
        assertThat(List.of("iat", "exp")).allSatisfy(claim -> assertThat(tree.path(claim).isIntegralNumber()).isTrue());
        assertThat(tree.path("roles").asText()).isEqualTo("PIC-SURE Top Admin,MANAGED_phs000007_c1");
        assertThat(tree.has("claims")).isFalse();
    }

    @Test
    void aValidRefreshWritesTheTokenAndItsExpiry() throws Exception {
        when(tokenService.refreshToken("Bearer signed-session-token"))
            .thenReturn(new ValidRefreshToken("refreshed-token", "2026-09-30T15:05:00Z"));

        String body = mockMvc.perform(get("/token/refresh").header("Authorization", "Bearer signed-session-token")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(tree(body)).isEqualTo(wire(Map.of("token", "refreshed-token", "expirationDate", "2026-09-30T15:05:00Z")));
        assertThat(body).isEqualTo("{\"token\":\"refreshed-token\",\"expirationDate\":\"2026-09-30T15:05:00Z\"}");
    }

    @Test
    void anInvalidRefreshIs400InTheErrorEnvelope() throws Exception {
        when(tokenService.refreshToken("Bearer signed-session-token")).thenReturn(new InvalidRefreshToken("User has been deactivated."));

        mockMvc.perform(get("/token/refresh").header("Authorization", "Bearer signed-session-token")).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"User has been deactivated.\"}"));
    }
}
