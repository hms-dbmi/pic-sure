package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.entity.UserConsents;
import edu.harvard.hms.dbmi.avillach.auth.model.EvaluateAccessRuleResult;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Consent-rule evaluation over the FLAT request shape the gateway sends, through the REAL {@link BdcConsentBasedAccessRuleEvaluator}. <p>
 * This is the one branch whose authorization outcome actually changed. It used to read the query from {@code request.query.query} -- a
 * legacy envelope nesting the gateway has never sent -- so on a flat body it handed the evaluator a null Query and blew up. Now it reads
 * {@code request.query} directly and can genuinely GRANT, which means the grant path needs a positive test and the unreadable-body path
 * needs to be proven to fail CLOSED rather than 500.
 */
class AuthorizationServiceConsentEvaluationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A bare v3 query body, exactly as PsamaIntrospectionFilter forwards it under "query". */
    private static final String V3_QUERY = "{\"expectedResultType\":\"COUNT\",\"select\":[\"\\\\phs000007\\\\data\\\\age\\\\\"]}";

    private UserConsentsRepository userConsentsRepository;

    private AuthorizationService authorizationService;

    /**
     * Wired by hand rather than through a Spring context: nothing under test reads the container, and the real
     * BdcConsentBasedAccessRuleEvaluator is the point of this class -- mocking it would test nothing.
     */
    @BeforeEach
    void setUp() {
        userConsentsRepository = mock(UserConsentsRepository.class);
        AccessRuleService accessRuleService =
            new AccessRuleService(mock(AccessRuleRepository.class), "false", "false", "false", "false", "false", "false");
        authorizationService = new AuthorizationService(
            accessRuleService, mock(SessionService.class), mock(RoleService.class), new BdcConsentBasedAccessRuleEvaluator(),
            "fence,okta,open", userConsentsRepository
        );
    }

    /**
     * The grant path end to end: flat query node -> typed v3 Query -> consent evaluation grants -> the query comes back rewritten with the
     * user's consents injected as authorization filters, and serializes as a JSON OBJECT (which is what TokenService puts on the wire).
     */
    @Test
    void consentRuleGrantsAndReturnsTheQueryRewrittenWithAuthorizationFilters() throws JsonProcessingException {
        User user = userWithConsentRule();
        givenConsents(user, Map.of("\\_consents\\", Set.of("phs000007.c1", "phs000007.c2")));

        EvaluateAccessRuleResult result = authorizationService
            .isAuthorized(applicationFor(user), new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree(V3_QUERY)), user, false);

        assertTrue(result.result(), "the user consents to phs000007, so the consent rule must grant");
        assertTrue(result.query().isPresent(), "a granting consent rule must hand back the rewritten query");

        Query rewritten = result.query().get();
        assertEquals(1, rewritten.authorizationFilters().size());
        assertEquals("\\_consents\\", rewritten.authorizationFilters().getFirst().conceptPath());
        assertEquals(Set.of("phs000007.c1", "phs000007.c2"), rewritten.authorizationFilters().getFirst().values());
        // The submitted query is otherwise untouched.
        assertEquals("COUNT", rewritten.expectedResultType().name());

        // What TokenService actually emits: a JSON object, never an escaped string.
        JsonNode wire = MAPPER.valueToTree(rewritten);
        assertTrue(wire.isObject(), "the mutated query must serialize as an object");
        assertTrue(wire.get("authorizationFilters").isArray());
        assertEquals("\\_consents\\", wire.get("authorizationFilters").get(0).get("conceptPath").asText());
    }

    /** The same path, denying: the user has no consent for the study the query selects. */
    @Test
    void consentRuleDeniesWhenTheUserLacksTheStudy() throws JsonProcessingException {
        User user = userWithConsentRule();
        givenConsents(user, Map.of("\\_consents\\", Set.of("phs999999.c1")));

        EvaluateAccessRuleResult result = authorizationService
            .isAuthorized(applicationFor(user), new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree(V3_QUERY)), user, false);

        assertFalse(result.result());
        assertTrue(result.query().isEmpty(), "a denied request must not hand back a rewritten query");
    }

    /**
     * FAIL CLOSED: a query node that is not a v3 Query cannot be converted by the strict mapper. That must deny by the rule, not escape as
     * an IllegalArgumentException -- an uncaught one is a 500 that the gateway reports as a 502, so one unreadable body would take the
     * endpoint down instead of failing a single rule closed. The legacy {query, resourceUUID} envelope is the realistic instance.
     */
    @Test
    void consentRuleDeniesLegacyEnvelopeQueryInsteadOfThrowing() throws JsonProcessingException {
        User user = userWithConsentRule();
        givenConsents(user, Map.of("\\_consents\\", Set.of("phs000007.c1")));

        JsonNode legacyEnvelope = MAPPER.readTree(
            "{\"resourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":{\"expectedResultType\":\"COUNT\",\"fields\":[]}}"
        );

        EvaluateAccessRuleResult result = assertDoesNotThrow(
            () -> authorizationService
                .isAuthorized(applicationFor(user), new TargetedRequest("/hpds/auth/v3/query", legacyEnvelope), user, false)
        );

        assertFalse(result.result(), "an unreadable query must be denied, never granted");
        assertTrue(result.query().isEmpty());
    }

    /** Same guard, non-query body: a search request forwarded to a consent-gated privilege. */
    @Test
    void consentRuleDeniesNonQueryBodyInsteadOfThrowing() throws JsonProcessingException {
        User user = userWithConsentRule();
        givenConsents(user, Map.of("\\_consents\\", Set.of("phs000007.c1")));

        EvaluateAccessRuleResult result = assertDoesNotThrow(
            () -> authorizationService.isAuthorized(
                applicationFor(user),
                new TargetedRequest("/picsure/proxy/dictionary/search", MAPPER.readTree("{\"searchTerm\":\"asthma\"}")), user, false
            )
        );

        assertFalse(result.result());
    }

    private void givenConsents(User user, Map<String, Set<String>> consents) {
        when(userConsentsRepository.findByUserId(any()))
            .thenReturn(new UserConsents().setUserId(user.getUuid()).setConsents(consents));
    }

    private static Application applicationFor(User user) {
        Application application = user.getRoles().iterator().next().getPrivileges().iterator().next().getApplication();
        application.setPrivileges(new HashSet<>());
        return application;
    }

    private User userWithConsentRule() {
        AccessRule consentRule = new AccessRule();
        consentRule.setUuid(UUID.randomUUID());
        consentRule.setName("AR_CONSENT_bdc");
        consentRule.setType(AccessRule.TypeNaming.USER_CONSENT_ACCESS);
        // Deployed consent rules carry a rule string even though the USER_CONSENT_ACCESS branch never evaluates it as JsonPath:
        // AccessRuleService#preProcessARBySortedKeys puts it in a TreeSet and NPEs on null. Kept realistic on purpose.
        consentRule.setRule("$.query.categoryFilters.\\_consents\\[*]");
        consentRule.setValue("phs000007");

        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("TEST_APPLICATION");
        application.setPrivileges(new HashSet<>());

        Privilege privilege = new Privilege();
        privilege.setUuid(UUID.randomUUID());
        privilege.setName("TEST_PRIVILEGE");
        privilege.setApplication(application);
        privilege.setAccessRules(Collections.singleton(consentRule));

        Role role = new Role();
        role.setUuid(UUID.randomUUID());
        role.setName("TEST_ROLE");
        role.setPrivileges(Collections.singleton(privilege));

        Connection connection = new Connection();
        connection.setLabel("TEST_CONNECTION");

        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>(Collections.singleton(role)));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);
        user.setConnection(connection);
        return user;
    }
}
