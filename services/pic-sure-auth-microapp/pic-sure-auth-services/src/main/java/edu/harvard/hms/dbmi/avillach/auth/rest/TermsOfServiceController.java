package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Optional;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for creating and updating terms of service entities. Records when a user accepts a term of service.</p>
 */
@Tag(name = "Terms of Service Management")
@Controller
@RequestMapping("/tos")
public class TermsOfServiceController {

    private final Logger logger = LoggerFactory.getLogger(TermsOfServiceController.class);
    private final TOSService tosService;
    private final UserService userService;

    @Autowired
    public TermsOfServiceController(TOSService tosService, UserService userService) {
        this.tosService = tosService;
        this.userService = userService;
    }

    @Operation(description = "GET the latest Terms of Service")
    @GetMapping(path = "/latest", produces = "text/html")
    public ResponseEntity<String> getLatestTermsOfService(){
        logger.info("Getting latest Terms of Service");
        return PICSUREResponse.success(tosService.getLatest());
    }

    @Operation(description = "Update the Terms of Service html body")
    @RolesAllowed({AuthNaming.AuthRoleNaming.ADMIN, SUPER_ADMIN})
    @PostMapping(path = "/update", consumes = "text/html", produces = "application/json")
    public ResponseEntity<?> updateTermsOfService(
            @Parameter(required = true, description = "A html page for updating") String html){
        Optional<TermsOfService> termsOfService = tosService.updateTermsOfService(html);
        if (termsOfService.isEmpty()){
            return PICSUREResponse.success();
        }
        return PICSUREResponse.success(termsOfService.get());
    }

    @Operation(description = "GET if current user has acceptted his TOS or not")
    @GetMapping(produces = "text/plain")
    public ResponseEntity<Boolean> hasUserAcceptedTOS(){
        SecurityContext context = SecurityContextHolder.getContext();
        String userSubject = context.getAuthentication().getName();
        logger.info("hasUserAcceptedTOS for user {}", userSubject);
        return PICSUREResponse.success(tosService.hasUserAcceptedLatest(userSubject));
    }

    @Operation(description = "Endpoint for current user to accept his terms of service")
    @PostMapping(path = "/accept", produces = "application/json")
    public ResponseEntity<?> acceptTermsOfService(){
        SecurityContext context = SecurityContextHolder.getContext();
        String userSubject = context.getAuthentication().getName();
        User user = tosService.acceptTermsOfService(userSubject);
        userService.updateUser(List.of(user));
        return PICSUREResponse.success();
    }

}
