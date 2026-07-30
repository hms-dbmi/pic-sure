package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.OpenAccessValidationRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ValidationResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unauthenticated authorization path: the gateway asks whether a request with no user behind it passes the open-access rule set.
 */
@RestController
@RequestMapping(value = "/open")
public class OpenAccessController {

    private final AuthorizationService authorizationService;
    private final boolean openIdpProviderIsEnabled;

    @Autowired
    public OpenAccessController(
        AuthorizationService authorizationService, @Value("${open.idp.provider.is.enabled}") boolean openIdpProviderIsEnabled
    ) {
        this.authorizationService = authorizationService;
        this.openIdpProviderIsEnabled = openIdpProviderIsEnabled;
    }

    /**
     * Answers {@code {"valid": true|false}}. This replaced a bare JSON boolean and is a lockstep change with the gateway's
     * {@code PsamaClient#validateOpenAccess}, which now reads either shape -- so the gateway must be deployed first. See
     * {@link ValidationResponse}.
     */
    @Operation(description = "Validates an open-access request against the open-access rule set")
    @AuditEvent(type = "ACCESS", action = "open.validate")
    @RequestMapping(value = "/validate", produces = "application/json")
    public ValidationResponse validate(
        @Parameter(
            required = true, description = "The request being authorized, plus the caller's open-access marker"
        ) @RequestBody OpenAccessValidationRequest validationRequest, HttpServletRequest request
    ) {
        if (!openIdpProviderIsEnabled) {
            return new ValidationResponse(false);
        }

        boolean isValid = authorizationService.openAccessRequestIsValid(validationRequest);
        AuditAttributes.putMetadata(request, "validation_result", String.valueOf(isValid));

        TargetedRequest targetedRequest = validationRequest == null ? null : validationRequest.request();
        if (targetedRequest != null && targetedRequest.targetService() != null) {
            AuditAttributes.putMetadata(request, "target_service", targetedRequest.targetService());
            // v3 request bodies carry no resourceUUID; the resource being reached is the one the path names.
            AuditAttributes.putMetadata(request, "resource_id", AuditAttributes.resourceLabelForPath(targetedRequest.targetService()));
        }

        return new ValidationResponse(isValid);
    }

}
