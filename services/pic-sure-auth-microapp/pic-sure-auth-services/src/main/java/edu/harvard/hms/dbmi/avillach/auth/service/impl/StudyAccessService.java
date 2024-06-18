package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.FENCEAuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import io.swagger.v3.oas.annotations.Parameter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;

@Service
public class StudyAccessService {
    private final FenceMappingUtility fenceMappingUtility;
    Logger logger = LoggerFactory.getLogger(StudyAccessService.class);

    public static final String MANUAL = "MANUAL_";
    public static final String STUDY_IDENTIFIER = "study_identifier";
    public static final String CONSENT_GROUP_CODE = "consent_group_code";

    private final FENCEAuthenticationService fenceAuthenticationService;

    @Autowired
    public StudyAccessService(FENCEAuthenticationService fenceAuthenticationService, FenceMappingUtility fenceMappingUtility) {
        this.fenceAuthenticationService = fenceAuthenticationService;
        this.fenceMappingUtility = fenceMappingUtility;
    }

    public String addStudyAccess(@Parameter(description="The Study Identifier of the new study from the metadata.json")
                                                @RequestBody String studyIdentifier) {
        if (StringUtils.isBlank(studyIdentifier)) {
            return "Error: Study identifier cannot be blank";
        }

        StudyMetaData fenceMappingForStudy;
        try {
            Map<String, StudyMetaData> fenceMapping = fenceMappingUtility.getFENCEMapping();
            if (fenceMapping == null) {
                throw new Exception("Fence mapping is null");
            }
            fenceMappingForStudy = fenceMapping.get(studyIdentifier);
        } catch(Exception ex) {
            logger.error(ex.toString());
            logger.error("addStudyAccess - Error occurred while fetching FENCE mapping");
            return "Error: occurred while fetching FENCE mapping";
        }

        if (fenceMappingForStudy == null) {
            logger.error("addStudyAccess - Could not find study: {} in FENCE mapping", studyIdentifier);
            return "Error: Could not find study with the provided identifier";
        }

        String projectId = fenceMappingForStudy.getStudyIdentifier();
        String consentCode = fenceMappingForStudy.getConsentGroupCode();
        String newRoleName = StringUtils.isNotBlank(consentCode) ? MANUAL+projectId+"_"+consentCode : MANUAL+projectId;

        logger.debug("addStudyAccess - New manual PSAMA role name: {}", newRoleName);
        if (fenceAuthenticationService.upsertRole(null, newRoleName, MANUAL + " role "+newRoleName)) {
            logger.info("addStudyAccess - Updated user role. Now it includes `{}`", newRoleName);
            return "Role '" + newRoleName + "' successfully created";
        } else {
            logger.error("addStudyAccess - could not add {} role to to database", newRoleName);
            return "Error: Could not add role '" + newRoleName + "' to database";
        }
    }
}
