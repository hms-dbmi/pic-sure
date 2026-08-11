package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApplicationRefreshTokenResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApplicationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for registering and administering applications. <br> Note: Only users with the super admin role can access this endpoint.</p>
 */
@Tag(name = "Application Management")
@RestController
@RequestMapping(value = "/application")
public class ApplicationController {

    private final ApplicationService applicationService;

    @Autowired
    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Operation(description = "GET information of one Application with the UUID, no role restrictions")
    @AuditEvent(type = "OTHER", action = "application.read")
    @GetMapping(value = "/{applicationId}")
    public Application getApplicationById(
        @Parameter(required = true, description = "The UUID of the application to fetch information about") @PathVariable(
            "applicationId"
        ) String applicationId
    ) {
        Optional<Application> entityById = applicationService.getApplicationByID(applicationId);

        return entityById.orElseThrow(
            () -> new PicsureException(
                HttpStatus.BAD_REQUEST, "bad_request", "Application is not found by given Application ID: " + applicationId
            )
        );
    }

    @Operation(description = "GET a list of existing Applications, no role restrictions")
    @AuditEvent(type = "OTHER", action = "application.list")
    @GetMapping
    public List<Application> getApplicationAll() {
        return applicationService.getAllApplications();
    }

    @Operation(description = "POST a list of Applications, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(consumes = "application/json", produces = "application/json")
    public List<Application> addApplication(
        @Parameter(required = true, description = "A list of AccessRule in JSON format") @RequestBody List<Application> applications,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(applications.size()));
        return applicationService.addNewApplications(applications);
    }

    @Operation(description = "Update a list of Applications, will only update the fields listed, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.modify")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(consumes = "application/json", produces = "application/json")
    public List<Application> updateApplication(
        @Parameter(
            required = true, description = "A list of AccessRule with fields to be updated in JSON format"
        ) @RequestBody List<Application> applications, HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_count", String.valueOf(applications.size()));
        return applicationService.updateApplications(applications);
    }

    @Operation(description = "Refresh a token of an application by application Id, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.token_refresh")
    @RolesAllowed({SUPER_ADMIN})
    @GetMapping(value = "/refreshToken/{applicationId}")
    public ApplicationRefreshTokenResponse refreshApplicationToken(
        @Parameter(required = true, description = "A valid application Id") @PathVariable("applicationId") String applicationId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_id", applicationId);
        return new ApplicationRefreshTokenResponse(applicationService.refreshApplicationToken(applicationId));
    }

    /**
     * The {@code IllegalArgumentException} the service raises for an unknown or still-referenced application already maps to a 400 in
     * {@link edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler}, message intact, so there is nothing left to catch here.
     */
    @Operation(description = "DELETE an Application by Id only if the application is not associated by others, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "application.delete")
    @RolesAllowed({SUPER_ADMIN})
    @DeleteMapping(value = "/{applicationId}")
    public List<Application> removeById(
        @Parameter(required = true, description = "A valid accessRule Id") @PathVariable("applicationId") final String applicationId,
        HttpServletRequest request
    ) {
        AuditAttributes.putMetadata(request, "app_id", applicationId);
        return applicationService.deleteApplicationById(applicationId);
    }

}
