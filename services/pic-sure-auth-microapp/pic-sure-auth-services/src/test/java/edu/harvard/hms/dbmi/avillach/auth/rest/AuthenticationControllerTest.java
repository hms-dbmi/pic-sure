package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;

/**
 * {@code POST /authentication/{idpProvider}} returns exactly the JSON the provider's {@code HashMap} serialized to, for a provider that
 * sets {@code oktaIdToken} and for one that does not, and each error path keeps its status in the {@code {message, content}} body. The
 * provider receives only the members it can read, so a login that carries extra keys still binds.
 */
class AuthenticationControllerTest {

    private final AuthenticationServiceRegistry registry = mock(AuthenticationServiceRegistry.class);
    private final AuthenticationService provider = mock(AuthenticationService.class);
    private final AuthenticationController controller = new AuthenticationController(registry);
    private final MockMvc mockMvc = FrozenWire.mockMvc(controller);

    private static HashMap<String, String> login(String email) {
        HashMap<String, String> login = new HashMap<>();
        login.put("token", "signed-session-token");
        login.put("userId", "fence|12345");
        login.put("email", email);
        login.put("acceptedTOS", "true");
        login.put("expirationDate", "2026-09-30T14:05:00Z");
        login.put("uuid", "8694e3d4-5cb4-410f-8431-993445e6d3f6");
        return login;
    }

    private static String wire(Map<String, String> login) throws Exception {
        return FrozenWire.MAPPER.writeValueAsString(login);
    }

    @Test
    void aFenceLoginReturnsTheProviderMapJson() throws Exception {
        HashMap<String, String> login = login("researcher@example.org");
        when(registry.getAuthenticationService("fence")).thenReturn(provider);
        when(provider.authenticate(eq(Map.of("code", "SplxlOBeZQQYbYS6WxSbIA")), any())).thenReturn(login);

        mockMvc.perform(
            post("/authentication/{idp}", "fence").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"SplxlOBeZQQYbYS6WxSbIA\",\"state\":\"ignored\"}")
        ).andExpect(status().isOk()).andExpect(content().string(wire(login)));
    }

    @Test
    void anOktaLoginReturnsTheProviderMapJsonWithTheIdToken() throws Exception {
        HashMap<String, String> login = login("researcher@example.org");
        login.put("oktaIdToken", "okta-id-token");
        when(registry.getAuthenticationService("ras")).thenReturn(provider);
        when(provider.authenticate(anyMap(), any())).thenReturn(login);

        mockMvc.perform(post("/authentication/{idp}", "ras").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
            .andExpect(status().isOk()).andExpect(content().string(wire(login)));
    }

    @Test
    void anAuth0LoginPassesTheAccessTokenAndRedirectUriThrough() throws Exception {
        HashMap<String, String> login = login(null);
        when(registry.getAuthenticationService("auth0")).thenReturn(provider);
        when(provider.authenticate(eq(Map.of("access_token", "access-token", "redirectURI", "https://localhost/login/loading")), any()))
            .thenReturn(login);

        mockMvc.perform(
            post("/authentication/{idp}", "auth0").contentType(MediaType.APPLICATION_JSON)
                .content("{\"access_token\":\"access-token\",\"redirectURI\":\"https://localhost/login/loading\"}")
        ).andExpect(status().isOk()).andExpect(content().string(wire(login)));
        assertThat(wire(login)).contains("\"email\":null");
    }

    @Test
    void aLoginWithoutAUserIdIs401InTheErrorEnvelope() throws Exception {
        when(registry.getAuthenticationService("fence")).thenReturn(provider);
        when(provider.authenticate(anyMap(), any())).thenReturn(new HashMap<>(Map.of("token", "signed-session-token")));

        mockMvc.perform(post("/authentication/{idp}", "fence").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"message\":\"Unauthorized\",\"content\":\"User not authenticated.\"}"));
    }

    @Test
    void aRejectedLoginIs401InTheErrorEnvelope() throws Exception {
        when(registry.getAuthenticationService("fence")).thenReturn(provider);
        when(provider.authenticate(anyMap(), any())).thenReturn(null);

        mockMvc.perform(post("/authentication/{idp}", "fence").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("{\"message\":\"Unauthorized\",\"content\":\"User not authenticated.\"}"));
    }

    @Test
    void anUnknownProviderIs400InTheErrorEnvelope() throws Exception {
        when(registry.getAuthenticationService("nosuchidp"))
            .thenThrow(new IllegalArgumentException("No authentication service found for provider: nosuchidp"));

        mockMvc.perform(post("/authentication/{idp}", "nosuchidp").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(
                content().string("{\"message\":\"Invalid request\",\"content\":\"No authentication service found for provider: nosuchidp\"}")
            );
    }

    @Test
    void aProviderTheRegistryCannotSupplyIs400InTheErrorEnvelope() throws Exception {
        when(registry.getAuthenticationService("fence")).thenReturn(null);

        mockMvc.perform(post("/authentication/{idp}", "fence").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid request\",\"content\":\"authenticationService is null\"}"));
    }

    @Test
    void aNullBodyIsRejectedWith400() {
        assertThatThrownBy(() -> controller.authentication("fence", null, new MockHttpServletRequest()))
            .isInstanceOfSatisfying(PicSureResponseException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getContent()).isEqualTo("authRequest is null");
            });
    }

    @Test
    void theProviderReceivesOnlyTheMembersItCanRead() throws Exception {
        when(registry.getAuthenticationService("fence")).thenReturn(provider);
        when(provider.authenticate(anyMap(), any())).thenReturn(login("researcher@example.org"));

        mockMvc.perform(
            post("/authentication/{idp}", "fence").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"abc\",\"persona\":\"researcher\",\"nonce\":\"n\"}")
        ).andExpect(status().isOk());

        verify(provider).authenticate(eq(Map.of("code", "abc", "persona", "researcher")), any());
    }
}
