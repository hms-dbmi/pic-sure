package edu.harvard.hms.dbmi.avillach.auth.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.BioDataCatalyst;
import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FenceMappingUtility {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private Map<String, StudyMetaData> fenceMappingByConsent;
    private Map<String, StudyMetaData> fenceMappingByAuthZ;
    private final String templatePath;
    private ObjectMapper objectMapper;

    @Autowired
    public FenceMappingUtility(@Value("${application.template.path}") String templatePath) {
        this.templatePath = templatePath;
    }

    @PostConstruct
    public void init() {
        if (StringUtils.isNotBlank(this.templatePath) && this.templatePath.endsWith(File.separator)) {
            // Check if file exists
            File file = new File(this.templatePath + "fence_mapping.json");
            if (!file.exists()) {
                logger.error("FenceMappingUtility: fence_mapping.json not found in {}", this.templatePath);
            } else {
                logger.info("FenceMappingUtility: fence_mapping.json found in {}", this.templatePath);
                objectMapper = new ObjectMapper();
                initializeFENCEMappings();
            }

        } else {
            logger.error("FenceMappingUtility: templatePath is not set or does not end with a file separator");
        }
    }

    private void initializeFENCEMappings() {
        if (fenceMappingByConsent == null || fenceMappingByAuthZ == null) {
            List<StudyMetaData> studies = loadBioDataCatalystFenceMappingData();
            ConcurrentHashMap<String, StudyMetaData> tempFenceMappingByConsent = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, StudyMetaData> tempFenceMappingByAuthZ = new ConcurrentHashMap<>();

            studies.parallelStream().forEach(study -> {
                String consentVal = (study.getConsentGroupCode() != null && !study.getConsentGroupCode().isEmpty()) ?
                        study.getStudyIdentifier() + "." + study.getConsentGroupCode() :
                        study.getStudyIdentifier();
                tempFenceMappingByConsent.put(consentVal, study);
                tempFenceMappingByAuthZ.put(study.getAuthZ().replace("\\/", "/"), study);
            });

            fenceMappingByConsent = Collections.unmodifiableMap(tempFenceMappingByConsent);
            fenceMappingByAuthZ = Collections.unmodifiableMap(tempFenceMappingByAuthZ);
        }
    }

    public Map<String, StudyMetaData> getFENCEMapping() {
        return fenceMappingByConsent;
    }

    public Map<String, StudyMetaData> getFenceMappingByAuthZ() {
        return fenceMappingByAuthZ;
    }

    private List<StudyMetaData> loadBioDataCatalystFenceMappingData() {
        BioDataCatalyst fenceMapping;
        List<StudyMetaData> studies;
        try {
            logger.debug("getFENCEMapping: loading FENCE mapping from {}", templatePath);
            fenceMapping = objectMapper.readValue(
                    new File(String.join(File.separator,
                            new String[]{templatePath, "fence_mapping.json"}))
                    , BioDataCatalyst.class);

            studies = fenceMapping.getStudyMetaData();
            logger.debug("getFENCEMapping: found FENCE mapping with {} entries", studies.size());
        } catch (Exception e) {
            logger.error("loadFenceMappingData: Non-fatal error parsing fence_mapping.json: {}", templatePath, e);
            return new ArrayList<>();
        }
        return studies;
    }

}
