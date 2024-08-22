package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
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
    private final SessionService sessionService;
    private final UserService userService;
    private final AccessRuleService accessRuleService;
    private final JWTUtil jwtUtil;

    public CustomLogoutHandler(SessionService sessionService, UserService userService, AccessRuleService accessRuleService, edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil jwtUtil) {
        this.sessionService = sessionService;
        this.userService = userService;
        this.accessRuleService = accessRuleService;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        String bearer = request.getHeader("Authorization");
        String token = bearer.substring(7);
        Claims payload = jwtUtil.parseToken(token).getPayload();
        String subject = payload.getSubject();

        if (StringUtils.isNotBlank(subject)) {
            logger.info("logout() Logging out User: {}", subject);
            this.sessionService.endSession(subject);
            this.userService.evictFromCache(subject);
            this.accessRuleService.evictFromCache(subject);
            this.userService.removeUserPassport(subject);
        }
    }
}

