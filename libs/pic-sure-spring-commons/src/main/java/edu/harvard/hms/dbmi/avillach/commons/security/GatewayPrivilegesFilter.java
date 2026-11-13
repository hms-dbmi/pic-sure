package edu.harvard.hms.dbmi.avillach.commons.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUser;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Maps the gateway-set {@code X-User-Privileges} header into Spring {@link SimpleGrantedAuthority}s so a service's path rules and
 * {@code @PreAuthorize} guards can see them. The gateway is the only entity that talks to PSAMA; services trust the header (network ACLs
 * prevent direct access) and perform no JWT validation of their own.
 *
 * <p>Delegates to {@link GatewayUserResolver#resolve(HttpServletRequest)}: when {@code X-User-Id} is absent, {@code resolve} yields
 * {@link java.util.Optional#empty()} and this filter leaves the {@code SecurityContext} untouched -- the request stays anonymous.
 * Open-access callers carry a user id but no privileges, so they hold no authority.
 */
public class GatewayPrivilegesFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        GatewayUserResolver.resolve(request).ifPresent(this::authenticate);
        filterChain.doFilter(request, response);
    }

    private void authenticate(GatewayUser user) {
        List<SimpleGrantedAuthority> authorities = user.getPrivileges().stream().map(SimpleGrantedAuthority::new).toList();
        var authentication = new UsernamePasswordAuthenticationToken(user.getUserId(), "N/A", authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
