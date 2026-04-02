package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.model.EvaluateAccessRuleResult;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.rest.TokenController;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import io.micrometer.common.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService.*;

/**
 * This class handles authorization activities in the project. It decides
 * if a user can send a request to certain applications based on
 * what endpoint they are trying to hit and the content of the request body (in HTTP POST method).
 * <h3>Thoughts on design:</h3>
 * The core technology used here is jsonpath.
 * In the {@link TokenController#inspectToken(Map)} class, other registered applications
 * can hit the tokenIntrospection endpoint with a token they want PSAMA to introspect along
 * with the URL the token holder is trying to hit and what data this token holder is trying to send. After
 * checking if the token is valid or not, the authorization check in this class will start.
 * <br><br>
 * <p>
 * Whether users are allowed access or not depends on their privileges, which depends on
 * the accessRules underneath. AuthorizationService class will eventually use jsonpath to check if
 * certain places in the incoming JSON meet the requirement of the preset rules in accessRules to determine
 * if the token holder is authorized or not.
 * </p>
 */
@Service
public class AuthorizationService {

    private final Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

    protected AccessRuleService accessRuleService;
    protected SessionService sessionService;
    private final RoleService roleService;

    private final ConsentBasedAccessRuleEvaluator consentBasedAccessRuleEvaluator;

    /**
     * Applications that have strict access control. If the application is strict a user must have both privileges and access rules.
     * If the application is not strict, the user only needs privileges. Access rules are optional.
     */
    private final Set<String> strictConnections = new HashSet<>();


    private final UserConsentsRepository userConsentsRepository;

    @Autowired
    public AuthorizationService(AccessRuleService accessRuleService,
                                SessionService sessionService,
                                RoleService roleService,
                                ConsentBasedAccessRuleEvaluator consentBasedAccessRuleEvaluator,
                                @Value("${strict.authorization.applications.connections}") String strictConnections,
                                UserConsentsRepository userConsentsRepository) {
        this.accessRuleService = accessRuleService;
        this.sessionService = sessionService;
        this.roleService = roleService;
        this.consentBasedAccessRuleEvaluator = consentBasedAccessRuleEvaluator;
        if (strictConnections != null && !strictConnections.isEmpty()) {
            this.strictConnections.addAll(Arrays.asList(strictConnections.split(",")));
        }
        this.userConsentsRepository = userConsentsRepository;
    }

    /**
     * Checking based on AccessRule in Privilege
     * <br><br>
     * Thoughts on design:
     * <br>
     * <br>
     * We have three layers here: role, privilege, accessRule.
     * <br>
     * A role might have multiple privileges, a privilege might have multiple accessRules.
     * <br>
     * Currently, we retrieve all accessRules together. Between AccessRules, they should be OR relationship, which means
     * roles and privileges are OR relationship, pass one, and you are good.
     * <br>
     * <br>
     * Inside each accessRule, there are  subAccessRules and Gates.
     * <br>
     * Only if all gates are applied will the accessRule be checked.
     * <br>
     * The accessRule and subAccessRules are an AND relationship.
     *
     * @param application
     * @param requestBody
     * @param isLongTermToken
     * @return
     * @see Privilege
     * @see AccessRule
     */
    public EvaluateAccessRuleResult isAuthorized(Application application, Object requestBody, User user, boolean isLongTermToken) {
        String applicationName = application.getName();
        String resourceId = "null";
        String targetService = "null";

        if (user == null) {
            logger.error("isAuthorized() User cannot be null");
            return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
        }

        if (StringUtils.isBlank(user.getSubject())) {
            logger.error("isAuthorized() Subject cannot be blank {}", user.getSubject());
            return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
        }

        if (!isLongTermToken && sessionService.isSessionExpired(user.getSubject())) {
            logger.error("isAuthorized() Session expired {}", user.getSubject());
            return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
        }

        //in some cases, we don't go through the evaluation
        if (requestBody == null) {
            logger.debug("ACCESS_LOG ___ {},{},{} ___ has been granted access to application ___ {} ___ NO REQUEST BODY FORWARDED BY APPLICATION", user.getUuid().toString(), user.getEmail(), user.getName(), applicationName);
            return new EvaluateAccessRuleResult(true, Set.of(), null, Optional.empty());
        }

        try {
            Map requestBodyMap = (Map) requestBody;
            Map queryMap = (Map) requestBodyMap.get("query");
            resourceId = (String) queryMap.get("resourceUUID");
            targetService = (String) queryMap.get("Target Service");
        } catch (RuntimeException e) {
            logger.debug("Error parsing resource and target service from request body.");
        }

        String formattedQuery;
        try {
            formattedQuery = (String) ((Map) requestBody).get("formattedQuery");

            if (formattedQuery == null) {
                //fallback in case no formatted query info present
                formattedQuery = new ObjectMapper().writeValueAsString(requestBody);
            }

        } catch (ClassCastException | JsonProcessingException e1) {
            logger.debug("ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} ___ UNABLE TO PARSE REQUEST", user.getUuid().toString(), user.getEmail(), user.getName(), requestBody, applicationName);
            logger.debug("isAuthorized() Stack Trace: ", e1);
            return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
        }

        Set<AccessRule> accessRules;
        String label = "";
        if (user.getConnection() != null) {
            // Open Access doesn't currently use a connection
            label = user.getConnection().getLabel();
        }

        if (this.strictConnections.contains(label)) {
            accessRules = this.accessRuleService.getAccessRulesForUserAndApp(user, application);
            if (accessRules.isEmpty()) {
                logger.info("ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED", user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName);
                return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
            }
        } else {
            Set<Privilege> privileges = user.getPrivilegesByApplication(application);
            // List all privileges of the user
            logger.info("ACCESS_LOG ___ {},{},{} ___ has the following privileges: {}", user.getUuid().toString(), user.getEmail(), user.getName(), privileges.stream().map(Privilege::getName).collect(Collectors.joining(", ")));
            if (privileges == null || privileges.isEmpty()) {
                logger.info("ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} __ USER HAS NO PRIVILEGES ASSOCIATED TO THE APPLICATION, BUT APPLICATION HAS PRIVILEGES", user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName);
                return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
            }

            accessRules = this.accessRuleService.cachedPreProcessAccessRules(user, privileges);
            if (accessRules.isEmpty()) {
                logger.info("ACCESS_LOG ___ {},{},{} ___ has been granted access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED", user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName);
                return new EvaluateAccessRuleResult(true, Set.of(), null, Optional.empty());
            }
        }

        logger.info("ACCESS_LOG ___ {},{},{} ___ has the following access rules: {}", user.getUuid().toString(), user.getEmail(), user.getName(), accessRules.stream().map(AccessRule::toString).collect(Collectors.joining(", ")));

        EvaluateAccessRuleResult evaluationResult = passesAccessRuleEvaluation(requestBody, accessRules, user);
        boolean result = evaluationResult.result();
        String passRuleName = evaluationResult.passRuleName();
        Set<AccessRule> failedRules = evaluationResult.failedRules();

        logger.info("ACCESS_LOG ___ {},{},{} ___ has been {} access to execute query ___ {} ___ in application ___ {} ___ {}", user.getUuid().toString(), user.getEmail(), user.getName(), (result ? "granted" : "denied"), formattedQuery, applicationName, (result ? "passed by " + passRuleName : "failed by rules: ["
                + failedRules.stream()
                .map(ar -> (ar.getMergedName().isEmpty() ? ar.getName() : ar.getMergedName()))
                .collect(Collectors.joining(", ")) + "]"));

        return evaluationResult;
    }

    private EvaluateAccessRuleResult passesAccessRuleEvaluation(Object requestBody, Set<AccessRule> accessRules, User user) {
        // Current logic here is: among all accessRules, they are OR relationship
        Set<AccessRule> failedRules = new HashSet<>();
        AccessRule passByRule = null;
        boolean result = false;
        Query returnQuery = null;

        for (AccessRule accessRule : accessRules) {
            try {
                if (AccessRule.TypeNaming.USER_CONSENT_ACCESS == accessRule.getType()) {
                    UserConsents userConsents = userConsentsRepository.findByUserId(user.getUuid());

                    // This is an HPDS query inside a PIC-SURE query
                    Map queryMap  = (Map) ((Map) requestBody).get("query");
                    Object queryObject = queryMap.get("query");
                    Query query;

                    if (queryObject instanceof String) {
                        query = new ObjectMapper().readValue((String) queryObject, Query.class);
                    } else {
                        query = new ObjectMapper().convertValue(queryObject, Query.class);
                    }

                    if (consentBasedAccessRuleEvaluator.evaluateAccessRule(query, accessRule, userConsents)) {
                        result = true;
                        passByRule = accessRule;

                        returnQuery = consentBasedAccessRuleEvaluator.setAuthorizationFiltersForQuery(userConsents, query);
                        break;
                    } else {
                        failedRules.add(accessRule);
                    }
                }
                else {
                    String targetService = (String) ((Map) requestBody).get("Target Service");
                    logger.debug("Target service = " + targetService);
                    if (targetService != null && targetService.startsWith("/v3")) {
                        logger.debug("Skipping access rule {}", accessRule.getName());
                    }
                    else if (this.accessRuleService.evaluateAccessRule(requestBody, accessRule)) {
                        result = true;
                        passByRule = accessRule;
                        break;
                    } else {
                        failedRules.add(accessRule);
                        // Print the evaluation tree when a rule fails
                        if (logger.isInfoEnabled()) {
                            String ruleName = accessRule.getMergedName().isEmpty() ?
                                    accessRule.getName() :
                                    accessRule.getMergedName();
                            logger.info("Rule evaluation tree for failed rule {}:\n{}",
                                    ruleName,
                                    this.accessRuleService.printEvaluationTree());
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            } finally {
                // Clear the evaluation tree to prevent memory leaks
                this.accessRuleService.clearEvaluationTree();
            }
        }

        String passRuleName = null;
        if (passByRule != null) {
            if (passByRule.getMergedName().isEmpty())
                passRuleName = passByRule.getName();
            else
                passRuleName = passByRule.getMergedName();
        }

        return new EvaluateAccessRuleResult(result, failedRules, passRuleName, Optional.ofNullable(returnQuery));
    }


    public boolean openAccessRequestIsValid(Map<String, Object> inputMap) {

        if (inputMap == null || inputMap.isEmpty()) {
            logger.info("ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been denied access to application ___ NO REQUEST BODY FORWARDED BY APPLICATION");
            return true;
        }

        Object requestBody = inputMap.get("request");
        // If there is no request body, we can assume the request is valid
        if (requestBody == null) {
            logger.info("ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been granted access to application ___ NO REQUEST BODY FORWARDED BY APPLICATION");
            return true;
        }

        // Load the auth access rules
        Role authAccessRole = this.roleService.getRoleByName(MANAGED_AUTH_ACCESS_ROLE_NAME);
        if (authAccessRole == null) {
            logger.info("{} has not be created for this environment. Please create the role and its permissions before attempting to use auth access.", MANAGED_AUTH_ACCESS_ROLE_NAME);
            return false;
        }

        // Load the open access rules
        Role openAccessRole = this.roleService.getRoleByName(MANAGED_OPEN_ACCESS_ROLE_NAME);
        if (openAccessRole == null) {
            logger.info("{} has not be created for this environment. Please create the role and its permissions before attempting to use open access.", MANAGED_OPEN_ACCESS_ROLE_NAME);
            return false;
        }

        Set<AccessRule> allOpenAccessRules = openAccessRole.getPrivileges().stream()
                .map(Privilege::getAccessRules).collect(Collectors.toSet()).stream().flatMap(Collection::stream).collect(Collectors.toSet());

        boolean result = false;
        if (allOpenAccessRules.isEmpty()) {
            result = true;
            logger.info("ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been granted access to application ___ NO ACCESS RULES EVALUATED");
        } else {
            EvaluateAccessRuleResult evaluationResult = passesAccessRuleEvaluation(requestBody, allOpenAccessRules, null);
            result = evaluationResult.result();
            String passRuleName = evaluationResult.passRuleName();
            Set<AccessRule> failedRules = evaluationResult.failedRules();
            logger.info("ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been {} access to execute query ___ {} ___ in application ___ OPEN ACCESS ___ {}", (result ? "granted" : "denied"), requestBody, (result ? "passed by " + passRuleName : "failed by rules: ["
                                                                                                                                                                                                                                             + failedRules.stream()
                                                                                                                                                                                                                                                     .map(ar -> (ar.getMergedName().isEmpty() ? ar.getName() : ar.getMergedName()))
                                                                                                                                                                                                                                                     .collect(Collectors.joining(", ")) + "]"));
        }

        return result;
    }
}
