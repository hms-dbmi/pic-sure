package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.response.OpenAccessValidationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.OpenAccessValidationResponse.Denial;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@Tag(name = "Open access", description = "Open-access sessions for anonymous browsers, and request validation called by the gateway")
@Controller
@RequestMapping(value = "/open")
public class OpenAccessController {

    static final String RESPONSE_VERSION = "responseVersion";

    private final AuthorizationService authorizationService;
    private final boolean openIdpProviderIsEnabled;

    @Autowired
    public OpenAccessController(
        AuthorizationService authorizationService, @Value("${open.idp.provider.is.enabled}") boolean openIdpProviderIsEnabled
    ) {
        this.authorizationService = authorizationService;
        this.openIdpProviderIsEnabled = openIdpProviderIsEnabled;
    }

    @Operation(summary = "Validate an open-access request against the access rules")
    @ApiResponse(
        responseCode = "200",
        description = "Whether the open access request is permitted: a bare boolean, or an OpenAccessValidationResponse when the request "
            + "body carries \"responseVersion\": 2",
        content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {Boolean.class, OpenAccessValidationResponse.class}))
    )
    @AuditEvent(type = "ACCESS", action = "open.validate")
    @RequestMapping(value = "/validate", produces = "application/json")
    public ResponseEntity<?> validate(
        @Parameter(
            required = true, description = "A JSON object that at least includes a user and the token for validation"
        ) @RequestBody Map<String, Object> inputMap, HttpServletRequest request
    ) {
        // A gateway that predates the object response reads anything but a bare boolean as a denial, so the object goes only to a
        // caller that asks for it. Either service can then be deployed or rolled back first.
        boolean objectResponse = inputMap != null && inputMap.get(RESPONSE_VERSION) instanceof Number version && version.doubleValue() == 2;

        if (!openIdpProviderIsEnabled) {
            return ResponseEntity.ok(objectResponse ? OpenAccessValidationResponse.denied(Denial.RULES) : false);
        }

        OpenAccessValidationResponse validation = authorizationService.validateOpenAccessRequest(inputMap);
        boolean isValid = validation.valid();
        AuditAttributes.putMetadata(request, "validation_result", String.valueOf(isValid));

        Object requestObj = inputMap.get("request");
        if (requestObj instanceof Map<?, ?> requestDetails) {
            Object targetService = requestDetails.get("Target Service");
            if (targetService != null) {
                AuditAttributes.putMetadata(request, "target_service", targetService.toString());
            }
            Object query = requestDetails.get("query");
            if (query instanceof Map<?, ?> queryMap) {
                Object resourceUUID = queryMap.get("resourceUUID");
                if (resourceUUID != null) {
                    AuditAttributes.putMetadata(request, "resource_id", resourceUUID.toString());
                }
            }
        }

        return ResponseEntity.ok(objectResponse ? validation : isValid);
    }

}
