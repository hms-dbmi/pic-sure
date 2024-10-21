package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.CacheEvictionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
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

@Component
public class CustomLogoutHandler implements LogoutHandler {

    private final Logger logger = LoggerFactory.getLogger(CustomLogoutHandler.class);
    private final UserService userService;
    private final CacheEvictionService cacheEvictionService;
    private final JWTUtil jwtUtil;

    public CustomLogoutHandler(UserService userService, CacheEvictionService cacheEvictionService, edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil jwtUtil) {
        this.userService = userService;
        this.cacheEvictionService = cacheEvictionService;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        String bearer = request.getHeader("Authorization");
        if (!bearer.startsWith("Bearer ")) {
            return;
        }

        String token = bearer.substring(7);
        if (StringUtils.isBlank(token)) {
            return;
        }

        Claims payload = jwtUtil.parseToken(token).getPayload();
        String subject = payload.getSubject();

        if (StringUtils.isNotBlank(subject)) {
            logger.info("logout() Logging out User: {}", subject);
            this.cacheEvictionService.evictCache(subject);
            this.userService.removeUserPassport(subject);
        }
    }
}

