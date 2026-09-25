package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AuthenticationControllerTest {

    private final AuthenticationServiceRegistry registry = mock(AuthenticationServiceRegistry.class);
    private final AuthenticationController controller = new AuthenticationController(registry);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void returnsTheCompletedLoginResponse() throws Exception {
        AuthenticationService provider = mock(AuthenticationService.class);
        when(registry.getAuthenticationService("test")).thenReturn(provider);
        HashMap<String, String> response = new HashMap<>(Map.of("userId", "researcher", "token", "signed-user-token"));
        when(provider.authenticate(Map.of(), request.getServerName())).thenReturn(response);

        assertEquals(200, controller.authentication("test", Map.of(), request).getStatusCode().value());
    }

    @Test
    void rejectsAResponseWithoutASubject() throws Exception {
        AuthenticationService provider = mock(AuthenticationService.class);
        when(registry.getAuthenticationService("test")).thenReturn(provider);
        when(provider.authenticate(Map.of(), request.getServerName())).thenReturn(new HashMap<>(Map.of("token", "token")));

        assertEquals(401, controller.authentication("test", Map.of(), request).getStatusCode().value());
    }

    @Test
    void rejectsAnUnsuccessfulLogin() throws Exception {
        when(registry.getAuthenticationService("test")).thenReturn(mock(AuthenticationService.class));
        assertEquals(401, controller.authentication("test", Map.of(), request).getStatusCode().value());
    }
}
