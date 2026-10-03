package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.model.request.OpenAccessValidationRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@Tag(name = "Open access", description = "Validation of open-access requests, called by the gateway.")
@Controller
@RequestMapping(value = "/open")
public class OpenAccessController {

    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final boolean openIdpProviderIsEnabled;

    @Autowired
    public OpenAccessController(
        AuthorizationService authorizationService, ObjectMapper objectMapper,
        @Value("${open.idp.provider.is.enabled}") boolean openIdpProviderIsEnabled
    ) {
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
        this.openIdpProviderIsEnabled = openIdpProviderIsEnabled;
    }

    @Operation(summary = "Validate an open-access request against the access rules")
    @ApiResponse(
        responseCode = "200", description = "Whether the open access request is permitted.",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(type = "boolean", description = "True when the open access rules permit the request, false otherwise.")
        )
    )
    @AuditEvent(type = "ACCESS", action = "open.validate")
    @PostMapping(value = "/validate", produces = "application/json")
    public ResponseEntity<Boolean> validate(
        @Parameter(
            required = true, description = "The open access request to validate: its target service, and the API key when enforced."
        ) @RequestBody OpenAccessValidationRequest validation, HttpServletRequest request
    ) {
        if (!openIdpProviderIsEnabled) {
            return ResponseEntity.ok(false);
        }

        Map<String, Object> inputMap = validation.toMap(objectMapper);
        boolean isValid = authorizationService.openAccessRequestIsValid(inputMap);
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

        return ResponseEntity.ok(isValid);
    }

}
