package edu.harvard.hms.dbmi.avillach.auth.filter;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.SessionIdResolver;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLoggingFilterTest {

    private LoggingClient loggingClient;
    private AuditLoggingFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        filter = new AuditLoggingFilter(loggingClient);
        filterChain = mock(FilterChain.class);
    }

    @Test
    void shouldCategorizeLoginEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/authentication/ras");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "AUTH");
        request.setAttribute(AuditAttributes.ACTION, "auth.login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("AUTH", event.getEventType());
        assertEquals("auth.login", event.getAction());
    }

    @Test
    void shouldCategorizeLogoutEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "AUTH");
        request.setAttribute(AuditAttributes.ACTION, "auth.logout");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("AUTH", captor.getValue().getEventType());
        assertEquals("auth.logout", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeTokenIntrospect() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/token/inspect");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ACCESS");
        request.setAttribute(AuditAttributes.ACTION, "token.introspect");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ACCESS", captor.getValue().getEventType());
        assertEquals("token.introspect", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeUserProfile() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/me");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ACCESS");
        request.setAttribute(AuditAttributes.ACTION, "user.profile");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ACCESS", captor.getValue().getEventType());
        assertEquals("user.profile", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeAdminUserModify() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/user");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "user.modify");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("user.modify", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeRoleModify() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/role");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "role.modify");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("role.modify", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeRoleDelete() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/role/some-uuid");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "role.delete");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("role.delete", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeConnectionModify() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/connection");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "connection.modify");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("connection.modify", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeConnectionDelete() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/connection/some-uuid");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "connection.delete");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("connection.delete", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeApplicationModify() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/application");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "application.modify");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("application.modify", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeApplicationTokenRefresh() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/application/refreshToken/some-uuid");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "application.token_refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("application.token_refresh", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeTosAccept() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tos/accept");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ACCESS");
        request.setAttribute(AuditAttributes.ACTION, "tos.accept");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ACCESS", captor.getValue().getEventType());
        assertEquals("tos.accept", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeTosUpdate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tos/update");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "tos.update");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("tos.update", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeTokenRefresh() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/token/refresh");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ACCESS");
        request.setAttribute(AuditAttributes.ACTION, "token.refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ACCESS", captor.getValue().getEventType());
        assertEquals("token.refresh", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeOpenAccessValidate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/open/validate");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ACCESS");
        request.setAttribute(AuditAttributes.ACTION, "open.validate");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ACCESS", captor.getValue().getEventType());
        assertEquals("open.validate", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeStudyAccessCreate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/studyAccess");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "study_access.create");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("study_access.create", captor.getValue().getAction());
    }

    @Test
    void shouldCategorizeMappingDelete() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/mapping/some-uuid");
        request.setAttribute(AuditAttributes.EVENT_TYPE, "ADMIN");
        request.setAttribute(AuditAttributes.ACTION, "mapping.delete");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("ADMIN", captor.getValue().getEventType());
        assertEquals("mapping.delete", captor.getValue().getAction());
    }

    @Test
    void shouldSkipActuatorHealth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(loggingClient, never()).send(any(LoggingEvent.class));
    }

    @Test
    void shouldSkipSwagger() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger.json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(loggingClient, never()).send(any(LoggingEvent.class));
    }

    @Test
    void shouldNotLogWhenClientDisabled() throws Exception {
        when(loggingClient.isEnabled()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/authentication/ras");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(loggingClient, never()).send(any(LoggingEvent.class));
    }

    @Test
    void shouldExtractClientIpFromXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");

        assertEquals("1.2.3.4", AuditAttributes.extractClientIp(request));
    }

    @Test
    void shouldFallbackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertEquals("10.0.0.1", AuditAttributes.extractClientIp(request));
    }

    @Test
    void shouldUseSessionIdHeader() {
        assertEquals("my-session-123", SessionIdResolver.resolve("my-session-123", "10.0.0.1", "Mozilla/5.0"));
    }

    @Test
    void shouldGenerateSessionIdFromHash() {
        String sessionId = SessionIdResolver.resolve(null, "10.0.0.1", "Mozilla/5.0");
        assertNotNull(sessionId);
        assertFalse(sessionId.isEmpty());
        assertEquals(16, sessionId.length());
    }

    @Test
    void shouldIncludeSessionIdOnEvent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/some/endpoint");
        request.addHeader("X-Session-Id", "test-session");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("test-session", captor.getValue().getSessionId());
    }

    @Test
    void shouldMergeAuditAttributesMetadata() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/some/endpoint");
        AuditAttributes.putMetadata(request, "custom_field", "custom_value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("custom_value", captor.getValue().getMetadata().get("custom_field"));
    }

    @Test
    void shouldPassBearerTokenThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/some/endpoint");
        request.addHeader("Authorization", "Bearer my-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(loggingClient).send(any(LoggingEvent.class), eq("Bearer my-token"), any());
    }

    @Test
    void shouldIncludeErrorMapFor4xx() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/some/endpoint");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(403);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertNotNull(captor.getValue().getError());
        assertEquals("client_error", captor.getValue().getError().get("error_type"));
    }

    @Test
    void shouldIncludeErrorMapFor5xx() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/some/endpoint");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertNotNull(captor.getValue().getError());
        assertEquals("server_error", captor.getValue().getError().get("error_type"));
    }
}
