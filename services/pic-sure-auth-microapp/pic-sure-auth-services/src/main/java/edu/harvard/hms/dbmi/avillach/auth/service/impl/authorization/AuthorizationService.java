package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.model.EvaluateAccessRuleResult;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.rest.TokenController;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
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
 * technology used here is jsonpath. In the {@link TokenController#inspectToken(Map)} class, other registered applications can hit the
 * tokenIntrospection endpoint with a token they want PSAMA to introspect along with the URL the token holder is trying to hit and what data
 * this token holder is trying to send. After checking if the token is valid or not, the authorization check in this class will start.
 * <br><br> <p> Whether users are allowed access or not depends on their privileges, which depends on the accessRules underneath.
 * AuthorizationService class will eventually use jsonpath to check if certain places in the incoming JSON meet the requirement of the
 * preset rules in accessRules to determine if the token holder is authorized or not. </p>
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
    private static final Pattern AUTH_TARGET_SERVICE_PATTERN = Pattern.compile("^/(hpds|visualization)/auth(/.*)?$");
    static final String NO_CONSENTS_MESSAGE = "User has no consents on file.";
    static final String NO_ACCESS_RULES_MESSAGE = "No access rule grants this request.";

    protected AccessRuleService accessRuleService;
    protected SessionService sessionService;
    private final RoleService roleService;

    /**
     * Applications that have strict access control. If the application is strict a user must have both privileges and access rules. If the
     * application is not strict, the user only needs privileges. Access rules are optional.
     */
    private final Set<String> strictConnections = new HashSet<>();


    private final UserConsentsRepository userConsentsRepository;
    private final boolean consentBasedAuthorizationEnabled;
    private boolean enablePublicAccess;

    @Autowired
    public AuthorizationService(
        AccessRuleService accessRuleService, SessionService sessionService, RoleService roleService,
        @Value("${strict.authorization.applications.connections}") String strictConnections, UserConsentsRepository userConsentsRepository,
        @Value("${consent.based.authorization.enabled:true}") boolean consentBasedAuthorizationEnabled,
        @Value("${enable.public.access:false}") boolean enablePublicAccess
    ) {
        this.accessRuleService = accessRuleService;
        this.sessionService = sessionService;
        this.roleService = roleService;
        if (strictConnections != null && !strictConnections.isEmpty()) {
            this.strictConnections.addAll(Arrays.asList(strictConnections.split(",")));
        }
        this.userConsentsRepository = userConsentsRepository;
        this.consentBasedAuthorizationEnabled = consentBasedAuthorizationEnabled;
        this.enablePublicAccess = enablePublicAccess;
        logger.info("Consent-based authorization enabled: {}", consentBasedAuthorizationEnabled);
    }

    /**
     * Checking based on AccessRule in Privilege <br><br> Thoughts on design: <br> <br> We have three layers here: role, privilege,
     * accessRule. <br> A role might have multiple privileges, a privilege might have multiple accessRules. <br> Currently, we retrieve all
     * accessRules together. Between AccessRules, they should be OR relationship, which means roles and privileges are OR relationship, pass
     * one, and you are good. <br> <br> Inside each accessRule, there are subAccessRules and Gates. <br> Only if all gates are applied will
     * the accessRule be checked. <br> The accessRule and subAccessRules are an AND relationship.
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

        if (user == null) {
            logger.error("isAuthorized() User cannot be null");
            return new EvaluateAccessRuleResult(false, Set.of(), null);
        }

        if (StringUtils.isBlank(user.getSubject())) {
            logger.error("isAuthorized() Subject cannot be blank {}", user.getSubject());
            return new EvaluateAccessRuleResult(false, Set.of(), null);
        }

        if (!isLongTermToken && sessionService.isSessionExpired(user.getSubject())) {
            logger.error("isAuthorized() Session expired {}", user.getSubject());
            return new EvaluateAccessRuleResult(false, Set.of(), null);
        }

        Object authorizationRequest = Objects.requireNonNullElse(requestBody, Map.of());
        String formattedQuery = authorizationRequest.toString();
        if (
            authorizationRequest instanceof Map<?, ?> requestMap
                && requestMap.get("formattedQuery") instanceof String providedFormattedQuery
        ) {
            formattedQuery = providedFormattedQuery;
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
                logger.info(
                    "ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED",
                    user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName
                );
                return denied(Set.of(), NO_ACCESS_RULES_MESSAGE);
            }
        } else {
            Set<Privilege> privileges = user.getPrivilegesByApplication(application);
            // List all privileges of the user
            logger.info(
                "ACCESS_LOG ___ {},{},{} ___ has the following privileges: {}", user.getUuid().toString(), user.getEmail(), user.getName(),
                privileges.stream().map(Privilege::getName).collect(Collectors.joining(", "))
            );

            if (privileges.isEmpty()) {
                logger.info(
                    "ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} __ USER HAS NO PRIVILEGES ASSOCIATED TO THE APPLICATION, BUT APPLICATION HAS PRIVILEGES",
                    user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName
                );
                return new EvaluateAccessRuleResult(false, Set.of(), null);
            }

            accessRules = this.accessRuleService.cachedPreProcessAccessRules(user, privileges);
            if (accessRules.isEmpty()) {
                logger.info(
                    "ACCESS_LOG ___ {},{},{} ___ has been denied access to execute query ___ {} ___ in application ___ {} ___ NO ACCESS RULES EVALUATED",
                    user.getUuid().toString(), user.getEmail(), user.getName(), formattedQuery, applicationName
                );
                return denied(Set.of(), NO_ACCESS_RULES_MESSAGE);
            }
        }

        logger.info(
            "ACCESS_LOG ___ {},{},{} ___ has the following access rules: {}", user.getUuid().toString(), user.getEmail(), user.getName(),
            accessRules.stream().map(AccessRule::toString).collect(Collectors.joining(", "))
        );

        EvaluateAccessRuleResult evaluationResult = passesAccessRuleEvaluation(authorizationRequest, accessRules, user);
        boolean result = evaluationResult.result();
        String passRuleName = evaluationResult.passRuleName();
        Set<AccessRule> failedRules = evaluationResult.failedRules();

        logger.info(
            "ACCESS_LOG ___ {},{},{} ___ has been {} access to execute query ___ {} ___ in application ___ {} ___ {}",
            user.getUuid().toString(), user.getEmail(), user.getName(), (result ? "granted" : "denied"), formattedQuery, applicationName,
            (result ? "passed by " + passRuleName
                : evaluationResult.denialReason().orElseGet(
                    () -> "failed by rules: [" + failedRules.stream()
                        .map(ar -> (ar.getMergedName().isEmpty() ? ar.getName() : ar.getMergedName())).collect(Collectors.joining(", "))
                        + "]"
                ))
        );

        return evaluationResult;
    }

    private EvaluateAccessRuleResult passesAccessRuleEvaluation(Object requestBody, Set<AccessRule> accessRules, User user) {
        Set<AccessRule> failedRules = new HashSet<>();
        AccessRule passByRule = null;

        for (AccessRule accessRule : accessRules) {
            try {
                if (this.accessRuleService.evaluateAccessRule(requestBody, accessRule)) {
                    passByRule = accessRule;
                    break;
                } else {
                    failedRules.add(accessRule);
                    if (logger.isInfoEnabled()) {
                        logger.info(
                            "Rule evaluation tree for failed rule {}:\n{}", ruleName(accessRule),
                            this.accessRuleService.printEvaluationTree()
                        );
                    }
                }
            } finally {
                this.accessRuleService.clearEvaluationTree();
            }
        }

        if (passByRule == null) {
            return new EvaluateAccessRuleResult(false, failedRules, null);
        }

        String passRuleName = ruleName(passByRule);
        if (consentBasedAuthorizationEnabled && user != null && !hasConsents(userConsentsRepository.findByUserId(user.getUuid()))) {
            logger.warn("Denying request for user {}: {}", user.getUuid(), NO_CONSENTS_MESSAGE);
            return denied(failedRules, NO_CONSENTS_MESSAGE);
        }
        return new EvaluateAccessRuleResult(true, failedRules, passRuleName);
    }

    private static EvaluateAccessRuleResult denied(Set<AccessRule> failedRules, String reason) {
        return new EvaluateAccessRuleResult(false, failedRules, null, Optional.of(reason));
    }

    private static String ruleName(AccessRule accessRule) {
        return accessRule.getMergedName().isEmpty() ? accessRule.getName() : accessRule.getMergedName();
    }

    private static boolean hasConsents(UserConsents userConsents) {
        return userConsents != null && userConsents.getConsents() != null
            && userConsents.getConsents().values().stream().filter(Objects::nonNull).anyMatch(consents -> !consents.isEmpty());
    }

    /**
     * Returns true only when {@code targetService} is a clean HPDS-v3 (auth-backend) target service path, i.e. exactly
     * {@code /hpds/auth/v3} or {@code /hpds/auth/v3/**}. Open-access requests never reach this method (they are authorized via a separate
     * endpoint), so the {@code open} backend is not matched. <p> This is intentionally segment/prefix-aware (not a loose
     * {@code contains("hpds") && contains("v3")} check) so that consent-rule evaluation is skipped only for genuine HPDS-v3 calls, not for
     * unrelated paths that happen to contain those substrings.
     */
    static boolean isAuthTargetService(String targetService) {
        return targetService != null && AUTH_TARGET_SERVICE_PATTERN.matcher(targetService).matches();
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

        if (requestBody instanceof Map<?, ?> requestDetails) {
            Object targetService = requestDetails.get("Target Service");
            if (targetService instanceof String targetServicePath && isAuthTargetService(targetServicePath) && !enablePublicAccess) {
                logger.info(
                    "ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been denied access to application ___ AUTH BACKEND PATH {} IS NOT AVAILABLE TO OPEN ACCESS",
                    targetServicePath
                );
                return false;
            }
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
            logger.info("ACCESS_LOG ___ AN OPEN ACCESS USER ___ has been denied access to application ___ NO ACCESS RULES EVALUATED");
            return false;
        } else {
            EvaluateAccessRuleResult evaluationResult = passesAccessRuleEvaluation(requestBody, allOpenAccessRules, null);
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
}
