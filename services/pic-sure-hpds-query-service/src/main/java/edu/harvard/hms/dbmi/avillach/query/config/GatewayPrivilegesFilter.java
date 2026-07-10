package edu.harvard.hms.dbmi.avillach.query.config;

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
 * Rebuilds a {@code SecurityContext} from the gateway's {@code X-User-*} headers purely so {@link WebSecurityConfig}'s path rules can be
 * expressed declaratively (e.g. {@code /hpds/**} requiring an authenticated caller). The gateway is the sole PSAMA client and has already
 * authorized the request; this service performs no JWT validation of its own and trusts the headers (network ACLs prevent direct access).
 *
 * <p>Delegates to {@link GatewayUserResolver#resolve(HttpServletRequest)}: when {@code X-User-Id} is absent, {@code resolve} yields
 * {@link java.util.Optional#empty()} and this filter leaves the {@code SecurityContext} untouched -- the request stays anonymous.
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
