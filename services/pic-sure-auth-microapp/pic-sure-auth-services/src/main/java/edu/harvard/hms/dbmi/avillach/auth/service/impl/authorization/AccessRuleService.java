package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.mysql.cj.xdevapi.JsonArray;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AccessRuleService {

    private final AccessRuleRepository accessRuleRepo;
    private final Logger logger = LoggerFactory.getLogger(AccessRuleService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AccessRuleService(AccessRuleRepository accessRuleRepo) {
        this.accessRuleRepo = accessRuleRepo;
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
        return this.accessRuleRepo.save(accessRule);
    }

    public AccessRule getAccessRuleByName(String arName) {
        return this.accessRuleRepo.findByName(arName);
    }

    @Cacheable(value = "mergedRulesCache", key = "#user.getEmail()")
    public Set<AccessRule> getAccessRulesForUserAndApp(User user, Application application) {
        try {
            Set<Privilege> privileges = user.getPrivilegesByApplication(application);

            if (privileges == null || privileges.isEmpty()) {
                return null;
            }

            Set<AccessRule> detachedMergedRules = new HashSet<>();
            for (AccessRule rule : preProcessAccessRules(privileges)) {
                detachedMergedRules.add(objectMapper.readValue(objectMapper.writeValueAsString(rule), AccessRule.class));
            }

            return detachedMergedRules;
        } catch (Exception e) {
            logger.error("Error populating or retrieving data from cache: ", e);
        }

        return null;
    }

    @CacheEvict(value = "mergedRulesCache", key = "#user.getEmail()")
    public void evictFromCache(User user) {
        // This method is used to clear the cache for a user when their privileges are updated
    }

    public Set<AccessRule> preProcessAccessRules(Set<Privilege> privileges) {
        Set<AccessRule> accessRules = new HashSet<>();
        for (Privilege privilege : privileges) {
            accessRules.addAll(privilege.getAccessRules());
        }

        return preProcessARBySortedKeys(accessRules);
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
        logger.debug("evaluateAccessRule() starting with: {}", parsedRequestBody);
        logger.debug("evaluateAccessRule() access rule: {}", accessRule.getName());

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
                        logger.error("evaluateAccessRule() gate {} failed: {} ____ {}", gate.getName(), gate.getRule(), gate.getValue());
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
                    for (AccessRule subAccessRule : accessRule.getSubAccessRule()) {
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

        Object requestBodyValue;
        int accessRuleType = accessRule.getType();

        try {
            requestBodyValue = JsonPath.parse(parsedRequestBody).read(rule);

            // Json parse will always return a list even when we want a map (to check keys)
            if (requestBodyValue instanceof JsonArray && ((JsonArray) requestBodyValue).size() == 1) {
                requestBodyValue = ((JsonArray) requestBodyValue).get(0);
            }

        } catch (PathNotFoundException ex) {
            if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY) {
                // We could return accessRuleType == AccessRule.TypeNaming.IS_EMPTY directly, but we want to log the reason
                logger.debug("extractAndCheckRule() -> JsonPath.parse().read() PathNotFound;  passing IS_EMPTY rule");
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
        logger.debug("_decisionMaker() starting");
        logger.debug("_decisionMaker() access rule:{}", accessRule.getName());
        logger.debug(requestBodyValue);
        logger.debug(value);

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
}
