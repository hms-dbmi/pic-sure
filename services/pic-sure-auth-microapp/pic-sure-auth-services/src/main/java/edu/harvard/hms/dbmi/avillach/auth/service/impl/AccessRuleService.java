package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.mysql.cj.xdevapi.JsonArray;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.AccessRuleEvaluationNode;
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
import java.util.stream.Collectors;

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

    /*
     * SECURITY: the JsonPath fragments below are the only shapes a NEWLY minted access rule may bind. They address the bare v3 Query that
     * the introspection wire now carries at {@code $.query} -- {"Target Service": "<path>", "query": <bare v3 Query>}. The envelope-era
     * shapes they replace ({@code $.query.query.<field>}, i.e. a v1 Query nested inside a {query, resourceUUID} envelope) resolve nothing
     * anywhere on this wire: submits have been bare since the v3 ingress landed and reads are normalized to bare at dispatch. A generator
     * that keeps minting an envelope path does not fail loudly -- PathNotFoundException is a silent deny for ALL_CONTAINS/ALL_EQUALS/
     * IS_NOT_EMPTY rules and a silent grant for IS_EMPTY/ALL_CONTAINS_OR_EMPTY ones -- which is why these are constants with a test
     * (AccessRuleGeneratorWireTest) that resolves every one of them against a serialized TargetedRequest.
     *
     * DEPLOYED ROWS ARE NOT TOUCHED. getOrCreateAccessRule looks rules up by NAME and returns the existing row unchanged, so an environment
     * that already has AR_ALLOW_COUNT keeps its stored (envelope-era) rule text; only rules minted into a database that does not yet have
     * them get the shapes below. Rule NAMES are therefore deliberately left as they are: renaming one would mint a second, differently
     * shaped rule alongside the deployed one and AND it into the same privilege.
     */

    /** The result type the query asks for. Envelope-era: {@code $.query.query.expectedResultType}. */
    public static final String EXPECTED_RESULT_TYPE_PATH = "$.query.expectedResultType";

    /** Every concept path the query asks to have returned. Envelope-era: {@code $.query.query.fields.[*]}. */
    public static final String SELECT_CONCEPT_PATHS_PATH = "$.query.select.[*]";

    /*
     * ONLY phenotypicFilterType may discriminate a filter's kind. The v1 wire kept categorical and numeric filters in two different
     * MEMBERS (categoryFilters / numericFilters), so the JsonPath addressed them apart for free. v3 keeps both in one PhenotypicFilter and
     * the difference is which of values / min / max is populated -- and that CANNOT be tested here: json-path 2.9.0 reads an ABSENT key as
     * "!= null" and a key present with a null value as "not existing", so a predicate like [?(@.min != null)] gives opposite answers for
     * the two serializations this wire legitimately carries (a client's sparse body vs. a re-serialized typed Query, which emits nulls).
     * phenotypicFilterType is always present, so it is the only safe discriminator.
     */

    /**
     * The concept path of every value/range filter, at any nesting depth of the phenotypic clause tree. Envelope-era: the KEYS of
     * {@code $.query.query.categoryFilters} AND of {@code $.query.query.numericFilters} -- v3's FILTER covers both, which is why the two
     * rule labels (CATEGORICAL, NUMERIC) now bind the same path and merge into one rule at evaluation. The envelope-era rules set
     * checkMapKeyOnly/checkMapNode to walk that map; this resolves to a plain list of strings, so those flags are false everywhere now.
     */
    public static final String FILTER_CONCEPT_PATHS_PATH =
        "$.query.phenotypicClause..[?(@.phenotypicFilterType == 'FILTER')].conceptPath";

    /** The concept path of every "must have a value" filter. Envelope-era: {@code $.query.query.requiredFields.[*]}. */
    public static final String REQUIRED_CONCEPT_PATHS_PATH =
        "$.query.phenotypicClause..[?(@.phenotypicFilterType == 'REQUIRED')].conceptPath";

    /**
     * The concept path of every "any record of" filter. Envelope-era: {@code $.query.query.anyRecordOf.[*]} AND
     * {@code $.query.query.anyRecordOfMulti.[*]} -- v3 expresses the "multi" variant as an OR subquery of ANY_RECORD_OF filters, so this
     * single path covers both and the separate ANY_RECORD_OF_MULTI rule is no longer minted.
     */
    public static final String ANY_RECORD_OF_CONCEPT_PATHS_PATH =
        "$.query.phenotypicClause..[?(@.phenotypicFilterType == 'ANY_RECORD_OF')].conceptPath";

    /**
     * Every genomic filter. Envelope-era: {@code $.query.query.variantInfoFilters[*].categoryVariantInfoFilters.*} and its numeric twin --
     * v3 has one flat GenomicFilter list whose categorical/numeric split is again only readable from which of values / min / max is set, so
     * both AR_TOPMED_RESTRICTED_* rules bind this one path. They are IS_EMPTY rules whose shared intent is "no genomic filters at all", so
     * collapsing them loses nothing.
     */
    public static final String GENOMIC_FILTERS_PATH = "$.query.genomicFilters[*]";

    private final String fence_harmonized_consent_group_concept_path;
    private final String fence_parent_consent_group_concept_path;
    private final String fence_topmed_consent_group_concept_path;
    private final String fence_standard_access_rules;
    private final String fence_allowed_query_types;
    private final String fence_harmonized_concept_path;

    private String[] underscoreFields;

    private final ThreadLocal<Stack<AccessRuleEvaluationNode>> evaluationTreeStack = 
        ThreadLocal.withInitial(Stack::new);
    private final ThreadLocal<AccessRuleEvaluationNode> rootNode = new ThreadLocal<>();

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

    /**
     * Prints the evaluation tree for the most recently evaluated access rule.
     * This method should be called after evaluateAccessRule() has been called.
     * 
     * @return A string representation of the evaluation tree
     */
    public String printEvaluationTree() {
        AccessRuleEvaluationNode root = rootNode.get();
        if (root == null) {
            return "No evaluation tree available";
        }


        return "ACCESS RULE EVALUATION TREE:\n" + root.generateTreeString();
    }

    /**
     * Clears the evaluation tree data.
     * This should be called after processing is complete to prevent memory leaks.
     */
    public void clearEvaluationTree() {
        rootNode.remove();
        evaluationTreeStack.remove();
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
        String ruleName = accessRule.getMergedName().isEmpty() ?
                          accessRule.getName() : 
                          accessRule.getMergedName();

        boolean isGate = !evaluationTreeStack.get().isEmpty() &&
                         evaluationTreeStack.get().peek().getRule().getGates() != null &&
                         evaluationTreeStack.get().peek().getRule().getGates().contains(accessRule);
        boolean isSubRule = !evaluationTreeStack.get().isEmpty() && 
                            evaluationTreeStack.get().peek().getRule().getSubAccessRule() != null &&
                            evaluationTreeStack.get().peek().getRule().getSubAccessRule().contains(accessRule);
        boolean isOrRelationship = accessRule.getGateAnyRelation() != null && accessRule.getGateAnyRelation();

        AccessRuleEvaluationNode currentNode = new AccessRuleEvaluationNode(accessRule, isGate, isSubRule, isOrRelationship);

        if (evaluationTreeStack.get().isEmpty()) {
            rootNode.set(currentNode);
        } else {
            evaluationTreeStack.get().peek().addChild(currentNode);
        }

        evaluationTreeStack.get().push(currentNode);

        try {
            logger.trace("evaluateAccessRule() starting with: {}", parsedRequestBody);
            logger.debug("evaluateAccessRule() evaluating rule: {}", ruleName);

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
                            logger.info("evaluateAccessRule() gate {} failed", gate.getName());
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
                            logger.debug("evaluateAccessRule() gate {} passed", gate.getName());
                            gatesPassed = true;
                            break;
                        }
                    }

                    if (!gatesPassed) {
                        logger.debug("All OR gates failed");
                    }
                }
            }

            boolean result = false;
            if (accessRule.getEvaluateOnlyByGates() != null && accessRule.getEvaluateOnlyByGates()) {
                logger.debug("evaluateAccessRule() eval only by gates");
                result = gatesPassed;
                currentNode.setResult(result);
                if (!result) {
                    currentNode.setFailureReason("Gates evaluation failed");
                }
                return result;
            }

            if (gatesPassed) {
                logger.debug("evaluateAccessRule() gates passed");
                if (!extractAndCheckRule(accessRule, parsedRequestBody)) {
                    logger.debug("Query Rejected by rule(1) {}, with request body {}", accessRule,  parsedRequestBody);
                    currentNode.setResult(false);
                    currentNode.setFailureReason("Rule check failed: " + accessRule.getRule());
                    return false;
                } else {
                    if (accessRule.getSubAccessRule() != null) {
                        // We need to check all the sub rules as merged rules; they can overlap
                        Set<AccessRule> mergedSubRules = preProcessARBySortedKeys(accessRule.getSubAccessRule());
                        for (AccessRule subAccessRule : mergedSubRules) {
                            if (!evaluateAccessRule(parsedRequestBody, subAccessRule)) {
                                logger.debug("Query Rejected by rule(2) {}", subAccessRule);
                                currentNode.setResult(false);
                                currentNode.setFailureReason("Sub-rule check failed");
                                return false;
                            }
                        }
                    }
                }
            } else {
                logger.debug("evaluateAccessRule() gates failed");
                currentNode.setResult(false);
                currentNode.setFailureReason("Gates evaluation failed");
                return false;
            }

            currentNode.setResult(true);
            return true;
        } finally {
            evaluationTreeStack.get().pop();
        }
    }

    public boolean extractAndCheckRule(AccessRule accessRule, Object parsedRequestBody) {
        String rule = accessRule.getRule();

        if (rule == null || rule.isEmpty())
            return true;

        rule = rule.stripLeading();

        Object requestBodyValue;
        int accessRuleType = accessRule.getType();

        try {
            logger.trace("extractAndCheckRule() -> JsonPath.parse().read() with parsedRequestBody - {} - {}", parsedRequestBody, rule);
            requestBodyValue = JsonPath.parse(parsedRequestBody).read(rule);

            if (accessRule.getCheckMapNode() != null && accessRule.getCheckMapNode()) {
                // Json parse will always return a list even when we want a map (to check keys)
                if (requestBodyValue instanceof JsonArray && ((JsonArray) requestBodyValue).size() == 1) {
                    requestBodyValue = ((JsonArray) requestBodyValue).get(0);
                }
            }
        } catch (PathNotFoundException ex) {
            if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY) {
                // We could return true directly, but we want to log the reason
                logger.debug("extractAndCheckRule() -> JsonPath.parse().read() PathNotFound;  passing rule {} for type {}", rule, accessRuleType);
                return true;
            }

            if (accessRuleType == AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY ||
                accessRuleType == AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY_IGNORE_CASE) {
                logger.debug("extractAndCheckRule() -> JsonPath.parse().read() PathNotFound;  passing rule {} for type {}", rule, accessRuleType);
                return true;
            }

            logger.info("extractAndCheckRule() -> JsonPath.parse().read() throws exception with parsedRequestBody - {} : {} - {}", parsedRequestBody, ex.getClass().getSimpleName(), ex.getMessage());

            // Record failure reason in the evaluation tree
            if (!evaluationTreeStack.get().isEmpty()) {
                AccessRuleEvaluationNode currentNode = evaluationTreeStack.get().peek();
                currentNode.setFailureReason("Path not found: " + rule + " - " + ex.getMessage());
            }

            return false;
        }

        if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY
            || accessRuleType == AccessRule.TypeNaming.IS_NOT_EMPTY) {
            if (requestBodyValue == null
                || (requestBodyValue instanceof String && ((String) requestBodyValue).isEmpty())
                || (requestBodyValue instanceof Collection && ((Collection) requestBodyValue).isEmpty())
                || (requestBodyValue instanceof Map && ((Map) requestBodyValue).isEmpty())) {
                boolean result = accessRuleType == AccessRule.TypeNaming.IS_EMPTY;
                if (!result && !evaluationTreeStack.get().isEmpty()) {
                    AccessRuleEvaluationNode currentNode = evaluationTreeStack.get().peek();
                    currentNode.setFailureReason("Expected empty path but found value: " + requestBodyValue);
                }
                return result;
            } else {
                boolean result = accessRuleType == AccessRule.TypeNaming.IS_NOT_EMPTY;
                if (!result && !evaluationTreeStack.get().isEmpty()) {
                    AccessRuleEvaluationNode currentNode = evaluationTreeStack.get().peek();
                    currentNode.setFailureReason("Expected non-empty path but found empty value");
                }
                return result;
            }
        }

        boolean result = evaluateNode(requestBodyValue, accessRule);
        if (!result && !evaluationTreeStack.get().isEmpty()) {
            AccessRuleEvaluationNode currentNode = evaluationTreeStack.get().peek();
            if (currentNode.getFailureReason() == null) {
                currentNode.setFailureReason("Rule evaluation failed for path: " + rule);
            }
        }
        return result;
    }

    private boolean evaluateNode(Object requestBodyValue, AccessRule accessRule) {
        logger.trace("evaluateNode() starting: {} :: {} :: {}", accessRule.getRule(), accessRule.getType(), accessRule.getMergedValues().isEmpty() ? accessRule.getValue() : ("Merged " + Arrays.deepToString(accessRule.getMergedValues().toArray())));
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
                    return switch (accessRule.getType()) {
                        case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE), (AccessRule.TypeNaming.ALL_EQUALS),
                             (AccessRule.TypeNaming.ALL_CONTAINS), (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE) ->
                                false;
                        default -> true;
                    };
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
        logger.debug("evaluateCollection()");
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
            logger.debug("decisionMaker No Merged values: {}, request body: {}, access rule: {}", value, requestBodyValue, accessRule);
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
        logger.debug("Checking {} in collection {}", requestBodyValue, Arrays.deepToString(accessRule.getMergedValues().toArray()));
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
                logger.info("Returning true for {} in collection {}", s, Arrays.deepToString(accessRule.getMergedValues().toArray()));
                break;
            }
        }
        return res;
    }

    private boolean _decisionMaker(AccessRule accessRule, String requestBodyValue, String value) {
        boolean decision = switch (accessRule.getType()) {
            case AccessRule.TypeNaming.NOT_CONTAINS -> !requestBodyValue.contains(value);
            case AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE ->
                    !requestBodyValue.toLowerCase().contains(value.toLowerCase());
            case (AccessRule.TypeNaming.NOT_EQUALS) -> !value.equals(requestBodyValue);
            case (AccessRule.TypeNaming.ANY_EQUALS), (AccessRule.TypeNaming.ALL_EQUALS) ->
                    value.equals(requestBodyValue);
            case (AccessRule.TypeNaming.ALL_CONTAINS), (AccessRule.TypeNaming.ANY_CONTAINS),
                 (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY) -> requestBodyValue.contains(value);
            case (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE),
                 (AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY_IGNORE_CASE) ->
                    requestBodyValue.toLowerCase().contains(value.toLowerCase());
            case (AccessRule.TypeNaming.NOT_EQUALS_IGNORE_CASE) -> !value.equalsIgnoreCase(requestBodyValue);
            case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE) -> value.equalsIgnoreCase(requestBodyValue);
            case (AccessRule.TypeNaming.ALL_REG_MATCH), (AccessRule.TypeNaming.ANY_REG_MATCH) ->
                    requestBodyValue.matches(value);
            default -> {
                logger.warn("evaluateAccessRule() incoming accessRule type is out of scope. Just return true.");
                yield true;
            }
        };

        if (decision) {
            logger.info("_decisionMaker() returning true for request body: {} access rule: {} value: {}", requestBodyValue, accessRule, value);
        }

        return decision;
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
        ar.setGates(new HashSet<>(getGates(true, false, false)));

        addUniqueSubRules(ar, getAllowedQueryTypeRules());
        addUniqueSubRules(ar, getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
        addUniqueSubRules(ar, getTopmedRestrictedSubRules());
    }


    /**
     * Configures the harmonized AccessRule with gates and sub-rules.
     *
     * @param ar              The AccessRule to configure.
     * @param studyIdentifier The study identifier.
     * @param conceptPath     The concept path.
     * @param projectAlias    The project alias.
     */
    protected void configureHarmonizedAccessRule(AccessRule ar, String studyIdentifier, String conceptPath, String projectAlias) {
        ar.setGates(new HashSet<>(Collections.singleton(upsertConsentGate("HARMONIZED_CONSENT", consentValuesPath(fence_harmonized_consent_group_concept_path), true, "harmonized data"))));

        addUniqueSubRules(ar, getAllowedQueryTypeRules());
        addUniqueSubRules(ar, getHarmonizedSubRules());
        addUniqueSubRules(ar, getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
    }

    protected AccessRule configureClinicalAccessRuleWithPhenoSubRule(AccessRule ar, String studyIdentifier, String consent_group, String conceptPath, String projectAlias) {
        ar.setGates(new HashSet<>(getGates(true, false, true)));

        addUniqueSubRules(ar, getAllowedQueryTypeRules());
        addUniqueSubRules(ar, getPhenotypeSubRules(studyIdentifier, conceptPath, projectAlias));
        addUniqueSubRules(ar, Collections.singleton(createTopmedConsentAllowanceSubRule()));

        return ar;
    }

    /**
     * The sub-rule that lets a query filter on the Topmed consent concept path itself. Shared with PrivilegeService's topmed+parent rule,
     * which attaches the identical rule.
     */
    protected AccessRule createTopmedConsentAllowanceSubRule() {
        return createPhenotypeSubRule(
                fence_topmed_consent_group_concept_path, "ALLOW_TOPMED_CONSENT", FILTER_CONCEPT_PATHS_PATH,
                AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, ""
        );
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
                    EXPECTED_RESULT_TYPE_PATH,
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
        rules.add(upsertTopmedRestrictedSubRule("CATEGORICAL", GENOMIC_FILTERS_PATH));
        rules.add(upsertTopmedRestrictedSubRule("NUMERIC", GENOMIC_FILTERS_PATH));

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
            logger.trace("Found existing rule: {}", ar.getName());
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
        // On the envelope wire the consent groups rode in categoryFilters, so that node was never empty and this rule could be a plain
        // ALL_CONTAINS. On the bare v3 wire consents live in authorizationFilters instead: a query may legitimately carry no phenotypic
        // filter at all, and ALL_CONTAINS denies an empty match. ALL_CONTAINS_OR_EMPTY keeps the check ("every categorical filter the
        // query does carry is under an allowed concept path") without denying a query that carries none.
        rules.add(createPhenotypeSubRule(fence_parent_consent_group_concept_path, "ALLOW_PARENT_CONSENT", FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, ""));

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, SELECT_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "CATEGORICAL"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, REQUIRED_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, ANY_RECORD_OF_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "ANY_RECORD_OF"));
        }

        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "CATEGORICAL"));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "NUMERIC"));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, SELECT_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS"));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, REQUIRED_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQUIRED_FIELDS"));
        rules.add(createPhenotypeSubRule(conceptPath, alias + "_" + studyIdentifier, ANY_RECORD_OF_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "ANY_RECORD_OF"));

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
        // ALL_CONTAINS_OR_EMPTY rather than ALL_CONTAINS for the same reason as getPhenotypeSubRules: on the bare v3 wire the consent
        // groups no longer ride in the phenotypic filters, so an empty filter set is legitimate rather than impossible.
        rules.add(createPhenotypeSubRule(fence_parent_consent_group_concept_path, "ALLOW_PARENT_CONSENT", FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, ""));
        rules.add(createPhenotypeSubRule(fence_harmonized_consent_group_concept_path, "ALLOW_HARMONIZED_CONSENT", FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, ""));
        rules.add(createTopmedConsentAllowanceSubRule());

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, SELECT_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "CATEGORICAL"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, REQUIRED_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, ANY_RECORD_OF_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "ANY_RECORD_OF"));
        }

        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "CATEGORICAL"));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "NUMERIC"));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", SELECT_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS"));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", REQUIRED_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQUIRED_FIELDS"));
        rules.add(createPhenotypeSubRule(fence_harmonized_concept_path, "HARMONIZED", ANY_RECORD_OF_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "ANY_RECORD_OF"));

        return rules;
    }


    /**
     * generate and return a set of rules that disallow access to phenotype data (only genomic filters allowed)
     *
     * @return
     */
    protected Collection<? extends AccessRule> getPhenotypeRestrictedSubRules(String studyIdentifier, String consentCode, String alias) {
        Set<AccessRule> rules = new HashSet<AccessRule>();
        // ALL_CONTAINS_OR_EMPTY rather than ALL_CONTAINS: see getPhenotypeSubRules.
        rules.add(createTopmedConsentAllowanceSubRule());

        for (String underscorePath : underscoreFields) {
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, SELECT_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "FIELDS"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, FILTER_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "CATEGORICAL"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, REQUIRED_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "REQ_FIELDS"));
            rules.add(createPhenotypeSubRule(underscorePath, "ALLOW " + underscorePath, ANY_RECORD_OF_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY, "ANY_RECORD_OF"));
        }

        // The envelope-era DISALLOW_NUMERIC rule (numericFilters IS_EMPTY) has no v3 counterpart that is not self-contradictory: v3 has one
        // FILTER node for both categorical and numeric filters, so an IS_EMPTY rule on it would also deny the topmed-consent filter this
        // very rule set explicitly allows above -- the privilege would deny everything. The concern it covered is already carried by the
        // ALL_CONTAINS_OR_EMPTY rules above, which confine EVERY filter concept path (numeric included) to the underscore paths and the
        // topmed consent path. Only the required-filter denial, which v3 can still address on its own, survives.
        rules.add(createPhenotypeSubRule(null, alias + "_" + studyIdentifier + "_" + consentCode, REQUIRED_CONCEPT_PATHS_PATH, AccessRule.TypeNaming.IS_EMPTY, "DISALLOW_REQUIRED_FIELDS"));

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
        gates.add(upsertConsentGate("PARENT_CONSENT", consentValuesPath(fence_parent_consent_group_concept_path), parent, "parent study data"));
        gates.add(upsertConsentGate("HARMONIZED_CONSENT", consentValuesPath(fence_harmonized_consent_group_concept_path), harmonized, "harmonized data"));
        gates.add(upsertConsentGate("TOPMED_CONSENT", consentValuesPath(fence_topmed_consent_group_concept_path), topmed, "Topmed data"));

        return gates;
    }

    protected AccessRule populateTopmedAccessRule(AccessRule rule, boolean includeParent) {
        rule.setGates(new HashSet<>(getGates(includeParent, false, true)));
        addUniqueSubRules(rule, getAllowedQueryTypeRules());

        return rule;
    }

    protected AccessRule populateHarmonizedAccessRule(AccessRule rule, String parentConceptPath, String studyIdentifier, String projectAlias) {
        rule.setGates(new HashSet<>(Collections.singletonList(
                upsertConsentGate("HARMONIZED_CONSENT", consentValuesPath(fence_harmonized_consent_group_concept_path), true, "harmonized data")
        )));

        addUniqueSubRules(rule, getAllowedQueryTypeRules());
        addUniqueSubRules(rule, getHarmonizedSubRules());
        addUniqueSubRules(rule, getPhenotypeSubRules(studyIdentifier, parentConceptPath, projectAlias));

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
        String ruleText = consentValuesPath(consent_path);
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

        String ruleText = consentValuesPath(fence_topmed_consent_group_concept_path);
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
        logger.trace("upsertHarmonizedAccessRule() Creating new access rule {}", ar_name);
        String description = "MANAGED AR for " + project_name + "." + consent_group + " Topmed data";
        String ruleText = consentValuesPath(fence_harmonized_consent_group_concept_path);
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

    /**
     * Every phenotype sub-rule now binds a v3 path that resolves to a plain list of concept-path strings, so none of them needs the
     * checkMapKeyOnly/checkMapNode pair that walking the envelope-era {@code categoryFilters} MAP required.
     */
    protected AccessRule createPhenotypeSubRule(String conceptPath, String alias, String rule, int ruleType, String label) {
        String ar_name = "AR_PHENO_" + alias + "_" + label;
        logger.trace("createPhenotypeSubRule() Creating new access rule {}", ar_name);

        return getOrCreateAccessRule(
                ar_name,
                "MANAGED SUB AR for " + alias + " " + label + " clinical concepts",
                rule,
                ruleType,
                ruleType == AccessRule.TypeNaming.IS_NOT_EMPTY ? null : normalizeConceptPath(conceptPath),
                false,
                false,
                false,
                false
        );
    }

    /**
     * The concept paths on the bare v3 wire carry SINGLE backslashes ({@code \_consents\}). Configuration and constants in this service are
     * written both ways, so normalize a doubled path down to the wire form before it is used as a rule VALUE.
     */
    private static String normalizeConceptPath(String conceptPath) {
        return conceptPath == null ? null : conceptPath.replace("\\\\", "\\");
    }

    /**
     * The JsonPath that reads the consent values a query carries in ONE consent bucket of the bare v3 wire. Envelope-era this was
     * {@code $.query.query.categoryFilters.<consent concept path>[*]} -- the consent groups were a client-supplied categorical filter, and
     * the concept path was spliced in as a JsonPath KEY. On the v3 wire consents are an {@code authorizationFilters} entry, so the concept
     * path is a VALUE to match instead, and it has to be escaped for a JsonPath filter literal: a lone backslash in
     * {@code == '\_consents\'} escapes the closing quote and the whole expression fails to compile.
     */
    static String consentValuesPath(String consentConceptPath) {
        String wirePath = normalizeConceptPath(consentConceptPath == null ? "" : consentConceptPath);
        return "$.query.authorizationFilters[?(@.conceptPath == '" + wirePath.replace("\\", "\\\\") + "')].values[*]";
    }

    protected AccessRule getOrCreateAccessRule(String name, String description, String rule, int type, String value, boolean checkMapKeyOnly, boolean checkMapNode, boolean evaluateOnlyByGates, boolean gateAnyRelation) {
        return accessRuleCache.computeIfAbsent(name, key -> {
            AccessRule ar = this.getAccessRuleByName(key);
            if (ar == null) {
                logger.trace("Creating new access rule {}", key);
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

    public List<AccessRule> getAccessRulesByPrivilegeIds(List<UUID> privilegeIds) {
        return this.accessRuleRepo.getAccessRulesByPrivilegeIds(privilegeIds);
    }

    /**
     * Adds unique sub-rules to the provided parent access rule. This method ensures that duplicate sub-rules,
     * based on their names, are not added to the parent access rule.
     *
     * @param accessRule    the parent access rule to which the sub-rules are added
     * @param subRulesToAdd the collection of sub-rules to be added to the parent access rule
     */
    private void addUniqueSubRules(AccessRule accessRule, Collection<? extends AccessRule> subRulesToAdd) {
        if (accessRule.getSubAccessRule() == null) {
            accessRule.setSubAccessRule(new HashSet<>());
        }

        Set<String> existingRuleNames = accessRule.getSubAccessRule().stream()
                .map(AccessRule::getName)
                .collect(Collectors.toSet());

        for (AccessRule subRule : subRulesToAdd) {
            if (!existingRuleNames.contains(subRule.getName())) {
                accessRule.getSubAccessRule().add(subRule);
                existingRuleNames.add(subRule.getName());
            }
        }
    }

}
