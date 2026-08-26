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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AccessRuleService {

    private final Logger logger = LoggerFactory.getLogger(AccessRuleService.class);

    private final AccessRuleRepository accessRuleRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Set<AccessRule> standardAccessRules;

    private final String fence_standard_access_rules;

    private final ThreadLocal<Stack<AccessRuleEvaluationNode>> evaluationTreeStack = ThreadLocal.withInitial(Stack::new);
    private final ThreadLocal<AccessRuleEvaluationNode> rootNode = new ThreadLocal<>();

    @Autowired
    public AccessRuleService(
        AccessRuleRepository accessRuleRepo, @Value("${fence.standard.access.rules}") String fenceStandardAccessRules
    ) {
        this.accessRuleRepo = accessRuleRepo;
        this.fence_standard_access_rules = fenceStandardAccessRules;
        logger.info("fence_standard_access_rules: {}", fenceStandardAccessRules);
    }

    public Optional<AccessRule> getAccessRuleById(String accessRuleId) {
        return accessRuleRepo.findById(UUID.fromString(accessRuleId));
    }

    public List<AccessRule> getAllAccessRules() {
        return accessRuleRepo.findAll();
    }

    public List<AccessRule> addAccessRule(List<AccessRule> accessRules) {
        accessRules.forEach(accessRule -> {
            if (accessRule.getEvaluateOnlyByGates() == null) accessRule.setEvaluateOnlyByGates(false);

            if (accessRule.getCheckMapKeyOnly() == null) accessRule.setCheckMapKeyOnly(false);

            if (accessRule.getCheckMapNode() == null) accessRule.setCheckMapNode(false);

            if (accessRule.getGateAnyRelation() == null) accessRule.setGateAnyRelation(false);
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
        return this.accessRuleRepo.save(accessRule);
    }

    /**
     * Prints the evaluation tree for the most recently evaluated access rule. This method should be called after evaluateAccessRule() has
     * been called.
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
     * Clears the evaluation tree data. This should be called after processing is complete to prevent memory leaks.
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
            Boolean checkMapKeyOnly = accessRule.getCheckMapKeyOnly(), checkMapNode = accessRule.getCheckMapNode(),
                evaluateOnlyByGates = accessRule.getEvaluateOnlyByGates(), gateAnyRelation = accessRule.getGateAnyRelation();

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
        String ruleName = accessRule.getMergedName().isEmpty() ? accessRule.getName() : accessRule.getMergedName();

        boolean isGate = !evaluationTreeStack.get().isEmpty() && evaluationTreeStack.get().peek().getRule().getGates() != null
            && evaluationTreeStack.get().peek().getRule().getGates().contains(accessRule);
        boolean isSubRule = !evaluationTreeStack.get().isEmpty() && evaluationTreeStack.get().peek().getRule().getSubAccessRule() != null
            && evaluationTreeStack.get().peek().getRule().getSubAccessRule().contains(accessRule);
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
                    logger.debug("Query Rejected by rule(1) {}, with request body {}", accessRule, parsedRequestBody);
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

        if (rule == null || rule.isEmpty()) return true;

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
                logger.debug(
                    "extractAndCheckRule() -> JsonPath.parse().read() PathNotFound;  passing rule {} for type {}", rule, accessRuleType
                );
                return true;
            }

            if (
                accessRuleType == AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY
                    || accessRuleType == AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY_IGNORE_CASE
            ) {
                logger.debug(
                    "extractAndCheckRule() -> JsonPath.parse().read() PathNotFound;  passing rule {} for type {}", rule, accessRuleType
                );
                return true;
            }

            logger.info(
                "extractAndCheckRule() -> JsonPath.parse().read() throws exception with parsedRequestBody - {} : {} - {}",
                parsedRequestBody, ex.getClass().getSimpleName(), ex.getMessage()
            );

            // Record failure reason in the evaluation tree
            if (!evaluationTreeStack.get().isEmpty()) {
                AccessRuleEvaluationNode currentNode = evaluationTreeStack.get().peek();
                currentNode.setFailureReason("Path not found: " + rule + " - " + ex.getMessage());
            }

            return false;
        }

        if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY || accessRuleType == AccessRule.TypeNaming.IS_NOT_EMPTY) {
            if (
                requestBodyValue == null || (requestBodyValue instanceof String && ((String) requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Collection && ((Collection) requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Map && ((Map) requestBodyValue).isEmpty())
            ) {
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
        logger.trace(
            "evaluateNode() starting: {} :: {} :: {}", accessRule.getRule(), accessRule.getType(),
            accessRule.getMergedValues().isEmpty() ? accessRule.getValue()
                : ("Merged " + Arrays.deepToString(accessRule.getMergedValues().toArray()))
        );
        logger.trace(
            "evaluateNode() requestBody {}  {}", requestBodyValue.getClass().getName(),
            requestBodyValue instanceof Collection ? Arrays.deepToString(((Collection) requestBodyValue).toArray())
                : requestBodyValue.toString()
        );

        if (requestBodyValue instanceof String value) {
            return decisionMaker(accessRule, value);
        }
        if (requestBodyValue instanceof Collection collection) {
            return evaluateCollection(collection, accessRule);
        }
        if (requestBodyValue instanceof Map && accessRule.getCheckMapNode() != null && accessRule.getCheckMapNode()) {
            return evaluateMap(requestBodyValue, accessRule);
        }
        return true;
    }

    private boolean evaluateMap(Object requestBodyValue, AccessRule accessRule) {
        logger.trace("evaluateMap() access rule:{}", accessRule.getName());
        logger.trace("evaluateMap() request body value:{}", requestBodyValue);

        switch (accessRule.getType()) {
            case (AccessRule.TypeNaming.ANY_EQUALS):
            case (AccessRule.TypeNaming.ANY_CONTAINS):
            case (AccessRule.TypeNaming.ANY_REG_MATCH):
                for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()) {
                    if (decisionMaker(accessRule, (String) entry.getKey())) return true;

                    if (
                        (accessRule.getCheckMapKeyOnly() == null || !accessRule.getCheckMapKeyOnly())
                            && evaluateNode(entry.getValue(), accessRule)
                    ) return true;
                }
                return false;
            default:
                if (((Map) requestBodyValue).isEmpty()) {
                    return switch (accessRule.getType()) {
                        case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE), (AccessRule.TypeNaming.ALL_EQUALS), (AccessRule.TypeNaming.ALL_CONTAINS), (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE) -> false;
                        default -> true;
                    };
                }
                for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()) {
                    if (!decisionMaker(accessRule, (String) entry.getKey())) return false;

                    if (
                        (accessRule.getCheckMapKeyOnly() == null || !accessRule.getCheckMapKeyOnly())
                            && !evaluateNode(entry.getValue(), accessRule)
                    ) return false;
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
                        if (!evaluateNode(item, accessRule)) return false;
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
        boolean decision;
        switch (accessRule.getType()) {
            case AccessRule.TypeNaming.NOT_CONTAINS:
                decision = !requestBodyValue.contains(value);
                break;
            case AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE:
                decision = !requestBodyValue.toLowerCase().contains(value.toLowerCase());
                break;
            case AccessRule.TypeNaming.NOT_EQUALS:
                decision = !value.equals(requestBodyValue);
                break;
            case AccessRule.TypeNaming.ANY_EQUALS:
            case AccessRule.TypeNaming.ALL_EQUALS:
                decision = value.equals(requestBodyValue);
                break;
            case AccessRule.TypeNaming.ALL_CONTAINS:
            case AccessRule.TypeNaming.ANY_CONTAINS:
            case AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY:
                decision = requestBodyValue.contains(value);
                break;
            case AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE:
            case AccessRule.TypeNaming.ALL_CONTAINS_OR_EMPTY_IGNORE_CASE:
                decision = requestBodyValue.toLowerCase().contains(value.toLowerCase());
                break;
            case AccessRule.TypeNaming.NOT_EQUALS_IGNORE_CASE:
                decision = !value.equalsIgnoreCase(requestBodyValue);
                break;
            case AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE:
                decision = value.equalsIgnoreCase(requestBodyValue);
                break;
            case AccessRule.TypeNaming.ALL_REG_MATCH:
            case AccessRule.TypeNaming.ANY_REG_MATCH:
                decision = requestBodyValue.matches(value);
                break;
            default:
                logger.warn("evaluateAccessRule() incoming accessRule type is out of scope. Just return true.");
                decision = true;
        }

        if (decision) {
            logger.info(
                "_decisionMaker() returning true for request body: {} access rule: {} value: {}", requestBodyValue, accessRule, value
            );
        }

        return decision;
    }

    /**
     * The set of standard access rules that are added to all privileges. This set is cached to avoid loading the rules multiple times.
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

    public List<AccessRule> getAccessRulesByPrivilegeIds(List<UUID> privilegeIds) {
        return this.accessRuleRepo.getAccessRulesByPrivilegeIds(privilegeIds);
    }

}
