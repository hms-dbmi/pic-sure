package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.InvalidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.RefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.ValidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TokenService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>Token introspection endpoint called by an application to validate a user's token and permissions by request.</p>
 *
 * <p>Here, a registered application asks if the user behind a token is allowed to perform certain activities by
 * showing this endpoint the token and where the user wants to go.</p>
 * <p>To accomplish this, this endpoint validates the incoming token, then checks if the user behind the token
 * is authorized to access the URLs they queried and send data along with them. The AuthorizationService class handles authorization
 * {@link AuthorizationService} at the access rule level, but this endpoint handles token validation and pre-check at the privilege level.</p>
 */
@Tag(name = "Token Management")
@Controller
@RequestMapping("/token")
public class TokenController {

    private final TokenService tokenService;

    @Autowired
    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Operation(description = "Token introspection endpoint for user to retrieve a valid token")
    @AuditEvent(type = "ACCESS", action = "token.introspect")
    @PostMapping(path = "/inspect", produces = "application/json")
    public ResponseEntity<Map<String, Object>> inspectToken(
            @Parameter(required = true, description = "A JSON object that at least" +
                    " include a user the token for validation")
            @RequestBody Map<String, Object> inputMap, HttpServletRequest request) {
        Map<String, Object> resultMap = this.tokenService.inspectToken(inputMap);

        boolean active = Boolean.TRUE.equals(resultMap.getOrDefault("active", false));
        AuditAttributes.putMetadata(request, "authz_result", active ? "granted" : "denied");
        AuditAttributes.putMetadata(request, "authz_user_sub", String.valueOf(resultMap.getOrDefault("sub", "")));
        if (resultMap.containsKey("message")) {
            AuditAttributes.putMetadata(request, "authz_message", String.valueOf(resultMap.get("message")));
        }
        if (resultMap.containsKey("tokenRefreshed")) {
            AuditAttributes.putMetadata(request, "authz_token_refreshed", String.valueOf(resultMap.get("tokenRefreshed")));
        }

        return PICSUREResponse.success(resultMap);
    }

    @Operation(description = "To refresh current user's token if the user is an active user")
    @AuditEvent(type = "ACCESS", action = "token.refresh")
    @GetMapping(path = "/refresh", produces = "application/json")
    public ResponseEntity<?> refreshToken(@RequestHeader("Authorization") String authorizationHeader, HttpServletRequest request) {
        RefreshToken refreshTokenResp = this.tokenService.refreshToken(authorizationHeader);

        if (refreshTokenResp instanceof InvalidRefreshToken invalidRefreshToken) {
            AuditAttributes.putMetadata(request, "token_refresh_result", "failure");
            AuditAttributes.putMetadata(request, "token_refresh_error", invalidRefreshToken.error());
            return PICSUREResponse.protocolError(invalidRefreshToken.error());
        }

        if (refreshTokenResp instanceof ValidRefreshToken validRefreshToken) {
            AuditAttributes.putMetadata(request, "token_refresh_result", "success");
            return PICSUREResponse.success(Map.of("token", validRefreshToken.token(), "expirationDate", validRefreshToken.expirationDate()));
        }

        return PICSUREResponse.success();
    }

}
