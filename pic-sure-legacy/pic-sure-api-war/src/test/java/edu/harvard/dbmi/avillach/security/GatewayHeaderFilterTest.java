package edu.harvard.dbmi.avillach.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import edu.harvard.dbmi.avillach.PicSureWarInit;
import edu.harvard.dbmi.avillach.data.entity.AuthUser;

public class GatewayHeaderFilterTest {

    private GatewayHeaderFilter filter;
    private PicSureWarInit picSureWarInit;

    @Before
    public void setup() {
        filter = new GatewayHeaderFilter();
        picSureWarInit = mock(PicSureWarInit.class);
        filter.picSureWarInit = picSureWarInit;
        filter.uriInfo = mock(UriInfo.class);
        when(filter.uriInfo.getRequestUri()).thenReturn(URI.create("https://picsure.example.org/query"));
    }

    private ContainerRequestContext createRequestContext(String path) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        return ctx;
    }

    @Test
    public void gatewayOwnsAuthOff_leavesSecurityContextUntouched() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(false);
        ContainerRequestContext ctx = createRequestContext("/query");

        filter.filter(ctx);

        verify(ctx, never()).setSecurityContext(any(SecurityContext.class));
        verify(ctx, never()).getHeaderString(anyString());
    }

    @Test
    public void interimQueryReadPath_leftForJWTFilter() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        when(picSureWarInit.isGatewayOwnsQueryReadAuth()).thenReturn(false);
        ContainerRequestContext ctx = createRequestContext("/query/abc-123/result");

        filter.filter(ctx);

        verify(ctx, never()).setSecurityContext(any(SecurityContext.class));
    }

    @Test
    public void queryReadPathWithGatewayOwnsQueryReadAuth_rebuildsContextToo() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        when(picSureWarInit.isGatewayOwnsQueryReadAuth()).thenReturn(true);
        ContainerRequestContext ctx = createRequestContext("/query/abc-123/signed-url");
        when(ctx.getHeaderString("X-User-Id")).thenReturn("user-1");

        filter.filter(ctx);

        verify(ctx).setSecurityContext(any(SecurityContext.class));
    }

    @Test
    public void gatewayOwnedPathWithoutUserIdHeader_leavesSecurityContextUntouched() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        ContainerRequestContext ctx = createRequestContext("/query");
        when(ctx.getHeaderString("X-User-Id")).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).setSecurityContext(any(SecurityContext.class));
    }

    @Test
    public void gatewayOwnedPathWithBlankUserIdHeader_leavesSecurityContextUntouched() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        ContainerRequestContext ctx = createRequestContext("/query");
        when(ctx.getHeaderString("X-User-Id")).thenReturn("   ");

        filter.filter(ctx);

        verify(ctx, never()).setSecurityContext(any(SecurityContext.class));
    }

    @Test
    public void gatewayOwnedPath_rebuildsSecurityContextFromHeaders() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        ContainerRequestContext ctx = createRequestContext("/query");
        when(ctx.getHeaderString("X-User-Id")).thenReturn("user-42");
        when(ctx.getHeaderString("X-User-Subject")).thenReturn("sub-42");
        when(ctx.getHeaderString("X-User-Email")).thenReturn("user@example.com");
        when(ctx.getHeaderString("X-User-Roles")).thenReturn("PIC_SURE_ANY_QUERY");
        when(ctx.getHeaderString("X-User-Privileges")).thenReturn("PRIV_A, PRIV_B ,PRIV_A");

        filter.filter(ctx);

        verify(ctx).setProperty("username", "user-42");

        ArgumentCaptor<SecurityContext> captor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(ctx).setSecurityContext(captor.capture());
        SecurityContext secCtx = captor.getValue();

        AuthUser principal = (AuthUser) secCtx.getUserPrincipal();
        assertEquals("user-42", principal.getUserId());
        assertEquals("sub-42", principal.getSubject());
        assertEquals("user@example.com", principal.getEmail());
        assertEquals("PIC_SURE_ANY_QUERY", principal.getRoles());

        // isUserInRole() authorizes off privileges (AuthSecurityContext), not the raw roles string.
        assertTrue(secCtx.isUserInRole("PRIV_A"));
        assertTrue(secCtx.isUserInRole("PRIV_B"));
        assertFalse(secCtx.isUserInRole("PIC_SURE_ANY_QUERY"));
        assertFalse(secCtx.isUserInRole("PRIV_C"));
    }

    @Test
    public void gatewayOwnedPathWithBlankPrivilegesHeader_yieldsNoRoles() throws IOException {
        when(picSureWarInit.isGatewayOwnsAuth()).thenReturn(true);
        ContainerRequestContext ctx = createRequestContext("/query");
        when(ctx.getHeaderString("X-User-Id")).thenReturn("user-42");
        when(ctx.getHeaderString("X-User-Privileges")).thenReturn(null);

        filter.filter(ctx);

        ArgumentCaptor<SecurityContext> captor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(ctx).setSecurityContext(captor.capture());
        assertFalse(captor.getValue().isUserInRole("PRIV_A"));
    }
}
