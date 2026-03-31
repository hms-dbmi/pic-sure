package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@Controller
@RequestMapping(value = "/open")
public class OpenAccessController {

    private final AuthorizationService authorizationService;
    private final boolean openIdpProviderIsEnabled;

    @Autowired
    public OpenAccessController(AuthorizationService authorizationService, @Value("${open.idp.provider.is.enabled}") boolean openIdpProviderIsEnabled) {
        this.authorizationService = authorizationService;
        this.openIdpProviderIsEnabled = openIdpProviderIsEnabled;
    }

    @AuditEvent(type = "ACCESS", action = "open.validate")
    @RequestMapping(value = "/validate", produces = "application/json")
    public ResponseEntity<?> validate(@Parameter(required = true, description = "A JSON object that at least" +
            " include a user the token for validation")
                         @RequestBody Map<String, Object> inputMap, HttpServletRequest request) {
        if (!openIdpProviderIsEnabled) {
            return ResponseEntity.ok(false);
        }

        boolean isValid = authorizationService.openAccessRequestIsValid(inputMap);
        AuditAttributes.putMetadata(request, "validation_result", String.valueOf(isValid));
        return ResponseEntity.ok(isValid);
    }

}
