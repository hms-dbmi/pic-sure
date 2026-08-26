package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.RasDbgapPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds BDC consents based on a user's RAS passport
 */
public class BdcConsentsBuilder {

    public static final String PUBLIC_STUDY_TYPE = "public";
    public static final String GENOMIC_DATA_TYPE_VALUE = "G";
    private final Logger log = LoggerFactory.getLogger(BdcConsentsBuilder.class);

    public static final String CONSENTS_KEY = "\\_consents\\";
    public static final String HARMONIZED_CONSENTS_KEY = "\\_harmonized_consent\\";
    public static final String TOPMED_CONSENTS_KEY = "\\_topmed_consents\\";
    private final Map<String, StudyMetaData> fenceMappingByConsent;

    private final Set<String> userConsentStrings;

    public BdcConsentsBuilder(Map<String, StudyMetaData> fenceMappingByConsent, Set<String> userConsentStrings) {
        this.fenceMappingByConsent = fenceMappingByConsent;
        this.userConsentStrings = userConsentStrings;
    }

    public Set<String> createConsents() {
        Set<String> result = new HashSet<>();

        userConsentStrings.forEach(consent -> {
            StudyMetaData studyMetaData = fenceMappingByConsent.get(consent);
            if (studyMetaData == null) {
                log.debug(consent + " not found in fence mapping");
                return;
            }
            // all user consents go in the consents list
            result.add(consent);
        });

        // Add all public studies to the consents list
        fenceMappingByConsent.forEach((key, value) -> {
            if (PUBLIC_STUDY_TYPE.equalsIgnoreCase(value.getStudyType())) {
                result.add(key);
            }
        });

        if (result.isEmpty()) {
            throw new IllegalStateException("No studies available for user");
        }

        return result;
    }
}
