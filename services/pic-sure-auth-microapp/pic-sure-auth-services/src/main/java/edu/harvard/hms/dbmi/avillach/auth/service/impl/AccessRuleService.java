package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.mysql.cj.xdevapi.JsonArray;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AccessRuleService {

    private final Logger logger = LoggerFactory.getLogger(AccessRuleService.class);

    private final AccessRuleRepository accessRuleRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, AccessRule> accessRuleCache = new ConcurrentHashMap<>();
    private Set<AccessRule> allowQueryTypeRules;
    private Set<AccessRule> standardAccessRules;

    public static final String parentAccessionField = "\\\\_Parent Study Accession with Subject ID\\\\";
    private static final String topmedAccessionField = "\\\\_Topmed Study Accession with Subject ID\\\\";
    private final String fence_harmonized_consent_group_concept_path;
    private final String fence_parent_consent_group_concept_path;
    private final String fence_topmed_consent_group_concept_path;
    private final String fence_standard_access_rules;
    private final String fence_allowed_query_types;
    private final String fence_harmonized_concept_path;

    private String[] underscoreFields;

    @Autowired
    public AccessRuleService(AccessRuleRepository accessRuleRepo,
                             @Value("${fence.harmonized.consent.group.concept.path}") String fenceHarmonizedConsentGroupConceptPath,
                             @Value("${fence.parent.consent.group.concept.path}") String fenceParentConceptPath,
                             @Value("${fence.topmed.consent.group.concept.path}") String fenceTopmedConceptPath,
                             @Value("${fence.standard.access.rules}") String fenceStandardAccessRules,
                             @Value("${fence.allowed.query.types}") String fenceAllowedQueryTypes,
                             @Value("${fence.consent.group.concept.path}") String fenceHarmonizedConceptPath) {
        this.accessRuleRepo = accessRuleRepo;
        this.fence_harmonized_consent_group_concept_path = fenceHarmonizedConsentGroupConceptPath;
        this.fence_parent_consent_group_concept_path = fenceParentConceptPath;
        this.fence_topmed_consent_group_concept_path = fenceTopmedConceptPath;
        this.fence_standard_access_rules = fenceStandardAccessRules;
        this.fence_allowed_query_types = fenceAllowedQueryTypes;
        this.fence_harmonized_concept_path = fenceHarmonizedConceptPath;
    }

    @PostConstruct
    public void init() {
        // We need to set the underscoreFields here so that we can use them in the access rules during PostConstruct
        // If we don't set them here, we will get a NullPointerException when we try to use them in the access rules
        underscoreFields = new String[]{
                parentAccessionField,
                topmedAccessionField,
                fence_harmonized_consent_group_concept_path,
                fence_parent_consent_group_concept_path,
                fence_topmed_consent_group_concept_path,
                "\\\\_VCF Sample Id\\\\",
                "\\\\_studies\\\\",
                "\\\\_studies_consents\\\\",  //used to provide consent-level counts for open access
                "\\\\_parent_consents\\\\",  //parent consents not used for auth (use combined _consents)
                "\\\\_Consents\\\\"
        };

        logger.info("fence_standard_access_rules: {}", fence_standard_access_rules);
        logger.info("fence_allowed_query_types: {}", fence_allowed_query_types);
        logger.info("fence_harmonized_consent_group_concept_path: {}", fence_harmonized_consent_group_concept_path);
        logger.info("fence_parent_consent_group_concept_path: {}", fence_parent_consent_group_concept_path);
        logger.info("fence_topmed_consent_group_concept_path: {}", fence_topmed_consent_group_concept_path);
        logger.info("fence_harmonized_concept_path: {}", fence_harmonized_concept_path);
        logger.info("underscoreFields: {}", Arrays.toString(underscoreFields));
    }

    public Optional<AccessRule> getAccessRuleById(String accessRuleId) {
        return accessRuleRepo.findById(UUID.fromString(accessRuleId));
    }

    public List<AccessRule> getAllAccessRules() {
        return accessRuleRepo.findAll();
    }

    public List<AccessRule> addAccessRule(List<AccessRule> accessRules) {
        accessRules.forEach(accessRule -> {
            if (accessRule.getEvaluateOnlyByGates() == null)
                accessRule.setEvaluateOnlyByGates(false);

            if (accessRule.getCheckMapKeyOnly() == null)
                accessRule.setCheckMapKeyOnly(false);

            if (accessRule.getCheckMapNode() == null)
                accessRule.setCheckMapNode(false);

            if (accessRule.getGateAnyRelation() == null)
                accessRule.setGateAnyRelation(false);
        });

        return this.accessRuleRepo.saveAll(accessRules);
    }

    public List<AccessRule> updateAccessRules(List<AccessRule> accessRules) {
        return this.accessRuleRepo.saveAll(accessRules);
    }

    @Transactional
    public List<AccessRule> removeAccessRuleById(String accessRuleId) {
        this.accessRuleRepo.deleteById(UUID.fromString(accessRuleId));
        return this.accessRuleRepo.findAll();
    }

    public AccessRule save(AccessRule accessRule) {
        // if the access rule exists in the AccessRule cache, update it
        if (accessRuleCache.containsKey(accessRule.getName())) {
            accessRuleCache.put(accessRule.getName(), accessRule);
        }
        return this.accessRuleRepo.save(accessRule);
    }

    public AccessRule getAccessRuleByName(String arName) {
        return this.accessRuleRepo.findByName(arName);
    }

    @Cacheable(value = "mergedRulesCache", keyGenerator = "customKeyGenerator")
    public Set<AccessRule> getAccessRulesForUserAndApp(User user, Application application) {
        try {
            Set<Privilege> privileges = user.getPrivilegesByApplication(application);
            if (privileges == null || privileges.isEmpty()) {
                return new HashSet<>();
            }

            Set<AccessRule> detachedMergedRules = new HashSet<>();
            for (AccessRule rule : preProcessAccessRules(privileges)) {
                detachedMergedRules.add(objectMapper.readValue(objectMapper.writeValueAsString(rule), AccessRule.class));
            }

            return detachedMergedRules;
        } catch (Exception e) {
            logger.error("Error populating or retrieving data from cache: ", e);
        }

        return new HashSet<>();
    }

    @CacheEvict(value = "mergedRulesCache")
    public void evictFromMergedAccessRuleCache(String userSubject) {
        if (StringUtils.isBlank(userSubject)) {
            logger.warn("evictFromMergedAccessRuleCache() was called with a null or empty email");
            return;
        }
        logger.info("evictFromMergedAccessRuleCache() evicting cache for user: {}", userSubject);
    }

    @Cacheable(value = "preProcessedAccessRules", keyGenerator = "customKeyGenerator")
    public Set<AccessRule> cachedPreProcessAccessRules(User user, Set<Privilege> privileges) {
        Set<AccessRule> accessRules = new HashSet<>();
        for (Privilege privilege : privileges) {
            accessRules.addAll(privilege.getAccessRules());
        }

        return preProcessARBySortedKeys(accessRules);
    }

    public Set<AccessRule> preProcessAccessRules(Set<Privilege> privileges) {
        Set<AccessRule> accessRules = new HashSet<>();
        for (Privilege privilege : privileges) {
            accessRules.addAll(privilege.getAccessRules());
        }

        return preProcessARBySortedKeys(accessRules);
    }

    @CacheEvict(value = "preProcessedAccessRules")
    public void evictFromPreProcessedAccessRules(String userSubject) {
        if (userSubject == null || userSubject.isEmpty()) {
            logger.warn("evictFromPreProcessedAccessRules() was called with a null or empty email");
            return;
        }
        logger.info("evictFromPreProcessedAccessRules() evicting cache for user: {}", userSubject);
    }

    public Set<AccessRule> preProcessARBySortedKeys(Set<AccessRule> accessRules) {
        Map<String, Set<AccessRule>> accessRuleMap = new HashMap<>();

        for (AccessRule accessRule : accessRules) {

            // 1st generate the key by grabbing all related string and put them together in order
            // we use a treeSet here to put orderly combine Strings together
            Set<String> keys = new TreeSet<>();

            // the current accessRule rule
            keys.add(accessRule.getRule());

            // all gates' UUID as strings
            keys.add(accessRule.getType().toString());

            if (accessRule.getGates() != null) {
                for (AccessRule gate : accessRule.getGates()) {
                    keys.add(gate.getUuid().toString());
                }
            }

            // all sub accessRule rules
            if (accessRule.getSubAccessRule() != null) {
                for (AccessRule subAccessRule : accessRule.getSubAccessRule()) {
                    keys.add(subAccessRule.getRule());
                }
            }
            Boolean checkMapKeyOnly = accessRule.getCheckMapKeyOnly(),
                    checkMapNode = accessRule.getCheckMapNode(),
                    evaluateOnlyByGates = accessRule.getEvaluateOnlyByGates(),
                    gateAnyRelation = accessRule.getGateAnyRelation();

            keys.add(checkMapKeyOnly == null ? "null" : Boolean.toString(checkMapKeyOnly));
            keys.add(checkMapNode == null ? "null" : Boolean.toString(checkMapNode));
            keys.add(evaluateOnlyByGates == null ? "null" : Boolean.toString(evaluateOnlyByGates));
            keys.add(gateAnyRelation == null ? "null" : Boolean.toString(gateAnyRelation));

            String key = String.join("", keys);
            if (accessRuleMap.containsKey(key)) {
                accessRuleMap.get(key).add(accessRule);
            } else {
                Set<AccessRule> accessRuleSet = new HashSet<>();
                accessRuleSet.add(accessRule);
                accessRuleMap.put(key, accessRuleSet);
            }
        }

        return mergeSameKeyAccessRules(accessRuleMap.values());
    }

    private Set<AccessRule> mergeSameKeyAccessRules(Collection<Set<AccessRule>> accessRuleMap) {
        Set<AccessRule> accessRules = new HashSet<>();
        for (Set<AccessRule> accessRulesSet : accessRuleMap) {
            AccessRule accessRule = null;
            for (AccessRule innerAccessRule : accessRulesSet) {
                accessRule = mergeAccessRules(accessRule, innerAccessRule);
            }
            if (accessRule != null) {
                accessRules.add(accessRule);
            }
        }
        return accessRules;
    }

    private AccessRule mergeAccessRules(AccessRule baseAccessRule, AccessRule accessRuleToBeMerged) {
        if (baseAccessRule == null) {
            accessRuleToBeMerged.getMergedValues().add(accessRuleToBeMerged.getValue());
            return accessRuleToBeMerged;
        }

        if (baseAccessRule.getSubAccessRule() != null && accessRuleToBeMerged.getSubAccessRule() != null) {
            baseAccessRule.getSubAccessRule().addAll(accessRuleToBeMerged.getSubAccessRule());
        } else if (baseAccessRule.getSubAccessRule() == null && accessRuleToBeMerged.getSubAccessRule() != null) {
            baseAccessRule.setSubAccessRule(accessRuleToBeMerged.getSubAccessRule());
        }

        baseAccessRule.getMergedValues().add(accessRuleToBeMerged.getValue());
        if (baseAccessRule.getMergedName().startsWith("Merged|")) {
            baseAccessRule.setMergedName(baseAccessRule.getMergedName() + "|" + accessRuleToBeMerged.getName());
        } else {
            baseAccessRule.setMergedName("Merged|" + baseAccessRule.getName() + "|" + accessRuleToBeMerged.getName());
        }

        return baseAccessRule;
    }

    public boolean evaluateAccessRule(Object parsedRequestBody, AccessRule accessRule) {
        logger.trace("evaluateAccessRule() starting with: {}", parsedRequestBody);
        logger.trace("evaluateAccessRule() access rule: {}", accessRule.getName());

        Set<AccessRule> gates = accessRule.getGates();
        boolean gatesPassed = true;

        // depends on the flag getGateAnyRelation is true or false,
        // the logic of checking if apply gate will be changed
        // the following cases are gate passed:
        // 1. if gates are null or empty
        // 2. if getGateAnyRelation is false, all gates passed
        // 3. if getGateAnyRelation is true, one of the gate passed
        if (gates != null && !gates.isEmpty()) {
            if (accessRule.getGateAnyRelation() == null || !accessRule.getGateAnyRelation()) {
                // All gates are AND relationship
                // means one fails all fail
                for (AccessRule gate : gates) {
                    if (!evaluateAccessRule(parsedRequestBody, gate)) {
                        logger.info("evaluateAccessRule() gate {} failed: {} ____ {}", gate.getName(), gate.getRule(), gate.getValue());
                        gatesPassed = false;
                        break;
                    }
                }
            } else {
                // All gates are OR relationship
                // means one passes all pass
                gatesPassed = false;
                for (AccessRule gate : gates) {
                    if (evaluateAccessRule(parsedRequestBody, gate)) {
                        logger.debug("evaluateAccessRule() gate {} passed ", gate.getName());
                        gatesPassed = true;
                        break;
                    }
                }

                if (!gatesPassed) {
                    logger.debug("all OR gates failed");
                }
            }
        }

        if (accessRule.getEvaluateOnlyByGates() != null && accessRule.getEvaluateOnlyByGates()) {
            logger.debug("evaluateAccessRule() eval only by gates");
            return gatesPassed;
        }

        if (gatesPassed) {
            logger.debug("evaluateAccessRule() gates passed");
            if (!extractAndCheckRule(accessRule, parsedRequestBody)) {
                logger.debug("Query Rejected by rule(1) {} :: {} :: {}", accessRule.getRule(), accessRule.getType(), accessRule.getValue());
                return false;
            } else {
                if (accessRule.getSubAccessRule() != null) {
                    // We need to check all the sub rules as merged rules; they can overlap
                    Set<AccessRule> mergedSubRules = preProcessARBySortedKeys(accessRule.getSubAccessRule());
                    for (AccessRule subAccessRule : mergedSubRules) {
                        if (!extractAndCheckRule(subAccessRule, parsedRequestBody)) {
                            logger.debug("Query Rejected by rule(2) {} :: {} :: {}", subAccessRule.getRule(), subAccessRule.getType(), subAccessRule.getValue());
                            return false;
                        }
                    }
                }
            }
        } else {
            logger.debug("evaluateAccessRule() gates failed");
            return false;
        }

        return true;
    }

    public boolean extractAndCheckRule(AccessRule accessRule, Object parsedRequestBody) {
        logger.debug("extractAndCheckRule() starting");
        String rule = accessRule.getRule();

        if (rule == null || rule.isEmpty())
            return true;

        rule = rule.stripLeading();

        Object requestBodyValue;
        int accessRuleType = accessRule.getType();

        try {
            logger.debug("extractAndCheckRule() -> JsonPath.parse().read() with parsedRequestBody - {} - {}", parsedRequestBody, rule);
            requestBodyValue = JsonPath.parse(parsedRequestBody).read(rule);

            if (accessRule.getCheckMapNode() != null && accessRule.getCheckMapNode()) {
                // Json parse will always return a list even when we want a map (to check keys)
                if (requestBodyValue instanceof JsonArray && ((JsonArray) requestBodyValue).size() == 1) {
                    requestBodyValue = ((JsonArray) requestBodyValue).get(0);
                }
            }
        } catch (PathNotFoundException ex) {
            if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY) {
                // We could return accessRuleType == AccessRule.TypeNaming.IS_EMPTY directly, but we want to log the reason
                logger.debug("extractAndCheckRule() -> JsonPath.parse().read() PathNotFound;  passing IS_EMPTY rule {}", rule);
                return true;
            }
            logger.debug("extractAndCheckRule() -> JsonPath.parse().read() throws exception with parsedRequestBody - {} : {} - {}", parsedRequestBody, ex.getClass().getSimpleName(), ex.getMessage());
            return false;
        }

        if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY
                || accessRuleType == AccessRule.TypeNaming.IS_NOT_EMPTY) {
            if (requestBodyValue == null
                    || (requestBodyValue instanceof String && ((String) requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Collection && ((Collection) requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Map && ((Map) requestBodyValue).isEmpty())) {
                return accessRuleType == AccessRule.TypeNaming.IS_EMPTY;
            } else {
                return accessRuleType == AccessRule.TypeNaming.IS_NOT_EMPTY;
            }
        }

        return evaluateNode(requestBodyValue, accessRule);
    }

    private boolean evaluateNode(Object requestBodyValue, AccessRule accessRule) {
        logger.debug("evaluateNode() starting: {} :: {} :: {}", accessRule.getRule(), accessRule.getType(), accessRule.getMergedValues().isEmpty() ? accessRule.getValue() : ("Merged " + Arrays.deepToString(accessRule.getMergedValues().toArray())));
        logger.trace("evaluateNode() requestBody {}  {}", requestBodyValue.getClass().getName(), requestBodyValue instanceof Collection ?
                Arrays.deepToString(((Collection) requestBodyValue).toArray()) :
                requestBodyValue.toString());

        return switch (requestBodyValue) {
            case String s -> decisionMaker(accessRule, s);
            case Collection collection -> evaluateCollection(collection, accessRule);
            case Map map when accessRule.getCheckMapNode() != null && accessRule.getCheckMapNode() ->
                    evaluateMap(requestBodyValue, accessRule);
            default -> true;
        };
    }

    private boolean evaluateMap(Object requestBodyValue, AccessRule accessRule) {
        logger.trace("evaluateMap() access rule:{}", accessRule.getName());
        logger.trace("evaluateMap() request body value:{}", requestBodyValue);

        switch (accessRule.getType()) {
            case (AccessRule.TypeNaming.ANY_EQUALS):
            case (AccessRule.TypeNaming.ANY_CONTAINS):
            case (AccessRule.TypeNaming.ANY_REG_MATCH):
                for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()) {
                    if (decisionMaker(accessRule, (String) entry.getKey()))
                        return true;

                    if ((accessRule.getCheckMapKeyOnly() == null || !accessRule.getCheckMapKeyOnly())
                            && evaluateNode(entry.getValue(), accessRule))
                        return true;
                }
                return false;
            default:
                if (((Map) requestBodyValue).isEmpty()) {
                    switch (accessRule.getType()) {
                        case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE):
                        case (AccessRule.TypeNaming.ALL_EQUALS):
                        case (AccessRule.TypeNaming.ALL_CONTAINS):
                        case (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE):
                            return false;
                        case (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY):
                        case (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY_IGNORE_CASE):
                        default:
                            return true;
                    }
                }
                for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()) {
                    if (!decisionMaker(accessRule, (String) entry.getKey()))
                        return false;

                    if ((accessRule.getCheckMapKeyOnly() == null || !accessRule.getCheckMapKeyOnly())
                            && !evaluateNode(entry.getValue(), accessRule))
                        return false;
                }

        }

        return true;
    }

    private Boolean evaluateCollection(Collection requestBodyValue, AccessRule accessRule) {
        logger.trace("evaluateCollection() access rule:{}", accessRule.getName());
        logger.trace("evaluateCollection() request body value:{}", requestBodyValue);

        switch (accessRule.getType()) {
            case (AccessRule.TypeNaming.ANY_EQUALS):
            case (AccessRule.TypeNaming.ANY_CONTAINS):
            case (AccessRule.TypeNaming.ANY_REG_MATCH):
                for (Object item : requestBodyValue) {
                    if (item instanceof String) {
                        if (decisionMaker(accessRule, (String) item)) {
                            return true;
                        }
                    } else {
                        if (evaluateNode(item, accessRule)) {
                            return true;
                        }
                    }
                }
                return false;
            default:
                if (requestBodyValue.isEmpty()) {
                    switch (accessRule.getType()) {
                        case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE):
                        case (AccessRule.TypeNaming.ALL_EQUALS):
                        case (AccessRule.TypeNaming.ALL_CONTAINS):
                        case (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE):
                            return false;
                        case (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY):
                        case (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY_IGNORE_CASE):
                        default:
                            return true;
                    }
                }

                for (Object item : requestBodyValue) {
                    if (item instanceof String) {
                        if (!decisionMaker(accessRule, (String) item)) {
                            return false;
                        }
                    } else {
                        if (!evaluateNode(item, accessRule))
                            return false;
                    }
                }
        }

        return true;
    }

    public boolean decisionMaker(AccessRule accessRule, String requestBodyValue) {
        if (accessRule.getMergedValues().isEmpty()) {
            String value = accessRule.getValue();
            if (value == null) {
                return requestBodyValue == null;
            }
            return _decisionMaker(accessRule, requestBodyValue, value);
        }

        // recursively check the values
        // until one of them is true
        // if there is only one element in the merged value set
        // the operation equals to _decisionMaker(accessRule, requestBodyValue, value)
        boolean res = false;
        for (String s : accessRule.getMergedValues()) {
            // check the special case value is null
            // if value is null, the check will stop here and
            // not goes to _decisionMaker()
            if (s == null) {
                if (requestBodyValue == null) {
                    res = true;
                    break;
                } else {
                    continue;
                }
            }

            // all the merged values are OR relationship
            // means if you pass one of them, you pass the rule
            if (_decisionMaker(accessRule, requestBodyValue, s)) {
                res = true;
                break;
            }
        }
        return res;
    }

    private boolean _decisionMaker(AccessRule accessRule, String requestBodyValue, String value) {
        logger.trace("_decisionMaker() starting");
        logger.trace("_decisionMaker() access rule:{}", accessRule.getName());
        logger.trace("_decisionMaker() request body value:{}", requestBodyValue);
        logger.trace("_decisionMaker() value:{}", value);
        logger.trace("_decisionMaker() access rule type:{}", accessRule.getType());

        return switch (accessRule.getType()) {
            case AccessRule.TypeNaming.NOT_CONTAINS -> !requestBodyValue.contains(value);
            case AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE -> !requestBodyValue.toLowerCase().contains(value.toLowerCase());
            case (AccessRule.TypeNaming.NOT_EQUALS) -> !value.equals(requestBodyValue);
            case (AccessRule.TypeNaming.ANY_EQUALS), (AccessRule.TypeNaming.ALL_EQUALS) -> value.equals(requestBodyValue);
            case (AccessRule.TypeNaming.ALL_CONTAINS), (AccessRule.TypeNaming.ANY_CONTAINS), (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY) -> requestBodyValue.contains(value);
            case (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE), (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY_IGNORE_CASE) -> requestBodyValue.toLowerCase().contains(value.toLowerCase());
            case (AccessRule.TypeNaming.NOT_EQUALS_IGNORE_CASE) -> !value.equalsIgnoreCase(requestBodyValue);
            case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE) -> value.equalsIgnoreCase(requestBodyValue);
            case (AccessRule.TypeNaming.ALL_REG_MATCH), (AccessRule.TypeNaming.ANY_REG_MATCH) -> requestBodyValue.matches(value);
            default -> {
                logger.warn("evaluateAccessRule() incoming accessRule type is out of scope. Just return true.");
                yield true;
            }
        };
    }

    /**
     * Configures the AccessRule with gates and sub-rules.
     *
     * @param ar              The AccessRule to configure.
     * @param studyIdentifier The study identifier.
     * @param consent_group   The consent group.
     * @param conceptPath     The concept path.
     * @param projectAlias    The project alias.
     */
    protected void configureAccessRule(AccessRule ar, String studyIdentifier, String consent_group, String conceptPath, String projectAlias) {
        if (ar.getGates() == null) {
            ar.setGates(new HashSet<>());
            ar.getGates().addAll(getGates(true, false, false));

            if (ar.getSubAccessRule() == null) {
                ar.setSubAccessRule(new HashSet<>());
            }
            ar.getSubAccessRule().addAll(getAllowedQueryTypeRules());
            ar.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
            ar.getSubAccessRule().addAll(getTopmedRestrictedSubRules());
        }
    }

    /**
     * Configures the harmonized AccessRule with gates and sub-rules.
     *
     * @param ar              The AccessRule to configure.
     * @param studyIdentifier The study identifier.
     * @param conceptPath     The concept path.
     * @param projectAlias    The project alias.
     * @return
     */
    protected void configureHarmonizedAccessRule(AccessRule ar, String studyIdentifier, String conceptPath, String projectAlias) {
        if (ar.getGates() == null) {
            ar.setGates(new HashSet<>());
            ar.getGates().add(upsertConsentGate("HARMONIZED_CONSENT", "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]", true, "harmonized data"));

            if (ar.getSubAccessRule() == null) {
                ar.setSubAccessRule(new HashSet<>());
            }
            ar.getSubAccessRule().addAll(getAllowedQueryTypeRules());
            ar.getSubAccessRule().addAll(getHarmonizedSubRules());
            ar.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
        }
    }

    protected AccessRule configureClinicalAccessRuleWithPhenoSubRule(AccessRule ar, String studyIdentifier, String consent_group, String conceptPath, String projectAlias) {
        if (ar.getGates() == null) {
            ar.setGates(new HashSet<>());
            ar.getGates().addAll(getGates(true, false, true));

            if (ar.getSubAccessRule() == null) {
                ar.setSubAccessRule(new HashSet<>());
            }
            ar.getSubAccessRule().addAll(getAllowedQueryTypeRules());
            ar.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
            ar.getSubAccessRule().add(createPhenotypeSubRule(fence_topmed_consent_group_concept_path, "ALLOW_TOPMED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));
        }

        return ar;
    }

    protected Set<AccessRule> getAllowedQueryTypeRules() {
        if (allowQueryTypeRules == null) {
            allowQueryTypeRules = loadAllowedQueryTypeRules();
        }

        return allowQueryTypeRules;
    }

    /**
     * Retrieves or creates AccessRules for allowed query types.
     *
     * @return A set of AccessRules for allowed query types.
     */
    private Set<AccessRule> loadAllowedQueryTypeRules() {
        // Initialize a set to hold the AccessRules
        Set<AccessRule> rules = new HashSet<>();
        // Split the allowed query types from the configuration
        String[] allowedTypes = this.fence_allowed_query_types.split(",");

        // Iterate over each allowed query type
        for (String queryType : allowedTypes) {
            // Construct the AccessRule name
            String ar_name = "AR_ALLOW_" + queryType;

            // Log the creation of a new AccessRule
            AccessRule ar = getOrCreateAccessRule(
                    ar_name,
                    "MANAGED SUB AR to allow " + queryType + " Queries",
                    "$.query.query.expectedResultType",
                    AccessRule.TypeNaming.ALL_EQUALS,
                    queryType,
                    false,
                    false,
                    false,
                    false
            );

            // Add the newly created rule to the set
            rules.add(ar);
        }
        // Return the set of AccessRules
        return rules;
    }



    private Collection<? extends AccessRule> getTopmedRestrictedSubRules() {
        Set<AccessRule> rules = new HashSet<AccessRule>();
        rules.add(upsertTopmedRestrictedSubRule("CATEGORICAL", "$.query.query.variantInfoFilters[*].categoryVariantInfoFilters.*"));
        rules.add(upsertTopmedRestrictedSubRule("NUMERIC", "$.query.query.variantInfoFilters[*].numericVariantInfoFilters.*"));

        return rules;
    }

    /**
     * Creates and returns a restricted sub-rule AccessRule for Topmed.
     * topmed restriction rules don't need much configuration.  Just deny all access.
     *
     * @param type The type of the Topmed restriction.
     * @param rule The rule expression.
     * @return The created AccessRule.
     */
    private AccessRule upsertTopmedRestrictedSubRule(String type, String rule) {
        // Construct the AccessRule name
        String ar_name = "AR_TOPMED_RESTRICTED_" + type;
        // Check if the AccessRule already exists
        AccessRule ar = this.getAccessRuleByName(ar_name);
        if (ar != null) {
            // Log and return the existing rule
            logger.debug("Found existing rule: {}", ar.getName());
            return ar;
        }

        // Log the creation of a new AccessRule
        // Create the AccessRule using the createAccessRule method
        return getOrCreateAccessRule(
                ar_name,
                "MANAGED SUB AR for restricting " + type + " genomic concepts",
                rule,
                AccessRule.TypeNaming.IS_EMPTY,
                null,
                false,
                false,
                false,
                false
        );
    }

    protected Collection<? extends AccessRule> getPhenotypeSubRules(String studyIdentifier, String conceptPath, String alias) {

        Set<AccessRule> rules = new HashSet<AccessRule>();
        //categorical filters will always contain at least one entry (for the consent groups); it will never be empty
        rules.add(createPhenotypeSubRule(fence_parent_consent_group_concept_path, "ALLOW_PARENT_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS", false));
        }

        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.numericFilters", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "NUMERIC", true));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQUIRED_FIELDS", false));

        return rules;
    }

    /**
     * Harmonized rules should allow the user to supply paretn and top med consent groups;  this allows a single harmonized
     * rules instead of splitting between a topmed+harmonized and parent+harmonized
     *
     * @return
     */
    private Collection<? extends AccessRule> getHarmonizedSubRules() {

        Set<AccessRule> rules = new HashSet<AccessRule>();
        //categorical filters will always contain at least one entry (for the consent groups); it will never be empty
        rules.add(createPhenotypeSubRule(fence_parent_consent_group_concept_path, "ALLOW_PARENT_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));
        rules.add(createPhenotypeSubRule(fence_harmonized_consent_group_concept_path, "ALLOW_HARMONIZED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));
        rules.add(createPhenotypeSubRule(fence_topmed_consent_group_concept_path, "ALLOW_TOPMED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS", false));
        }

        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.numericFilters", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "NUMERIC", true));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQUIRED_FIELDS", false));

        return rules;
    }


    /**
     * generate and return a set of rules that disallow access to phenotype data (only genomic filters allowed)
     *
     * @return
     */
    protected Collection<? extends AccessRule> getPhenotypeRestrictedSubRules(String studyIdentifier, String consentCode, String alias) {
        Set<AccessRule> rules = new HashSet<AccessRule>();
        //categorical filters will always contain at least one entry (for the consent groups); it will never be empty
        rules.add(createPhenotypeSubRule(fence_topmed_consent_group_concept_path, "ALLOW_TOPMED_CONSENT", "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "", true));

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.fields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS", false));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.categoryFilters", AccessRule.TypeNaming.ALL_CONTAINS, "CATEGORICAL", true));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS", false));
        }

        rules.add(createPhenotypeSubRule(null, alias + "_" + studyIdentifier + "_" + consentCode, "$.query.query.numericFilters.[*]", AccessRule.TypeNaming.IS_EMPTY, "DISALLOW_NUMERIC", false));
        rules.add(createPhenotypeSubRule(null, alias + "_" + studyIdentifier + "_" + consentCode, "$.query.query.requiredFields.[*]", AccessRule.TypeNaming.IS_EMPTY, "DISALLOW_REQUIRED_FIELDS", false));

        return rules;
    }

    /**
     * Return a set of gates that identify which consent values have been provided.  the boolean parameters indicate
     * if a value in the specified consent location should allow this gate to pass.
     *
     * @param parent
     * @param harmonized
     * @param topmed
     * @return
     */
    private Collection<? extends AccessRule> getGates(boolean parent, boolean harmonized, boolean topmed) {
        Set<AccessRule> gates = new HashSet<AccessRule>();
        gates.add(upsertConsentGate("PARENT_CONSENT", "$.query.query.categoryFilters." + fence_parent_consent_group_concept_path + "[*]", parent, "parent study data"));
        gates.add(upsertConsentGate("HARMONIZED_CONSENT", "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]", harmonized, "harmonized data"));
        gates.add(upsertConsentGate("TOPMED_CONSENT", "$.query.query.categoryFilters." + fence_topmed_consent_group_concept_path + "[*]", topmed, "Topmed data"));

        return gates;
    }

    protected AccessRule populateTopmedAccessRule(AccessRule rule, boolean includeParent) {
        if (rule.getGates() == null) {
            rule.setGates(new HashSet<>(getGates(includeParent, false, true)));
        } else {
            rule.getGates().addAll(getGates(includeParent, false, true));
        }

        if (rule.getSubAccessRule() == null) {
            rule.setSubAccessRule(new HashSet<>(getAllowedQueryTypeRules()));
        } else {
            rule.getSubAccessRule().addAll(getAllowedQueryTypeRules());
        }

        return rule;
    }

    protected AccessRule populateHarmonizedAccessRule(AccessRule rule, String parentConceptPath, String studyIdentifier, String projectAlias) {
        if (rule.getGates() == null) {
            rule.setGates(new HashSet<>(Collections.singletonList(
                    upsertConsentGate("HARMONIZED_CONSENT", "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]", true, "harmonized data")
            )));
        }

        if (rule.getSubAccessRule() == null) {
            rule.setSubAccessRule(new HashSet<>(getAllowedQueryTypeRules()));
            rule.getSubAccessRule().addAll(getHarmonizedSubRules());
            rule.getSubAccessRule().addAll(getPhenotypeSubRules(studyIdentifier, parentConceptPath, projectAlias));
        }

        return rule;
    }


    /**
     * The set of standard access rules that are added to all privileges.
     * This set is cached to avoid loading the rules multiple times.
     *
     * @return The set of standard access rules.
     */
    protected Set<AccessRule> addStandardAccessRules() {
        if (standardAccessRules != null && !standardAccessRules.isEmpty()) {
            return standardAccessRules;
        }

        standardAccessRules = new HashSet<>();
        for (String arName : fence_standard_access_rules.split(",")) {
            if (arName.startsWith("AR_")) {
                AccessRule ar = this.getAccessRuleByName(arName);
                if (ar != null) {
                    standardAccessRules.add(ar);
                } else {
                    logger.warn("Unable to find an access rule with name {}", arName);
                }
            } else {
                logger.info("Skipping AccessRule {} as it does not start with AR_", arName);
            }
        }

        logger.info("Added {} standard access rules to privilege", standardAccessRules.size());
        return standardAccessRules;
    }


    /**
     * Creates and returns a consent access rule AccessRule.
     * Generates Main rule only; gates & sub-rules attached after calling this
     * prentRule should be null if this is the main rule, or the appropriate value if this is a sub-rule
     *
     * @param studyIdentifier The study identifier.
     * @param consent_group   The consent group.
     * @param label           The label for the rule.
     * @param consent_path    The consent path.
     * @return The created AccessRule.
     */
    protected AccessRule createConsentAccessRule(String studyIdentifier, String consent_group, String label, String consent_path) {
        String ar_name = (consent_group != null && !consent_group.isEmpty()) ? "AR_CONSENT_" + studyIdentifier + "_" + consent_group + "_" + label : "AR_CONSENT_" + studyIdentifier;
        String description = (consent_group != null && !consent_group.isEmpty()) ? "MANAGED AR for " + studyIdentifier + "." + consent_group + " clinical concepts" : "MANAGED AR for " + studyIdentifier + " clinical concepts";
        String ruleText = "$.query.query.categoryFilters." + consent_path + "[*]";
        String arValue = (consent_group != null && !consent_group.isEmpty()) ? studyIdentifier + "." + consent_group : studyIdentifier;

        return getOrCreateAccessRule(
                ar_name,
                description,
                ruleText,
                AccessRule.TypeNaming.ALL_CONTAINS,
                arValue,
                false,
                false,
                false,
                false
        );
    }

    /**
     * Creates and returns a Topmed access rule AccessRule.
     * Generates Main Rule only; gates & sub-rules attached by calling method
     *
     * @param project_name  The name of the project.
     * @param consent_group The consent group.
     * @param label         The label for the rule.
     * @return The created AccessRule.
     */
    protected AccessRule upsertTopmedAccessRule(String project_name, String consent_group, String label) {
        String ar_name = (consent_group != null && !consent_group.isEmpty()) ? "AR_TOPMED_" + project_name + "_" + consent_group + "_" + label : "AR_TOPMED_" + project_name + "_" + label;
        String description = "MANAGED AR for " + project_name + "." + consent_group + " Topmed data";

        String conceptPath = fence_topmed_consent_group_concept_path;
        // Check if the conceptPath has `\\\\` present. This technically represents `\\`.
        if(conceptPath != null && conceptPath.contains("\\\\")) {
            // This will convert all `\\\\` to `\\`.
            conceptPath = conceptPath.replaceAll("\\\\\\\\", "\\\\");
        }

        String ruleText = "$.query.query.categoryFilters." + conceptPath + "[*]";
        String arValue = (consent_group != null && !consent_group.isEmpty()) ? project_name + "." + consent_group : project_name;

        return getOrCreateAccessRule(
                ar_name,
                description,
                ruleText,
                AccessRule.TypeNaming.ALL_CONTAINS,
                arValue,
                false,
                false,
                false,
                false
        );
    }

    /**
     * Creates and returns a harmonized access rule AccessRule for Topmed.
     * Generates Main Rule only; gates & sub rules attached by calling method
     *
     * @param project_name  The name of the project.
     * @param consent_group The consent group.
     * @return The created AccessRule.
     */
    protected AccessRule upsertHarmonizedAccessRule(String project_name, String consent_group) {
        String ar_name = "AR_TOPMED_" + project_name + "_" + consent_group + "_" + "HARMONIZED";
        logger.debug("upsertHarmonizedAccessRule() Creating new access rule {}", ar_name);
        String description = "MANAGED AR for " + project_name + "." + consent_group + " Topmed data";
        String ruleText = "$.query.query.categoryFilters." + fence_harmonized_consent_group_concept_path + "[*]";
        String arValue = project_name + "." + consent_group;

        return getOrCreateAccessRule(
                ar_name,
                description,
                ruleText,
                AccessRule.TypeNaming.ALL_CONTAINS,
                arValue,
                false,
                false,
                false,
                false
        );
    }

    /**
     * Creates and returns a consent gate AccessRule.
     * Insert a new gate (if it doesn't exist yet) to identify if consent values are present in the query.
     * return an existing gate named GATE_{gateName}_(PRESENT|MISSING) if it exists.
     *
     * @param gateName    The name of the gate.
     * @param rule        The rule expression.
     * @param is_present  Whether the gate is for present or missing consent.
     * @param description The description of the gate.
     * @return The created AccessRule.
     */
    private AccessRule upsertConsentGate(String gateName, String rule, boolean is_present, String description) {
        gateName = "GATE_" + gateName + "_" + (is_present ? "PRESENT" : "MISSING");

        escapePath(rule);

        return getOrCreateAccessRule(
                gateName,
                "MANAGED GATE for " + description + " consent " + (is_present ? "present" : "missing"),
                rule,
                is_present ? AccessRule.TypeNaming.IS_NOT_EMPTY : AccessRule.TypeNaming.IS_EMPTY,
                null,
                false,
                false,
                false,
                false
        );
    }

    protected AccessRule createPhenotypeSubRule(String conceptPath, String alias, String rule, int ruleType, String label, boolean useMapKey) {
        String ar_name = "AR_PHENO_" + alias + "_" + label;
        logger.debug("createPhenotypeSubRule() Creating new access rule {}", ar_name);

        // Check if the conceptPath has `\\\\` present. This technically represents `\\`.
        if(conceptPath != null && conceptPath.contains("\\\\")) {
           // This will convert all `\\\\` to `\\`.
            conceptPath = conceptPath.replaceAll("\\\\\\\\", "\\\\");
        }

        return getOrCreateAccessRule(
                ar_name,
                "MANAGED SUB AR for " + alias + " " + label + " clinical concepts",
                rule,
                ruleType,
                ruleType == AccessRule.TypeNaming.IS_NOT_EMPTY ? null : conceptPath,
                useMapKey,
                useMapKey,
                false,
                false
        );
    }

    protected AccessRule getOrCreateAccessRule(String name, String description, String rule, int type, String value, boolean checkMapKeyOnly, boolean checkMapNode, boolean evaluateOnlyByGates, boolean gateAnyRelation) {
        return accessRuleCache.computeIfAbsent(name, key -> {
            AccessRule ar = this.getAccessRuleByName(key);
            if (ar == null) {
                logger.debug("Creating new access rule {}", key);
                ar = new AccessRule();
                ar.setName(name);
                ar.setDescription(description);
                ar.setRule(rule);
                ar.setType(type);
                ar.setValue(value);
                ar.setCheckMapKeyOnly(checkMapKeyOnly);
                ar.setCheckMapNode(checkMapNode);
                ar.setEvaluateOnlyByGates(evaluateOnlyByGates);
                ar.setGateAnyRelation(gateAnyRelation);
                ar = this.save(ar);
            }

            return ar;
        });
    }

    private String escapePath(String path) {
        if (path != null && !path.contains("\\\\")) {
            return path.replaceAll("\\\\", "\\\\\\\\");
        }
        return path;
    }

    public List<AccessRule> getAccessRulesByPrivilegeIds(List<UUID> privilegeIds) {
        return this.accessRuleRepo.getAccessRulesByPrivilegeIds(privilegeIds);
    }
}
