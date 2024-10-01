package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import io.swagger.v3.oas.annotations.Parameter;
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

    @RequestMapping(value = "/validate", produces = "application/json")
    public ResponseEntity<?> validate(@Parameter(required = true, description = "A JSON object that at least" +
            " include a user the token for validation")
                         @RequestBody Map<String, Object> inputMap) {
        if (!openIdpProviderIsEnabled) {
            return ResponseEntity.ok(false);
        }

        boolean isValid = authorizationService.openAccessRequestIsValid(inputMap);
        return ResponseEntity.ok(isValid);
    }

}
