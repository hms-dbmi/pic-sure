package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService.*;

/**
 * This class handles authorization activities in the project. It decides if a user can send a request to certain applications based on what
 * endpoint they are trying to hit and the content of the request body (in HTTP POST method). <h3>Thoughts on design:</h3> The core
 * technology used here is jsonpath. In the {@link TokenController} class, other registered applications can hit the tokenIntrospection
 * endpoint with a token they want PSAMA to introspect along with the URL the token holder is trying to hit and what data this token holder
 * is trying to send. After checking if the token is valid or not, the authorization check in this class will start. <br><br> <p> Whether
 * users are allowed access or not depends on their privileges, which depends on the accessRules underneath. AuthorizationService class will
 * eventually use jsonpath to check if certain places in the incoming JSON meet the requirement of the preset rules in accessRules to
 * determine if the token holder is authorized or not. </p>
 */
@Service
public class AuthorizationService {

    private final Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

    /**
     * Matches clean HPDS-v3 (auth-backend) target service paths, e.g. {@code /hpds/auth/v3}, {@code /hpds/auth/v3/query},
     * {@code /hpds/auth/v3/query/abc/result}. Only the {@code auth} backend reaches this method — open-access requests are authorized via a
     * separate endpoint — so the {@code open} backend is intentionally not matched. Segment-aware so it does NOT match things like
     * {@code /hpds/auth/v30/query}, {@code /hpds/auth/v3ish/query}, {@code /hpds/v3/query}, {@code /foo/hpds/auth/v3/query}, or
     * {@code /hpds/auth/v3-query}.
     */
    private static final Pattern HPDS_V3_TARGET_SERVICE_PATTERN = Pattern.compile("^/hpds/auth/v3(/.*)?$");

    /**
     * SECURITY: this mapper only ever turns a {@link TargetedRequest} back into the plain {@code Map} the JsonPath evaluator has always
     * been handed, and turns the request's query node into a typed {@code Query}. It must not be configured with a naming strategy, an
     * inclusion policy, or anything else that would change the serialized shape -- deployed FISMA access rules are JsonPath strings stored
     * in the database and are evaluated against that map.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> RULE_NODE_TYPE = new TypeReference<>() {};

    protected AccessRuleService accessRuleService;
    protected SessionService sessionService;
    private final RoleService roleService;

    private final ConsentBasedAccessRuleEvaluator consentBasedAccessRuleEvaluator;

    /**
     * Applications that have strict access control. If the application is strict a user must have both privileges and access rules. If the
     * application is not strict, the user only needs privileges. Access rules are optional.
     */
    private final Set<String> strictConnections = new HashSet<>();


    private final UserConsentsRepository userConsentsRepository;

    @Autowired
    public AuthorizationService(
        AccessRuleService accessRuleService, SessionService sessionService, RoleService roleService,
        ConsentBasedAccessRuleEvaluator consentBasedAccessRuleEvaluator,
        @Value("${strict.authorization.applications.connections}") String strictConnections, UserConsentsRepository userConsentsRepository
    ) {
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
     * Checking based on AccessRule in Privilege <br><br> Thoughts on design: <br> <br> We have three layers here: role, privilege,
     * accessRule. <br> A role might have multiple privileges, a privilege might have multiple accessRules. <br> Currently, we retrieve all
     * accessRules together. Between AccessRules, they should be OR relationship, which means roles and privileges are OR relationship, pass
     * one, and you are good. <br> <br> Inside each accessRule, there are subAccessRules and Gates. <br> Only if all gates are applied will
     * the accessRule be checked. <br> The accessRule and subAccessRules are an AND relationship.
     *
     * @param application
     * @param request the request being authorized, exactly as the caller sent it
     * @param isLongTermToken
     * @return
     * @see Privilege
     * @see AccessRule
     */
    public EvaluateAccessRuleResult isAuthorized(Application application, TargetedRequest request, User user, boolean isLongTermToken) {
        String applicationName = application.getName();

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

        // in some cases, we don't go through the evaluation
        if (request == null) {
            logger.debug(
                "ACCESS_LOG ___ {},{},{} ___ has been granted access to application ___ {} ___ NO REQUEST BODY FORWARDED BY APPLICATION",
                user.getUuid().toString(), user.getEmail(), user.getName(), applicationName
            );
            return new EvaluateAccessRuleResult(true, Set.of(), null, Optional.empty());
        }

        // Access-log text only: never read for an authorization decision.
        String formattedQuery = describe(request);

        Set<AccessRule> accessRules;
        String label = "";
        if (user.getConnection() != null) {
            // Open Access doesn't currently use a connection
            label = user.getConnection().getLabel();
        }

        if (this.strictConnections.contains(label)) {
            accessRules = this.accessRuleService.getAccessRulesForUserAndApp(user, application);
            if (accessRules.isEmpty()) {
                logger.info(
                    "ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED",
                    user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName
                );
                return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
            }
        } else {
            Set<Privilege> privileges = user.getPrivilegesByApplication(application);
            // List all privileges of the user
            logger.info(
                "ACCESS_LOG ___ {},{},{} ___ has the following privileges: {}", user.getUuid().toString(), user.getEmail(), user.getName(),
                privileges.stream().map(Privilege::getName).collect(Collectors.joining(", "))
            );
            if (privileges == null || privileges.isEmpty()) {
                logger.info(
                    "ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} __ USER HAS NO PRIVILEGES ASSOCIATED TO THE APPLICATION, BUT APPLICATION HAS PRIVILEGES",
                    user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName
                );
                return new EvaluateAccessRuleResult(false, Set.of(), null, Optional.empty());
            }

            accessRules = this.accessRuleService.cachedPreProcessAccessRules(user, privileges);
            if (accessRules.isEmpty()) {
                logger.info(
                    "ACCESS_LOG ___ {},{},{} ___ has been granted access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED",
                    user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName
                );
                return new EvaluateAccessRuleResult(true, Set.of(), null, Optional.empty());
            }
        }

        logger.info(
            "ACCESS_LOG ___ {},{},{} ___ has the following access rules: {}", user.getUuid().toString(), user.getEmail(), user.getName(),
            accessRules.stream().map(AccessRule::toString).collect(Collectors.joining(", "))
        );

        EvaluateAccessRuleResult evaluationResult = passesAccessRuleEvaluation(request, accessRules, user);
        boolean result = evaluationResult.result();
        String passRuleName = evaluationResult.passRuleName();
        Set<AccessRule> failedRules = evaluationResult.failedRules();

        logger.info(
            "ACCESS_LOG ___ {},{},{} ___ has been {} access to execute query ___ {} ___ in application ___ {} ___ {}",
            user.getUuid().toString(), user.getEmail(), user.getName(), (result ? "granted" : "denied"), formattedQuery, applicationName,
            (result ? "passed by " + passRuleName
                : "failed by rules: [" + failedRules.stream().map(ar -> (ar.getMergedName().isEmpty() ? ar.getName() : ar.getMergedName()))
                    .collect(Collectors.joining(", ")) + "]")
        );

        return evaluationResult;
    }

    private EvaluateAccessRuleResult passesAccessRuleEvaluation(TargetedRequest request, Set<AccessRule> accessRules, User user) {
        // Current logic here is: among all accessRules, they are OR relationship
        Set<AccessRule> failedRules = new HashSet<>();
        AccessRule passByRule = null;
        boolean result = false;
        Query returnQuery = null;

        // The exact node deployed JsonPath rules are evaluated against, built once. See toRuleEvaluationNode.
        Map<String, Object> ruleEvaluationNode = toRuleEvaluationNode(request);

        for (AccessRule accessRule : accessRules) {
            try {
                if (AccessRule.TypeNaming.USER_CONSENT_ACCESS == accessRule.getType()) {
                    UserConsents userConsents = userConsentsRepository.findByUserId(user.getUuid());

                    JsonNode queryNode = request.query();
                    if (queryNode == null || queryNode.isNull()) {
                        // Non-query request bodies (e.g. {Target Service=/operations/...}) carry no
                        // query for a consent rule to evaluate: deny by this rule rather than NPE
                        // into a 500, which the gateway would surface as a 502.
                        failedRules.add(accessRule);
                        continue;
                    }
                    Query query = OBJECT_MAPPER.convertValue(queryNode, Query.class);

                    if (consentBasedAccessRuleEvaluator.evaluateAccessRule(query, accessRule, userConsents)) {
                        result = true;
                        passByRule = accessRule;

                        returnQuery = consentBasedAccessRuleEvaluator.setAuthorizationFiltersForQuery(userConsents, query);
                        break;
                    } else {
                        failedRules.add(accessRule);
                    }
                } else {
                    String targetService = request.targetService();
                    logger.debug("Target service = {}", targetService);
                    if (targetService != null && (targetService.startsWith("/v3") || isHpdsV3TargetService(targetService))) {
                        logger.debug("Skipping access rule {}", accessRule.getName());
                    } else if (this.accessRuleService.evaluateAccessRule(ruleEvaluationNode, accessRule)) {
                        result = true;
                        passByRule = accessRule;
                        break;
                    } else {
                        failedRules.add(accessRule);
                        // Print the evaluation tree when a rule fails
                        if (logger.isInfoEnabled()) {
                            String ruleName = accessRule.getMergedName().isEmpty() ? accessRule.getName() : accessRule.getMergedName();
                            logger.info(
                                "Rule evaluation tree for failed rule {}:\n{}", ruleName, this.accessRuleService.printEvaluationTree()
                            );
                        }
                    }
                }
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

    /**
     * SECURITY: turns the typed request back into the plain {@code Map} that {@code JsonPath.parse(...)} has always been handed. <p> Two
     * things make this conversion load-bearing rather than incidental. First, the deployed FISMA access rules are JsonPath expressions
     * stored in PSAMA's database -- {@code $.['Target Service']}, {@code $.query.expectedResultType} -- and they resolve against whatever
     * this method returns; the key names, the nesting depth, and the query staying an object rather than a string are all part of the
     * production authorization decision. Second, json-path's default provider walks {@code Map}/{@code List}, not Jackson nodes: handing it
     * a {@code TargetedRequest} or a {@code JsonNode} would make every rule unresolvable. {@code AuthorizationServiceRuleNodeTest} pins the
     * result against the raw map shape the gateway used to send.
     */
    static Map<String, Object> toRuleEvaluationNode(TargetedRequest request) {
        return OBJECT_MAPPER.convertValue(request, RULE_NODE_TYPE);
    }

    /** Access-log text for a request. Never used to decide anything. */
    private String describe(TargetedRequest request) {
        try {
            return OBJECT_MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            logger.debug("isAuthorized() could not render the request for the access log", e);
            return String.valueOf(request);
        }
    }

    /**
     * Returns true only when {@code targetService} is a clean HPDS-v3 (auth-backend) target service path, i.e. exactly
     * {@code /hpds/auth/v3} or {@code /hpds/auth/v3/**}. Open-access requests never reach this method (they are authorized via a separate
     * endpoint), so the {@code open} backend is not matched. <p> This is intentionally segment/prefix-aware (not a loose
     * {@code contains("hpds") && contains("v3")} check) so that consent-rule evaluation is skipped only for genuine HPDS-v3 calls, not for
     * unrelated paths that happen to contain those substrings.
     */
    static boolean isHpdsV3TargetService(String targetService) {
        if (targetService == null) {
            return false;
        }
        return HPDS_V3_TARGET_SERVICE_PATTERN.matcher(targetService).matches();
    }

    public boolean openAccessRequestIsValid(Map<String, Object> inputMap) {

        if (inputMap == null || inputMap.isEmpty()) {
            logger.info(
                "ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been denied access to application ___ NO REQUEST BODY FORWARDED BY APPLICATION"
            );
            return true;
        }

        Object requestBody = inputMap.get("request");
        // If there is no request body, we can assume the request is valid
        if (requestBody == null) {
            logger.info(
                "ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been granted access to application ___ NO REQUEST BODY FORWARDED BY APPLICATION"
            );
            return true;
        }

        // Load the open access rules
        Role openAccessRole = this.roleService.getRoleByName(MANAGED_OPEN_ACCESS_ROLE_NAME);
        if (openAccessRole == null) {
            logger.info(
                "{} has not be created for this environment. Please create the role and its permissions before attempting to use open access.",
                MANAGED_OPEN_ACCESS_ROLE_NAME
            );
            return false;
        }

        Set<AccessRule> allOpenAccessRules = openAccessRole.getPrivileges().stream().map(Privilege::getAccessRules)
            .collect(Collectors.toSet()).stream().flatMap(Collection::stream).collect(Collectors.toSet());

        boolean result = false;
        if (allOpenAccessRules.isEmpty()) {
            result = true;
            logger.info("ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been granted access to application ___ NO ACCESS RULES EVALUATED");
        } else {
            EvaluateAccessRuleResult evaluationResult =
                passesAccessRuleEvaluation(asTargetedRequest(requestBody), allOpenAccessRules, null);
            result = evaluationResult.result();
            String passRuleName = evaluationResult.passRuleName();
            Set<AccessRule> failedRules = evaluationResult.failedRules();
            logger.info(
                "ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been {} access to execute query ___ {} ___ in application ___ OPEN ACCESS ___ {}",
                (result ? "granted" : "denied"), requestBody,
                (result ? "passed by " + passRuleName
                    : "failed by rules: [" + failedRules.stream()
                        .map(ar -> (ar.getMergedName().isEmpty() ? ar.getName() : ar.getMergedName())).collect(Collectors.joining(", "))
                        + "]")
            );
        }

        return result;
    }

    /**
     * The open-access endpoint still binds an untyped map (it also carries {@code ipAddress}, which introspection does not), so its
     * {@code request} node is narrowed here instead of at the controller. Narrowing is deliberately lenient -- an unexpected key is dropped
     * rather than raised -- because a rejected open-access request is a user-visible outage, and the gateway only ever sends the two keys
     * this reads ({@code OpenAccessFilter#buildOpenAccessRequest}). PSAMA's own surface is typed in a later step.
     */
    private static TargetedRequest asTargetedRequest(Object requestBody) {
        if (requestBody instanceof TargetedRequest targetedRequest) {
            return targetedRequest;
        }
        if (!(requestBody instanceof Map<?, ?> requestMap)) {
            // Nothing a rule can bind to; every rule then decides on PathNotFound, as it would have for an empty body.
            return new TargetedRequest(null, null);
        }
        Object targetService = requestMap.get("Target Service");
        Object query = requestMap.get("query");
        return new TargetedRequest(
            targetService == null ? null : targetService.toString(), query == null ? null : OBJECT_MAPPER.valueToTree(query)
        );
    }
}
