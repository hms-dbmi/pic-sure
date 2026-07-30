package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionRequest;
import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.InvalidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.RefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.TokenIntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.ValidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.response.TokenRefreshResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TokenService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * <p>Token introspection endpoint called by an application to validate a user's token and permissions by request.</p>
 *
 * <p>Here, a registered application asks if the user behind a token is allowed to perform certain activities by showing this endpoint the
 * token and where the user wants to go.</p> <p>To accomplish this, this endpoint validates the incoming token, then checks if the user
 * behind the token is authorized to access the URLs they queried and send data along with them. The AuthorizationService class handles
 * authorization {@link AuthorizationService} at the access rule level, but this endpoint handles token validation and pre-check at the
 * privilege level.</p>
 */
@Tag(name = "Token Management")
@RestController
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
    public TokenIntrospectionResponse inspectToken(
        @Parameter(
            required = true, description = "The token to validate plus the request being authorized"
        ) @RequestBody IntrospectionRequest introspectionRequest, HttpServletRequest request
    ) {
        TokenIntrospectionResponse result = this.tokenService.inspectToken(introspectionRequest);

        AuditAttributes.putMetadata(request, "authz_result", result.introspection().active() ? "granted" : "denied");
        AuditAttributes.putMetadata(request, "authz_user_sub", String.valueOf(result.introspection().sub()));
        if (result.message() != null) {
            AuditAttributes.putMetadata(request, "authz_message", result.message());
        }
        AuditAttributes.putMetadata(request, "authz_token_refreshed", String.valueOf(result.introspection().tokenRefreshed()));

        TargetedRequest targetedRequest = introspectionRequest == null ? null : introspectionRequest.request();
        if (targetedRequest != null) {
            AuditAttributes.putMetadata(request, "target_service", targetedRequest.targetService());
            // v3 request bodies carry no resourceUUID; the resource being reached is the one the path names.
            AuditAttributes.putMetadata(request, "resource_id", AuditAttributes.resourceLabelForPath(targetedRequest.targetService()));
        }

        return result;
    }

    /**
     * A refusal is still a 400 carrying the reason, but as the uniform {@code {errorType, message, requestId}} body rather than a bare JSON
     * string -- which no client could tell apart from a successful string response.
     *
     * <p>{@code RefreshToken} is a sealed interface of exactly these two cases, so the cast below is total; the old {@code else} branch
     * answered 200 with an empty body for a state that cannot occur.
     */
    @Operation(description = "To refresh current user's token if the user is an active user")
    @AuditEvent(type = "ACCESS", action = "token.refresh")
    @GetMapping(path = "/refresh", produces = "application/json")
    public TokenRefreshResponse refreshToken(@RequestHeader("Authorization") String authorizationHeader, HttpServletRequest request) {
        RefreshToken refreshTokenResp = this.tokenService.refreshToken(authorizationHeader);

        if (refreshTokenResp instanceof InvalidRefreshToken invalidRefreshToken) {
            AuditAttributes.putMetadata(request, "token_refresh_result", "failure");
            AuditAttributes.putMetadata(request, "token_refresh_error", invalidRefreshToken.error());
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", invalidRefreshToken.error());
        }

        ValidRefreshToken validRefreshToken = (ValidRefreshToken) refreshTokenResp;
        AuditAttributes.putMetadata(request, "token_refresh_result", "success");
        return new TokenRefreshResponse(validRefreshToken.token(), validRefreshToken.expirationDate());
    }

}
