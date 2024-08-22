package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.rest.TokenController;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import io.micrometer.common.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * Applications that have strict access control. If the application is strict a user must have both privileges and access rules.
     * If the application is not strict, the user only needs privileges. Access rules are optional.
     */
    private final Set<String> strictConnections = new HashSet<>();

    @Autowired
    public AuthorizationService(AccessRuleService accessRuleService,
                                SessionService sessionService,
                                @Value("${strict.authorization.applications.connections}") String strictConnections) {
        this.accessRuleService = accessRuleService;
        this.sessionService = sessionService;
        if (strictConnections != null && !strictConnections.isEmpty()) {
            this.strictConnections.addAll(Arrays.asList(strictConnections.split(",")));
        }
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
     * @return
     * @see Privilege
     * @see AccessRule
     */
    public boolean isAuthorized(Application application, Object requestBody, User user) {
        String applicationName = application.getName();
        String resourceId = "null";
        String targetService = "null";

        if (user == null) {
            logger.error("isAuthorized() User cannot be null");
            return false;
        }

        if (StringUtils.isBlank(user.getSubject())) {
            logger.error("isAuthorized() Subject cannot be blank {}", user.getSubject());
            return false;
        }

        if (sessionService.isSessionExpired(user.getSubject())) {
            logger.error("isAuthorized() Session expired {}", user.getSubject());
            return false;
        }

        //in some cases, we don't go through the evaluation
        if (requestBody == null) {
            logger.debug("ACCESS_LOG ___ {},{},{} ___ has been granted access to application ___ {} ___ NO REQUEST BODY FORWARDED BY APPLICATION", user.getUuid().toString(), user.getEmail(), user.getName(), applicationName);
            return true;
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
            formattedQuery = (String) ((Map)requestBody).get("formattedQuery");

            if(formattedQuery == null) {
                //fallback in case no formatted query info present
                formattedQuery = new ObjectMapper().writeValueAsString(requestBody);
            }

        } catch (ClassCastException | JsonProcessingException e1) {
            logger.debug("ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} ___ UNABLE TO PARSE REQUEST", user.getUuid().toString(), user.getEmail(), user.getName(), requestBody, applicationName);
            logger.debug("isAuthorized() Stack Trace: ", e1);
            return false;
        }

        Set<AccessRule> accessRules;
        String label = "";
        if (user.getConnection() != null) {
            // Open Access doesn't currently use a connection
            label = user.getConnection().getLabel();
        }

        if (!this.strictConnections.contains(label)) {
            Set<Privilege> privileges = user.getPrivilegesByApplication(application);
            if (privileges == null || privileges.isEmpty()) {
                logger.info("ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} __ USER HAS NO PRIVILEGES ASSOCIATED TO THE APPLICATION, BUT APPLICATION HAS PRIVILEGES", user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName);
                return false;
            }

            accessRules = this.accessRuleService.cachedPreProcessAccessRules(user, privileges);
            if (accessRules == null || accessRules.isEmpty()) {
                logger.info("ACCESS_LOG ___ {},{},{} ___ has been granted access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED", user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName);
                return true;
            }
        } else {
            accessRules = this.accessRuleService.getAccessRulesForUserAndApp(user, application);
            if (accessRules == null || accessRules.isEmpty()) {
                logger.info("ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED", user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName);
                return false;
            }
        }

        // Current logic here is: among all accessRules, they are OR relationship
        Set<AccessRule> failedRules = new HashSet<>();
        AccessRule passByRule = null;
        boolean result = false;
        for (AccessRule accessRule : accessRules) {
            if (this.accessRuleService.evaluateAccessRule(requestBody, accessRule)) {
                result = true;
                passByRule = accessRule;
                break;
            } else {
                failedRules.add(accessRule);
            }
        }

        String passRuleName = null;
        if (passByRule != null) {
            if (passByRule.getMergedName().isEmpty())
                passRuleName = passByRule.getName();
            else
                passRuleName = passByRule.getMergedName();
        }

        logger.debug("ACCESS_LOG ___ {},{},{} ___ has been {} access to execute query ___ {} ___ in application ___ {} ___ {}", user.getUuid().toString(), user.getEmail(), user.getName(), result ? "granted" : "denied", formattedQuery, applicationName, result ? "passed by " + passRuleName : "failed by rules: ["
                + failedRules.stream()
                .map(ar -> (ar.getMergedName().isEmpty() ? ar.getName() : ar.getMergedName()))
                .collect(Collectors.joining(", ")) + "]");

        return result;
    }




}
