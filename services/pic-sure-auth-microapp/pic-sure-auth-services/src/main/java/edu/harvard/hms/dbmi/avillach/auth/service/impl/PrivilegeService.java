package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.model.fenceMapping.StudyMetaData;
import edu.harvard.hms.dbmi.avillach.auth.repository.PrivilegeRepository;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;

@Service
public class PrivilegeService {

    private final static Logger logger = LoggerFactory.getLogger(PrivilegeService.class.getName());

    private final PrivilegeRepository privilegeRepository;
    private final ApplicationService applicationService;
    private final AccessRuleService accessRuleService;

    private Application picSureApp;
    private final String variantAnnotationColumns;

    private final String fence_harmonized_consent_group_concept_path;
    private final String fence_parent_consent_group_concept_path;
    private final String fence_topmed_consent_group_concept_path;

    private String fence_harmonized_concept_path;
    private static final String topmedAccessionField = "\\\\_Topmed Study Accession with Subject ID\\\\";

    @Autowired
    protected PrivilegeService(PrivilegeRepository privilegeRepository,
                               ApplicationService applicationService,
                               AccessRuleService accessRuleService,
                               @Value("${fence.variant.annotation.columns}") String variantAnnotationColumns,
                               @Value("${fence.harmonized.consent.group.concept.path}") String fenceHarmonizedConsentGroupConceptPath,
                               @Value("${fence.parent.consent.group.concept.path}") String fenceParentConceptPath,
                               @Value("${fence.topmed.consent.group.concept.path}") String fenceTopmedConceptPath,
                               @Value("${fence.consent.group.concept.path}") String fenceHarmonizedConceptPath) {
        this.privilegeRepository = privilegeRepository;
        this.applicationService = applicationService;
        this.accessRuleService = accessRuleService;
        this.variantAnnotationColumns = variantAnnotationColumns;
        this.fence_harmonized_consent_group_concept_path = fenceHarmonizedConsentGroupConceptPath;
        this.fence_parent_consent_group_concept_path = fenceParentConceptPath;
        this.fence_topmed_consent_group_concept_path = fenceTopmedConceptPath;
        this.fence_harmonized_concept_path = fenceHarmonizedConceptPath;
    }

    @PostConstruct
    private void init() {
        picSureApp = applicationService.getApplicationByName("PICSURE");
        logger.info("variantAnnotationColumns: {}", variantAnnotationColumns);
    }

    @Transactional
    @EventListener(ApplicationContextEvent.class)
    protected void onContextRefreshedEvent() {
        updateAllPrivilegesOnStartup();
    }

    @Transactional
    public List<Privilege> deletePrivilegeByPrivilegeId(String privilegeId) {
        Optional<Privilege> privilege = this.privilegeRepository.findById(UUID.fromString(privilegeId));

        // Get security context with spring security context
        SecurityContext securityContext = SecurityContextHolder.getContext();
        // Get the principal name from the security context
        String principalName = securityContext.getAuthentication().getName();

        if (ADMIN.equals(privilege.get().getName())) {
            logger.info("User: {}, is trying to remove the system admin privilege: " + ADMIN, principalName);
            throw new RuntimeException("System Admin privilege cannot be removed - uuid: " + privilege.get().getUuid().toString()
                    + ", name: " + privilege.get().getName());
        }

        this.privilegeRepository.deleteById(UUID.fromString(privilegeId));
        return this.getPrivilegesAll();
    }

    public List<Privilege> updatePrivileges(List<Privilege> privileges) {
        this.privilegeRepository.saveAll(privileges);
        return this.getPrivilegesAll();
    }

    public List<Privilege> addPrivileges(List<Privilege> privileges) {
        return this.privilegeRepository.saveAll(privileges);
    }

    public List<Privilege> getPrivilegesAll() {
        return this.privilegeRepository.findAll();
    }

    public Privilege getPrivilegeById(String privilegeId) {
        return this.privilegeRepository.findById(UUID.fromString(privilegeId)).orElse(null);
    }

    public Privilege findByName(String privilegeName) {
        return this.privilegeRepository.findByName(privilegeName);
    }

    public Privilege save(Privilege privilege) {
        return this.privilegeRepository.save(privilege);
    }

    public Set<Privilege> addPrivileges(Role r, Map<String, StudyMetaData> fenceMapping) {
        String roleName = r.getName();
        logger.debug("addPrivilege() starting, adding privilege(s) to role {}", roleName);

        //each project can have up to three privileges: Parent  |  Harmonized  | Topmed
        //harmonized has 2 ARs for parent + harmonized and harmonized only
        //Topmed has up to three ARs for topmed / topmed + parent / topmed + harmonized
        Set<Privilege> privs = r.getPrivileges();
        if (privs == null) {
            privs = new HashSet<Privilege>();
        }

        //e.g. MANAGED_phs0000xx_c2 or MANAGED_tutorial-biolinc_camp
        String project_name = extractProject(roleName);
        if (project_name.isEmpty()) {
            logger.warn("addPrivileges() role name: {} returned an empty project name", roleName);
        }
        String consent_group = extractConsentGroup(roleName);
        if (!consent_group.isEmpty()) {
            logger.warn("addPrivileges() role name: {} returned an empty consent group", roleName);
        }
        logger.debug("addPrivileges() project name: {} consent group: {}", project_name, consent_group);

        // Look up the metadata by consent group.
        StudyMetaData projectMetadata = getStudyMappingForProjectAndConsent(project_name, consent_group, fenceMapping);

        if (projectMetadata == null) {
            //no privileges means no access to this project.  just return existing set of privs.
            logger.warn("No metadata available for project {}.{}", project_name, consent_group);
            return privs;
        }

        String dataType = projectMetadata.getDataType();
        Boolean isHarmonized = projectMetadata.getIsHarmonized();
        String concept_path = projectMetadata.getTopLevelPath();
        String projectAlias = projectMetadata.getAbbreviatedName();

        // Need to add the escape sequence back in to the path for parsing later (also need to doubly escape the regex).
        // Need to do this for the query Template and scopes, but should NOT do this for the rules.
        if (concept_path != null) {
            concept_path = concept_path.replaceAll("\\\\", "\\\\\\\\");
        }

        if (dataType != null && dataType.contains("G")) {
            //insert genomic/topmed privs - this will also add rules for including harmonized & parent data if applicable
            privs.add(upsertTopmedPrivilege(project_name, projectAlias, consent_group, concept_path, isHarmonized));
        }

        if (dataType != null && dataType.contains("P")) {
            //insert clinical privs
            logger.debug("addPrivileges() project:{} consent_group:{} concept_path:{}", project_name, consent_group, concept_path);
            privs.add(upsertClinicalPrivilege(project_name, projectAlias, consent_group, concept_path, false));

            //if harmonized study, also create harmonized privileges
            if (Boolean.TRUE.equals(isHarmonized)) {
                privs.add(upsertClinicalPrivilege(project_name, projectAlias, consent_group, concept_path, true));
            }
        }

        //projects without G or P in data_type are skipped
        if (dataType == null || (!dataType.contains("P") && !dataType.contains("G"))) {
            logger.warn("Missing study type for {} {}. Skipping.", project_name, consent_group);
        }

        logger.info("addPrivileges() Finished");
        return privs;
    }

    /**
     * Creates a privilege with a set of access rules that allow queries containing a consent group to pass if the query only contains valid entries that match conceptPath.  If the study is harmonized,
     * this also creates an access rule to allow access when using the harmonized consent concept path.
     * Privileges created with this method will deny access if any genomic filters (topmed data) are included.
     *
     * @param studyIdentifier The study identifier
     * @param consent_group   The consent group
     * @param conceptPath     The concept path
     * @param isHarmonized    Whether the study is harmonized
     * @return The created privilege
     */
    private Privilege upsertClinicalPrivilege(String studyIdentifier, String projectAlias, String consent_group, String conceptPath, boolean isHarmonized) {
        // Construct the privilege name
        String privilegeName = (consent_group != null && !consent_group.isEmpty()) ?
                "PRIV_MANAGED_" + studyIdentifier + "_" + consent_group + (isHarmonized ? "_HARMONIZED" : "") :
                "PRIV_MANAGED_" + studyIdentifier + (isHarmonized ? "_HARMONIZED" : "");

        // Check if the Privilege already exists
        Privilege priv = this.findByName(privilegeName);
        if (priv == null) {
            priv = new Privilege();
        }

        try {
            priv.setApplication(picSureApp);
            priv.setName(privilegeName);

            // In BioData Catalyst this is either  \\_harmonized_consent\\_ or \\_consents\\ we need to escape the slashes
            String consent_concept_path = isHarmonized ? fence_harmonized_consent_group_concept_path : fence_parent_consent_group_concept_path;
            consent_concept_path = escapePath(consent_concept_path);
            fence_harmonized_concept_path = escapePath(fence_harmonized_concept_path);

            priv.setQueryTemplate(createClinicalQueryTemplate(studyIdentifier, consent_group, consent_concept_path));
            priv.setQueryScope(isHarmonized ? String.format("[\"%s\",\"_\",\"%s\"]", conceptPath, fence_harmonized_concept_path) : String.format("[\"%s\",\"_\"]", conceptPath));

            Set<AccessRule> accessRules = new HashSet<>();
            accessRules.add(createClinicalParentAccessRule(studyIdentifier, consent_group, conceptPath, projectAlias));
            accessRules.add(createClinicalTopmedParentAccessRule(studyIdentifier, consent_group, conceptPath, projectAlias));
            if (isHarmonized) {
                accessRules.add(createClinicalHarmonizedAccessRule(studyIdentifier, consent_group, conceptPath, projectAlias));
            }
            accessRules.addAll(this.accessRuleService.addStandardAccessRules());
            priv.setAccessRules(accessRules);

            priv = this.save(priv);
            logger.info("Added new privilege {} to DB", priv.getName());
        } catch (Exception ex) {
            logger.error("Could not save privilege", ex);
        }
        return priv;
    }

    private static String createClinicalQueryTemplate(String studyIdentifier, String consent_group, String consent_concept_path) {
        String studyIdentifierField = (consent_group != null && !consent_group.isEmpty()) ? studyIdentifier + "." + consent_group : studyIdentifier;
        return String.format(
                "{\"categoryFilters\": {\"%s\":[\"%s\"]},\"numericFilters\":{},\"requiredFields\":[],\"fields\":[\"%s\"],\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],\"expectedResultType\": \"COUNT\"}",
                consent_concept_path, studyIdentifierField, AccessRuleService.parentAccessionField
        );
    }

    private AccessRule createClinicalHarmonizedAccessRule(String studyIdentifier, String consentGroup, String conceptPath, String projectAlias) {
        AccessRule ar = this.accessRuleService.createConsentAccessRule(studyIdentifier, consentGroup, "HARMONIZED", fence_harmonized_consent_group_concept_path);
        ar.setSubAccessRule(new HashSet<>());
        this.accessRuleService.configureHarmonizedAccessRule(ar, studyIdentifier, conceptPath, projectAlias);
        return this.accessRuleService.save(ar);
    }

    private AccessRule createClinicalTopmedParentAccessRule(String studyIdentifier, String consentGroup, String conceptPath, String projectAlias) {
        AccessRule ar = this.accessRuleService.upsertTopmedAccessRule(studyIdentifier, consentGroup, "TOPMED+PARENT");
        ar.setSubAccessRule(new HashSet<>());
        ar = this.accessRuleService.configureClinicalAccessRuleWithPhenoSubRule(ar, studyIdentifier, consentGroup, conceptPath, projectAlias);
        return this.accessRuleService.save(ar);
    }

    private AccessRule createClinicalParentAccessRule(String studyIdentifier, String consentGroup, String conceptPath, String projectAlias) {
        AccessRule ar = this.accessRuleService.createConsentAccessRule(studyIdentifier, consentGroup, "PARENT", fence_parent_consent_group_concept_path);
        ar.setSubAccessRule(new HashSet<>());
        this.accessRuleService.configureAccessRule(ar, studyIdentifier, consentGroup, conceptPath, projectAlias);
        return this.accessRuleService.save(ar);
    }

    /**
     * Creates a privilege for Topmed access. This has (up to) three access rules:
     * 1) topmed only 2) topmed + parent 3) topmed + harmonized.
     *
     * @param studyIdentifier   The study identifier
     * @param projectAlias      The project alias
     * @param consentGroup      The consent group
     * @param parentConceptPath The parent concept path
     * @param isHarmonized      Whether the study is harmonized
     * @return Privilege
     */
    private Privilege upsertTopmedPrivilege(String studyIdentifier, String projectAlias, String consentGroup, String parentConceptPath, boolean isHarmonized) {
        String privilegeName = "PRIV_MANAGED_" + studyIdentifier + "_" + consentGroup + "_TOPMED";
        Privilege priv = this.findByName(privilegeName);

        if (priv == null) {
            priv = new Privilege();
        }

        try {
            buildPrivilegeObject(priv, privilegeName, studyIdentifier, consentGroup);

            Set<AccessRule> accessRules = new HashSet<>();
            accessRules.add(createTopmedAccessRules(studyIdentifier, projectAlias, consentGroup));

            if (parentConceptPath != null) {
                accessRules.add(createTopmedParentAccessRule(studyIdentifier, consentGroup, parentConceptPath, projectAlias));
                if (isHarmonized) {
                    accessRules.add(createHarmonizedTopmedAccessRule(studyIdentifier, projectAlias, consentGroup, parentConceptPath));
                }
            }

            accessRules.addAll(this.accessRuleService.addStandardAccessRules());

            priv.setAccessRules(accessRules);
            logger.info("upsertTopmedPrivilege() Added {} access_rules to privilege", accessRules.size());

            priv = this.save(priv);
            logger.info("upsertTopmedPrivilege() Added new privilege {} to DB", priv.getName());
        } catch (Exception ex) {
            logger.error("upsertTopmedPrivilege() could not save privilege", ex);
        }

        return priv;
    }

    private AccessRule createHarmonizedTopmedAccessRule(String studyIdentifier, String projectAlias, String consentGroup, String parentConceptPath) {
        AccessRule harmonizedRule = this.accessRuleService.upsertHarmonizedAccessRule(studyIdentifier, consentGroup);
        harmonizedRule.setSubAccessRule(new HashSet<>());
        harmonizedRule = this.accessRuleService.populateHarmonizedAccessRule(harmonizedRule, parentConceptPath, studyIdentifier, projectAlias);
        this.accessRuleService.save(harmonizedRule);
        return harmonizedRule;
    }

    private AccessRule createTopmedParentAccessRule(String studyIdentifier, String consentGroup, String parentConceptPath, String projectAlias) {
        AccessRule topmedParentRule = this.accessRuleService.upsertTopmedAccessRule(studyIdentifier, consentGroup, "TOPMED+PARENT");
        topmedParentRule.setSubAccessRule(new HashSet<>());
        this.accessRuleService.populateTopmedAccessRule(topmedParentRule, true);
        topmedParentRule.getSubAccessRule().addAll(this.accessRuleService.getPhenotypeSubRules(studyIdentifier, parentConceptPath, projectAlias));
        topmedParentRule.getSubAccessRule().add(this.accessRuleService.createPhenotypeSubRule(fence_topmed_consent_group_concept_path, "ALLOW_TOPMED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));
        return this.accessRuleService.save(topmedParentRule);
    }

    private AccessRule createTopmedAccessRules(String studyIdentifier, String projectAlias, String consentGroup) {
        AccessRule topmedRule = this.accessRuleService.upsertTopmedAccessRule(studyIdentifier, consentGroup, "TOPMED");
        topmedRule = this.accessRuleService.populateTopmedAccessRule(topmedRule, false);
        topmedRule.getSubAccessRule().addAll(this.accessRuleService.getPhenotypeRestrictedSubRules(studyIdentifier, consentGroup, projectAlias));
        return this.accessRuleService.save(topmedRule);
    }

    private void buildPrivilegeObject(Privilege priv, String privilegeName, String studyIdentifier, String consentGroup) {
        priv.setApplication(picSureApp);
        priv.setName(privilegeName);
        String consent = studyIdentifier + (StringUtils.isNotBlank(consentGroup) ? "." + consentGroup : "");
        priv.setDescription("MANAGED privilege for Topmed " + consent);

        String consentConceptPath = escapePath(fence_topmed_consent_group_concept_path);
        fence_harmonized_concept_path = escapePath(fence_harmonized_concept_path);


        String queryTemplateText = "{\"categoryFilters\": {\"" + consentConceptPath + "\":[\"" + consent + "\"]},"
                + "\"numericFilters\":{},\"requiredFields\":[],"
                + "\"fields\":[\"" + topmedAccessionField + "\"],"
                + "\"variantInfoFilters\":[{\"categoryVariantInfoFilters\":{},\"numericVariantInfoFilters\":{}}],"
                + "\"expectedResultType\": \"COUNT\""
                + "}";

        priv.setQueryTemplate(queryTemplateText);

        priv.setQueryScope(buildQueryScope(this.variantAnnotationColumns));
    }

    private String escapePath(String path) {
        if (path != null && !path.contains("\\\\")) {
            return path.replaceAll("\\\\", "\\\\\\\\");
        }
        return path;
    }

    private String buildQueryScope(String variantColumns) {
        if (variantColumns == null || variantColumns.isEmpty()) {
            return "[\"_\"]";
        }

        return Arrays.stream(variantColumns.split(","))
                .map(path -> "\"" + path + "\"")
                .collect(Collectors.joining(",", "[", ",\"_\"]"));
    }

    private String extractProject(String roleName) {
        String projectPattern = "MANAGED_(.*?)(?:_c\\d+)?$";
        if (roleName.startsWith("MANUAL_")) {
            projectPattern = "MANUAL_(.*?)(?:_c\\d+)?$";
        }
        Pattern projectRegex = Pattern.compile(projectPattern);
        Matcher projectMatcher = projectRegex.matcher(roleName);
        String project = "";
        if (projectMatcher.find()) {
            project = projectMatcher.group(1).trim();
        } else {
            logger.info("extractProject() Could not extract project from role name: {}", roleName);
            String[] parts = roleName.split("_", 1);
            if (parts.length > 0) {
                project = parts[1];
            }
        }
        return project;
    }

    private static String extractConsentGroup(String roleName) {
        String consentPattern = "MANAGED_.*?_c(\\d+)$";
        if (roleName.startsWith("MANUAL_")) {
            consentPattern = "MANUAL_.*?_c(\\d+)$";
        }
        Pattern consentRegex = Pattern.compile(consentPattern);
        Matcher consentMatcher = consentRegex.matcher(roleName);
        String consentGroup = "";
        if (consentMatcher.find()) {
            consentGroup = "c" + consentMatcher.group(1).trim();
        }
        return consentGroup;
    }

    private StudyMetaData getStudyMappingForProjectAndConsent(String projectId, String consent_group, Map<String, StudyMetaData> fenceMapping) {
        String consentVal = (consent_group != null && !consent_group.isEmpty()) ? projectId + "." + consent_group : projectId;
        logger.debug("getStudyMappingForProjectAndConsent() looking up {}", consentVal);

        return fenceMapping.get(consentVal);
    }

    public Optional<Privilege> findById(UUID uuid) {
        return privilegeRepository.findById(uuid);
    }

    /**
     * This method will update all existing privileges with the standard access rules and allowed query types.
     * This method will not remove any existing standard access rules If you need to remove a standard access rule,
     * you will need to create a migration script.
     */
    protected void updateAllPrivilegesOnStartup() {
        List<Privilege> privileges = this.getPrivilegesAll();
        Set<AccessRule> standardAccessRules = this.accessRuleService.addStandardAccessRules();
        if (standardAccessRules.isEmpty()) {
            logger.error("No standard access rules found.");
            return;
        } else {
            privileges.forEach(privilege ->
            {
                privilege.getAccessRules().addAll(standardAccessRules);
                this.save(privilege);
            });
        }

        List<UUID> privilegeIds = privileges.stream().map(Privilege::getUuid).toList();
        List<AccessRule> accessRules = this.accessRuleService.getAccessRulesByPrivilegeIds(privilegeIds);

        // find each access rule that has sub access rules that allow query types an update them
        accessRules.parallelStream()
                .filter(accessRule -> accessRule.getSubAccessRule().stream().anyMatch(subAccessRule -> subAccessRule.getName().startsWith("AR_ALLOW_")))
                .forEach(accessRule -> {
                    Set<AccessRule> subAccessRules = accessRule.getSubAccessRule();
                    subAccessRules.removeIf(subAccessRule -> subAccessRule.getName().startsWith("AR_ALLOW_"));

                    // Add the currently allowed query types
                    subAccessRules.addAll(this.accessRuleService.getAllowedQueryTypeRules());
                    accessRule.setSubAccessRule(subAccessRules);
                    this.accessRuleService.save(accessRule);
                });
    }
}
