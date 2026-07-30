package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.request.StudyAccessRequest;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.StudyAccessService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for adding all the auth rules for a given study</p> <p>Note: Only users with the super
 * admin role can access this endpoint.</p>
 */
@RestController
@RequestMapping("/studyAccess")
public class StudyAccessController {

    private final StudyAccessService studyAccessService;

    @Autowired
    public StudyAccessController(StudyAccessService studyAccessService) {
        this.studyAccessService = studyAccessService;
    }

    /**
     * The body is now {@link StudyAccessRequest} rather than a raw String; see that record for why the break is taken here.
     *
     * <p>The service signals failure by returning an {@code "Error:"}-prefixed string, which becomes a 500 carrying that text as
     * {@code message} -- the same status the envelope produced. Turning those cases into honest 4xx statuses needs the service to
     * distinguish them, which is a follow-up rather than something to guess at here.
     */
    @Operation(description = "POST a single study and it creates the role, privs, and rules for it, requires SUPER_ADMIN role")
    @AuditEvent(type = "ADMIN", action = "study_access.create")
    @Transactional
    @RolesAllowed({SUPER_ADMIN, ADMIN})
    @PostMapping(consumes = "application/json")
    public String addStudyAccess(
        @Parameter(
            description = "The Study Identifier of the new study from the metadata.json"
        ) @RequestBody StudyAccessRequest studyAccessRequest, HttpServletRequest request
    ) {
        String studyIdentifier = studyAccessRequest == null ? null : studyAccessRequest.studyIdentifier();
        AuditAttributes.putMetadata(request, "study_identifier", studyIdentifier);
        String status = studyAccessService.addStudyAccess(studyIdentifier);
        if (status.contains("Error:")) {
            throw new PicsureException(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", status);
        }
        return status;
    }
}
