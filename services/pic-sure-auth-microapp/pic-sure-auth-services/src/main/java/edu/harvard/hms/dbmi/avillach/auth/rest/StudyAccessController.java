package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.StudyAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;


import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoint for service handling business logic for adding all the auth
 * rules for a given study</p>
 * <p>Note: Only users with the super admin role can access this endpoint.</p>
 */
@Controller
@RequestMapping("/studyAccess")
public class StudyAccessController {

    private final StudyAccessService studyAccessService;

    @Autowired
    public StudyAccessController(StudyAccessService studyAccessService) {
        this.studyAccessService = studyAccessService;
    }

    @Operation(description = "POST a single study and it creates the role, privs, and rules for it, requires SUPER_ADMIN role")
    @Transactional
    @RolesAllowed({SUPER_ADMIN, ADMIN})
    @PostMapping(consumes = "application/json")
    public ResponseEntity<String> addStudyAccess(@Parameter(description = "The Study Identifier of the new study from the metadata.json")
                                            @RequestBody String studyIdentifier) {
        String status = studyAccessService.addStudyAccess(studyIdentifier);
        if (status.contains("Error:")) {
            return PICSUREResponse.error(status);
        } else {
            return PICSUREResponse.success(status);
        }
    }
}
