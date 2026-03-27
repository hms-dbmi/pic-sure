package edu.harvard.dbmi.avillach.security;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import edu.harvard.dbmi.avillach.data.entity.AuthUser;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.service.AuditContext;

public class AuditLoggingFilterTest {

    private AuditLoggingFilter filter;
    private LoggingClient loggingClient;
    private HttpServletRequest httpServletRequest;
    private AuditContext auditContext;

    @Before
    public void setup() {
        filter = new AuditLoggingFilter();
        loggingClient = mock(LoggingClient.class);
        when(loggingClient.isEnabled()).thenReturn(true);
        httpServletRequest = mock(HttpServletRequest.class);
        when(httpServletRequest.getRemoteAddr()).thenReturn("192.168.1.1");
        when(httpServletRequest.getLocalAddr()).thenReturn("10.0.0.1");
        when(httpServletRequest.getLocalPort()).thenReturn(8080);

        auditContext = mock(AuditContext.class);
        when(auditContext.getMetadata()).thenReturn(new java.util.HashMap<>());

        filter.loggingClient = loggingClient;
        filter.auditContext = auditContext;
        filter.httpServletRequest = httpServletRequest;
    }

    private ContainerRequestContext mockRequestContext(String path, String method) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080" + path));
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getMethod()).thenReturn(method);
        when(ctx.getProperty("audit_start_time")).thenReturn(System.currentTimeMillis() - 50L);
        return ctx;
    }

    private ContainerResponseContext mockResponseContext(int status) {
        ContainerResponseContext ctx = mock(ContainerResponseContext.class);
        when(ctx.getStatus()).thenReturn(status);
        when(ctx.getHeaderString("Content-Type")).thenReturn("application/json");
        when(ctx.getLength()).thenReturn(-1);
        return ctx;
    }

    // ---- Request phase ----

    @Test
    public void testRequestFilterSetsAuditStartTime() throws IOException {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        filter.filter(ctx);
        verify(ctx).setProperty(eq("audit_start_time"), anyLong());
    }

    // ---- URL categorization ----

    @Test
    public void testQueryPostMapsToQuerySubmitted() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/query", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.submitted", event.getAction());
    }

    @Test
    public void testQuerySyncPostMapsToQuerySync() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/query/sync", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.sync", event.getAction());
    }

    @Test
    public void testQueryStatusPostMapsToQueryStatus() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/query/abc-123/status", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.status", event.getAction());
    }

    @Test
    public void testQueryResultPostMapsToDataAccessQueryResult() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/query/abc-123/result", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("DATA_ACCESS", event.getEventType());
        assertEquals("query.result", event.getAction());
    }

    @Test
    public void testQuerySignedUrlPostMapsToDataAccessQuerySignedUrl() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/query/abc-123/signed-url", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("DATA_ACCESS", event.getEventType());
        assertEquals("query.signed_url", event.getAction());
    }

    @Test
    public void testQueryMetadataGetMapsToQueryMetadata() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/query/abc-123/metadata", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.metadata", event.getAction());
    }

    @Test
    public void testSearchPostMapsToSearch() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/search/abc-123", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("SEARCH", event.getEventType());
        assertEquals("search.execute", event.getAction());
    }

    @Test
    public void testSearchValuesGetMapsToSearchValues() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/search/abc-123/values/", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("SEARCH", event.getEventType());
        assertEquals("search.values", event.getAction());
    }

    @Test
    public void testProxyPostMapsToProxyRequest() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/proxy/container/path", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("PROXY", event.getEventType());
        assertEquals("proxy.request", event.getAction());
    }

    @Test
    public void testV3QueryPostMapsToQuerySubmittedWithApiVersion() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/v3/query", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.submitted", event.getAction());
        assertEquals("v3", event.getMetadata().get("api_version"));
    }

    @Test
    public void testPicsurePrefixIsStrippedForCategorization() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/PICSURE/query", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("query.submitted", event.getAction());
    }

    @Test
    public void testUnknownPathMapsToOtherWithHttpMethod() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something/else", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();
        assertEquals("OTHER", event.getEventType());
        assertEquals("GET", event.getAction());
    }

    // ---- Skipped paths ----

    @Test
    public void testSkippedPathsDoNotSendEvents() throws IOException {
        String[] skippedPaths = {
            "/system/status", "/openapi.json", "/info/resources", "/info/abc-123", "/bin/continuous",
            "/proxy/pic-sure-logging/audit"
        };

        for (String path : skippedPaths) {
            reset(loggingClient);
            when(loggingClient.isEnabled()).thenReturn(true);

            ContainerRequestContext reqCtx = mockRequestContext(path, "GET");
            ContainerResponseContext respCtx = mockResponseContext(200);

            filter.filter(reqCtx, respCtx);

            verify(loggingClient, never()).send(any(LoggingEvent.class));
            verify(loggingClient, never()).send(any(LoggingEvent.class), anyString(), anyString());
        }
    }

    // ---- IP extraction ----

    @Test
    public void testXForwardedForSingleIpUsedAsSrcIp() throws IOException {
        when(httpServletRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50");

        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("203.0.113.50", captor.getValue().getRequest().getSrcIp());
    }

    @Test
    public void testXForwardedForMultipleIpsUsesFirst() throws IOException {
        when(httpServletRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18, 150.172.238.178");

        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("203.0.113.50", captor.getValue().getRequest().getSrcIp());
    }

    @Test
    public void testNoXForwardedForFallsBackToRemoteAddr() throws IOException {
        when(httpServletRequest.getHeader("X-Forwarded-For")).thenReturn(null);

        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("192.168.1.1", captor.getValue().getRequest().getSrcIp());
    }

    // ---- Session ID ----

    @Test
    public void testXSessionIdHeaderUsedWhenPresent() throws IOException {
        when(httpServletRequest.getHeader("X-Session-Id")).thenReturn("my-session-123");

        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertEquals("my-session-123", captor.getValue().getMetadata().get("session_id"));
    }

    @Test
    public void testNoXSessionIdGeneratesHashFromIpAndUserAgent() throws IOException {
        when(httpServletRequest.getHeader("X-Session-Id")).thenReturn(null);
        when(httpServletRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpServletRequest.getHeader("User-Agent")).thenReturn("TestBrowser/1.0");

        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());

        String expectedRaw = "192.168.1.1|TestBrowser/1.0";
        String expectedHash = Integer.toHexString(expectedRaw.hashCode());
        assertEquals(expectedHash, captor.getValue().getMetadata().get("session_id"));
    }

    // ---- Error detection ----

    @Test
    public void testStatus200HasNoErrorMap() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        assertNull(captor.getValue().getError());
    }

    @Test
    public void testStatus404HasClientErrorMap() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(404);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        Map<String, Object> error = captor.getValue().getError();
        assertNotNull(error);
        assertEquals(404, error.get("status"));
        assertEquals("client_error", error.get("error_type"));
    }

    @Test
    public void testStatus500HasServerErrorMap() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        ContainerResponseContext respCtx = mockResponseContext(500);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        Map<String, Object> error = captor.getValue().getError();
        assertNotNull(error);
        assertEquals(500, error.get("status"));
        assertEquals("server_error", error.get("error_type"));
    }

    // ---- User extraction ----

    @Test
    public void testAuthUserInSecurityContextExtractsUserIdAndEmail() throws IOException {
        AuthUser authUser = new AuthUser();
        authUser.setUserId("user-42");
        authUser.setEmail("user@example.com");

        SecurityContext secCtx = mock(SecurityContext.class);
        when(secCtx.getUserPrincipal()).thenReturn(authUser);

        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        when(reqCtx.getSecurityContext()).thenReturn(secCtx);
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertEquals("user-42", metadata.get("user_id"));
        assertEquals("user@example.com", metadata.get("user_email"));
    }

    @Test
    public void testNoAuthUserButUsernamePropertyUsesUsernameAsUserId() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        when(reqCtx.getProperty("username")).thenReturn("prop-user");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertEquals("prop-user", metadata.get("user_id"));
    }

    @Test
    public void testNoUserInfoDoesNotIncludeUserIdInMetadata() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        when(reqCtx.getProperty("username")).thenReturn(null);
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMetadata();
        assertFalse(metadata.containsKey("user_id"));
    }

    // ---- Bearer token passthrough ----

    @Test
    public void testAuthorizationHeaderPresentCallsSendWithAuthAndRequestId() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        when(reqCtx.getHeaderString("Authorization")).thenReturn("Bearer token123");
        when(httpServletRequest.getHeader("X-Request-Id")).thenReturn("req-456");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture(), eq("Bearer token123"), eq("req-456"));
        verify(loggingClient, never()).send(any(LoggingEvent.class));
    }

    @Test
    public void testNoAuthHeaderAndNoRequestIdCallsSendWithoutExtra() throws IOException {
        ContainerRequestContext reqCtx = mockRequestContext("/something", "GET");
        when(reqCtx.getHeaderString("Authorization")).thenReturn(null);
        when(httpServletRequest.getHeader("X-Request-Id")).thenReturn(null);
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        verify(loggingClient, never()).send(any(LoggingEvent.class), anyString(), anyString());
    }

    // ---- Disabled / null logging client ----

    @Test
    public void testNullLoggingClientDoesNotSendOrThrow() throws IOException {
        filter.loggingClient = null;

        ContainerRequestContext reqCtx = mockRequestContext("/query", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        // Should not throw
        filter.filter(reqCtx, respCtx);
    }

    @Test
    public void testDisabledLoggingClientDoesNotSend() throws IOException {
        when(loggingClient.isEnabled()).thenReturn(false);

        ContainerRequestContext reqCtx = mockRequestContext("/query", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        verify(loggingClient, never()).send(any(LoggingEvent.class));
        verify(loggingClient, never()).send(any(LoggingEvent.class), anyString(), anyString());
    }

    // ---- RequestInfo fields ----

    @Test
    public void testRequestInfoPopulatedCorrectly() throws IOException {
        when(httpServletRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpServletRequest.getHeader("User-Agent")).thenReturn("TestAgent/2.0");

        ContainerRequestContext reqCtx = mockRequestContext("/query", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);

        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> captor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(captor.capture());
        LoggingEvent event = captor.getValue();

        assertEquals("POST", event.getRequest().getMethod());
        assertEquals("/query", event.getRequest().getUrl());
        assertEquals("192.168.1.1", event.getRequest().getSrcIp());
        assertEquals("10.0.0.1", event.getRequest().getDestIp());
        assertEquals(Integer.valueOf(8080), event.getRequest().getDestPort());
        assertEquals("TestAgent/2.0", event.getRequest().getHttpUserAgent());
        assertEquals(Integer.valueOf(200), event.getRequest().getStatus());
        assertEquals("application/json", event.getRequest().getHttpContentType());
        assertNull(event.getRequest().getBytes());
        assertNotNull(event.getRequest().getDuration());
    }

    @Test
    public void testMergesAuditContextMetadata() throws IOException {
        java.util.Map<String, Object> serviceMetadata = new java.util.HashMap<>();
        serviceMetadata.put("resource_id", "abc-123");
        serviceMetadata.put("resource_name", "test-resource");
        serviceMetadata.put("query_id", "def-456");
        when(auditContext.getMetadata()).thenReturn(serviceMetadata);

        ContainerRequestContext reqCtx = mockRequestContext("/PICSURE/query", "POST");
        ContainerResponseContext respCtx = mockResponseContext(200);
        filter.filter(reqCtx, respCtx);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture());
        LoggingEvent event = eventCaptor.getValue();
        assertEquals("abc-123", event.getMetadata().get("resource_id"));
        assertEquals("test-resource", event.getMetadata().get("resource_name"));
        assertEquals("def-456", event.getMetadata().get("query_id"));
        // Also has filter-level metadata
        assertNotNull(event.getMetadata().get("session_id"));
    }
}
