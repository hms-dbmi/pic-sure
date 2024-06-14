package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.OktaOAuthAuthenticationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Okta Authentication Controller", description = "The authentication endpoint for Okta.")
@Controller
@RequestMapping("/okta")
public class OktaAuthenticationController {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final OktaOAuthAuthenticationService oktaOAuthAuthenticationService;
    private final String idp_provider;

    @Autowired
    public OktaAuthenticationController(OktaOAuthAuthenticationService oktaOAuthAuthenticationService,
                                        @Value("${application.idp.provider}") String idp_provider) {
        this.oktaOAuthAuthenticationService = oktaOAuthAuthenticationService;
        this.idp_provider = idp_provider;
    }

    @PostMapping("/authentication")
    public ResponseEntity<?> authenticate(@RequestBody Map<String, String> authRequest, HttpServletRequest request) {
        logger.info("OKTA LOGIN ATTEMPT ___ {} ___", authRequest.get("code"));
        String host = request.getServerName();

        String idp_provider = this.idp_provider;
        if (idp_provider.equalsIgnoreCase("okta")) {
            HashMap<String, String> authenticate = oktaOAuthAuthenticationService.authenticate(host, authRequest);
            if (authenticate != null) {
                return PICSUREResponse.success(authenticate);
            } else {
                return PICSUREResponse.unauthorizedError("User not authenticated.");
            }
        } else {
            return PICSUREResponse.error("IDP provider not configured correctly.x");
        }
    }

}
