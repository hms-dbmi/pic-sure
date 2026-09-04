package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.CacheEvictionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CustomLogoutHandler implements LogoutHandler {

    private final Logger logger = LoggerFactory.getLogger(CustomLogoutHandler.class);
    private final UserService userService;
    private final CacheEvictionService cacheEvictionService;
    private final JWTUtil jwtUtil;
    private final SessionService sessionService;

    public CustomLogoutHandler(
        UserService userService, CacheEvictionService cacheEvictionService, JWTUtil jwtUtil, SessionService sessionService
    ) {
        this.userService = userService;
        this.cacheEvictionService = cacheEvictionService;
        this.jwtUtil = jwtUtil;
        this.sessionService = sessionService;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        String bearer = request.getHeader("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer ")) {
            return;
        }

        String token = bearer.substring(7);
        if (StringUtils.isBlank(token)) {
            return;
        }

        // Expiration is deliberately ignored: a token that idled out still names the session to end, and the
        // sessions it belongs to can outlive it (application.max.session.length is far longer than the token TTL).
        Optional<Claims> payload = jwtUtil.parseTokenAllowingExpiration(token);
        if (payload.isEmpty()) {
            logger.warn("logout() The token presented for logout could not be verified; no session to end.");
            return;
        }

        String subject = payload.get().getSubject();
        if (StringUtils.isBlank(subject)) {
            return;
        }

        // /logout is permit-listed and LogoutFilter runs ahead of JWTFilter, so this is the only place the logout
        // request's token is checked. Without this, anyone holding a token from a session the user already left
        // could end the session they are using now.
        if (sessionService.isTokenIssuedBeforeCurrentSession(subject, payload.get().getIssuedAt())) {
            logger.warn("logout() Ignoring a logout presented with a token from an ended session for subject: {}", subject);
            return;
        }

        logger.info("logout() Logging out User: {}", subject);
        this.cacheEvictionService.evictCache(subject);
        this.userService.removeUserPassport(subject);

        // Populate AuditAttributes for the AuditLoggingFilter to include in its event
        AuditAttributes.putMetadata(request, "user_subject", subject);
    }
}
