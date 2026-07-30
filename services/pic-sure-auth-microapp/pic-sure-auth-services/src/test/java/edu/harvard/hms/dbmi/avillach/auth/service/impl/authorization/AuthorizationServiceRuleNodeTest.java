package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

/**
 * SECURITY: deployed FISMA access rules are JsonPath strings in PSAMA's database, evaluated against the node
 * {@link AuthorizationService#toRuleEvaluationNode} produces. Those rules are data, not code -- they cannot be refactored alongside the
 * types. Binding {@code /token/inspect} to {@link TargetedRequest} changed what PSAMA holds in memory; it must not change one byte of what
 * the evaluator sees. <p> A failure here is a production authorization regression: the space in {@code "Target Service"} lost, the query
 * one level deeper or shallower, the query arriving as an escaped string, or a Jackson node handed to a json-path provider that only walks
 * {@code Map}/{@code List}. Every one of those leaves rules silently unresolvable rather than failing loudly.
 */
@SpringBootTest
@ContextConfiguration(classes = {AuthorizationService.class, AccessRuleService.class})
class AuthorizationServiceRuleNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @MockBean
    private SecurityContext securityContext;

    @MockBean
    private SessionService sessionService;

    @MockBean
    private AccessRuleRepository accessRuleRepository;

    @MockBean
    private RoleService roleService;

    @MockBean
    private BdcConsentBasedAccessRuleEvaluator consentEvaluator;

    @MockBean
    private UserConsentsRepository userConsentsRepository;

    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContextHolder.setContext(securityContext);
        AccessRuleService accessRuleService =
            new AccessRuleService(accessRuleRepository, "false", "false", "false", "false", "false", "false");
        authorizationService = new AuthorizationService(
            accessRuleService, sessionService, roleService, consentEvaluator, "fence,okta,open", userConsentsRepository
        );
    }

    /** The node the gateway used to build by hand, byte for byte. */
    @Test
    void ruleNodeMatchesTheRawMapTheGatewaySends() throws JsonProcessingException {
        TargetedRequest request =
            new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree("{\"expectedResultType\":\"COUNT\",\"select\":[]}"));

        Map<String, Object> legacyNode = new LinkedHashMap<>();
        legacyNode.put("Target Service", "/hpds/auth/v3/query");
        legacyNode.put("query", new LinkedHashMap<>(Map.of("expectedResultType", "COUNT", "select", List.of())));

        assertEquals(legacyNode, AuthorizationService.toRuleEvaluationNode(request));
    }

    /**
     * json-path's default provider walks {@code Map}/{@code List}. A {@code JsonNode} or a record here would make every deployed rule
     * unresolvable while still type-checking.
     */
    @Test
    void ruleNodeIsPlainMapsAndListsNotJacksonNodes() throws JsonProcessingException {
        Map<String, Object> node = AuthorizationService
            .toRuleEvaluationNode(new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree("{\"select\":[\"\\\\a\\\\b\\\\\"]}")));

        Object query = node.get("query");
        assertInstanceOf(Map.class, query, "query must stay a Map, not a Jackson node and never a String");
        assertInstanceOf(List.class, ((Map<?, ?>) query).get("select"));
    }

    /** Deployed rules resolve against the node exactly as they did before the request was typed. */
    @Test
    void deployedRulesResolveAgainstTheRuleNode() throws JsonProcessingException {
        Map<String, Object> node = AuthorizationService
            .toRuleEvaluationNode(new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree("{\"expectedResultType\":\"COUNT\"}")));

        assertEquals("/hpds/auth/v3/query", JsonPath.parse(node).read("$.['Target Service']"));
        assertEquals("COUNT", JsonPath.parse(node).read("$.query.expectedResultType"));
    }

    /**
     * Absence must stay absence: {@code extractAndCheckRule} treats a PathNotFoundException differently from a null match, so emitting
     * {@code "query": null} would flip the decision for IS_EMPTY and ALL_CONTAINS_OR_EMPTY rules.
     */
    @Test
    void ruleNodeOmitsQueryEntirelyWhenTheRequestHasNoBody() {
        Map<String, Object> node = AuthorizationService.toRuleEvaluationNode(new TargetedRequest("/picsure/proxy/dictionary/search", null));

        assertFalse(node.containsKey("query"), "an emitted null query turns PathNotFound into a null match: " + node);
        assertEquals("/picsure/proxy/dictionary/search", JsonPath.parse(node).read("$.['Target Service']"));
        org.junit.jupiter.api.Assertions
            .assertThrows(PathNotFoundException.class, () -> JsonPath.parse(node).read("$.query.expectedResultType"));
    }

    /** End to end through the real evaluator: a $.['Target Service'] rule still grants. */
    @Test
    void targetServiceRuleStillAuthorizes() {
        AccessRule rule = accessRule("$.['Target Service']", "/picsure/proxy/dictionary/search");
        Application application = application();
        User user = userFor(application, rule);

        boolean result = authorizationService
            .isAuthorized(application, new TargetedRequest("/picsure/proxy/dictionary/search", null), user, false).result();

        assertTrue(result);
    }

    /** End to end through the real evaluator: a $.query.<field> rule still grants against a bare v3 body. */
    @Test
    void queryFieldRuleStillAuthorizes() throws JsonProcessingException {
        AccessRule rule = accessRule("$.query.expectedResultType", "COUNT");
        Application application = application();
        User user = userFor(application, rule);

        boolean result = authorizationService.isAuthorized(
            application, new TargetedRequest("/picsure/proxy/dictionary/search", MAPPER.readTree("{\"expectedResultType\":\"COUNT\"}")),
            user, false
        ).result();

        assertTrue(result);
    }

    @Test
    void queryFieldRuleDeniesWhenTheValueDiffers() throws JsonProcessingException {
        AccessRule rule = accessRule("$.query.expectedResultType", "COUNT");
        Application application = application();
        User user = userFor(application, rule);

        boolean result = authorizationService.isAuthorized(
            application, new TargetedRequest("/picsure/proxy/dictionary/search", MAPPER.readTree("{\"expectedResultType\":\"DATAFRAME\"}")),
            user, false
        ).result();

        assertFalse(result);
    }

    private static AccessRule accessRule(String rule, String value) {
        AccessRule accessRule = new AccessRule();
        accessRule.setUuid(UUID.randomUUID());
        accessRule.setName("AR_TEST");
        accessRule.setRule(rule);
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue(value);
        return accessRule;
    }

    private static Application application() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("TEST_APPLICATION");
        application.setPrivileges(new java.util.HashSet<>());
        return application;
    }

    private User userFor(Application application, AccessRule rule) {
        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        privilege.setName("TEST_PRIVILEGE");
        privilege.setApplication(application);
        privilege.setAccessRules(Collections.singleton(rule));

        Role role = new Role();
        role.setUuid(UUID.randomUUID());
        role.setName("TEST_ROLE");
        role.setPrivileges(Collections.singleton(privilege));

        Connection connection = new Connection();
        connection.setLabel("TEST_CONNECTION");

        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new java.util.HashSet<>(Collections.singleton(role)));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);
        user.setConnection(connection);

        CustomUserDetails details = new CustomUserDetails(user);
        when(securityContext.getAuthentication())
            .thenReturn(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return user;
    }
}
