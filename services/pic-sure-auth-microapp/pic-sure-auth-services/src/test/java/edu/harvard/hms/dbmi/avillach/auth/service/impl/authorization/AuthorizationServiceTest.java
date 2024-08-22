package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.*;
import edu.harvard.hms.dbmi.avillach.auth.enums.SecurityRoles;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AuthorizationServiceTest {

    @Mock
    private SecurityContext securityContext;

    private AuthorizationService authorizationService;

    private AccessRuleService accessRuleService;

    @Mock
    private SessionService sessionService;

    @Mock
    private AccessRuleRepository accessRuleRepository;

    ObjectMapper mapper = new ObjectMapper();

    private static AccessRule GATE_resouceUUID;
    private static AccessRule GATE_has_expectedResultType;
    private static AccessRule GATE_has_categoryFilters;
    private static AccessRule GATE_has_requiredFields;

    private static AccessRule AR_CategoryFilter_String_contains;
    private static AccessRule AR_CategoryFilter_Any_Contains;
    private static AccessRule AR_Fields_ALL_SEX;
    private static AccessRule AR_Fields_ALL_AGE;
    private static AccessRule AR_Fields_IS_EMPTY;
    private static AccessRule AR_Fields_IS_NOT_EMPTY;
    private static AccessRule AR_ExpectedResultType_String_contains;

    private static String sample_matchGate = "{\"queries\":[{\"resourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":{\"expectedResultType\":\"DATAFRAME\"}}]}";

    private static String sample_gates_all_any = "{\"queries\":" +
            "[" +
            "{\"resourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\"" +
            "}]}";

    private static String sample_nestedGates = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"expectedResultType\": \"DATAFRAME\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    private static String getSample_passGate_passCheck_array_contains = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    private static String sample_passGate_passCheck_string_contains = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    private static String sample_no_pass_gate = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3fd\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    private static String sample_UUID_8694e3d4_withFields_SEX_And_AGE = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    private static String sample_UUID_8694e3d4_withFields_and_SEE_AGE_SEX = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEE\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\",\n" +
            "      \"\\\\demographics\\\\SEX\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    private static String sample_UUID_8694e3d4_withEmptyFields = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\",\n" +
            "    \"fields\": [\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    private static String sample_UUID_8694e3d4_withNoFieldsNode = "{\"queries\":[{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\"\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}]\n" +
            "}";

    @BeforeClass
    public static void init() {
        GATE_resouceUUID = new AccessRule();
        GATE_resouceUUID.setUuid(UUID.randomUUID());
        GATE_resouceUUID.setType(AccessRule.TypeNaming.ALL_EQUALS);
        GATE_resouceUUID.setName("Gate_resoruceUUID");
        GATE_resouceUUID.setRule("$.queries..resourceUUID");
        GATE_resouceUUID.setValue("8694e3d4-5cb4-410f-8431-993445e6d3f6");

        GATE_has_expectedResultType = new AccessRule();
        GATE_has_expectedResultType.setUuid(UUID.randomUUID());
        GATE_has_expectedResultType.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        GATE_has_expectedResultType.setName("GATE_has_expectedResultType");
        GATE_has_expectedResultType.setRule("$.queries..query");
        GATE_has_expectedResultType.setValue("expectedResultType");
        GATE_has_expectedResultType.setCheckMapNode(true);
        GATE_has_expectedResultType.setCheckMapKeyOnly(true);

        GATE_has_categoryFilters = new AccessRule();
        GATE_has_categoryFilters.setUuid(UUID.randomUUID());
        GATE_has_categoryFilters.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        GATE_has_categoryFilters.setName("GATE_has_categoryFilters");
        GATE_has_categoryFilters.setRule("$.queries..query");
        GATE_has_categoryFilters.setValue("categoryFilters");
        GATE_has_categoryFilters.setCheckMapNode(true);
        GATE_has_categoryFilters.setCheckMapKeyOnly(true);

        GATE_has_requiredFields = new AccessRule();
        GATE_has_requiredFields.setUuid(UUID.randomUUID());
        GATE_has_requiredFields.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        GATE_has_requiredFields.setName("GATE_has_requiredFields");
        GATE_has_requiredFields.setRule("$.queries..query");
        GATE_has_requiredFields.setValue("requiredFields");
        GATE_has_requiredFields.setCheckMapNode(true);
        GATE_has_requiredFields.setCheckMapKeyOnly(true);

        AR_CategoryFilter_String_contains = new AccessRule();
        AR_CategoryFilter_String_contains.setUuid(UUID.randomUUID());
        AR_CategoryFilter_String_contains.setName("AR_CategoryFilter");
        AR_CategoryFilter_String_contains.setRule("$.queries..fields.*");
        AR_CategoryFilter_String_contains.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        AR_CategoryFilter_String_contains.setValue("\\demographics\\SEX\\");

        AR_CategoryFilter_Any_Contains = new AccessRule();
        AR_CategoryFilter_Any_Contains.setName("AR_CategoryFilter");
        AR_CategoryFilter_Any_Contains.setRule("$.queries..fields.*");
        AR_CategoryFilter_Any_Contains.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        AR_CategoryFilter_Any_Contains.setValue("\\demographics\\SEX\\");

        AR_Fields_ALL_SEX = new AccessRule();
        AR_Fields_ALL_SEX.setUuid(UUID.randomUUID());
        AR_Fields_ALL_SEX.setName("AR_CategoryFilter");
        AR_Fields_ALL_SEX.setRule("$.queries..fields.*");
        AR_Fields_ALL_SEX.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        AR_Fields_ALL_SEX.setValue("\\demographics\\SEX\\");
        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_Fields_ALL_SEX.setGates(gates);


        AR_Fields_ALL_AGE = new AccessRule();
        AR_Fields_ALL_AGE.setUuid(UUID.randomUUID());
        AR_Fields_ALL_AGE.setName("AR_CategoryFilter");
        AR_Fields_ALL_AGE.setRule("$.queries..fields.*");
        AR_Fields_ALL_AGE.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        AR_Fields_ALL_AGE.setValue("\\demographics\\AGE\\");
        Set<AccessRule> gates_2 = new HashSet<>();
        gates_2.add(GATE_resouceUUID);
        AR_Fields_ALL_AGE.setGates(gates_2);

        AR_Fields_IS_EMPTY = new AccessRule();
        AR_Fields_IS_EMPTY.setUuid(UUID.randomUUID());
        AR_Fields_IS_EMPTY.setName("AR_Fields_IS_EMPTY");
        AR_Fields_IS_EMPTY.setRule("$.queries..fields.*");
        AR_Fields_IS_EMPTY.setType(AccessRule.TypeNaming.IS_EMPTY);

        AR_Fields_IS_NOT_EMPTY = new AccessRule();
        AR_Fields_IS_NOT_EMPTY.setUuid(UUID.randomUUID());
        AR_Fields_IS_NOT_EMPTY.setName("AR_Fields_IS_EMPTY");
        AR_Fields_IS_NOT_EMPTY.setRule("$.queries..fields.*");
        AR_Fields_IS_NOT_EMPTY.setType(AccessRule.TypeNaming.IS_NOT_EMPTY);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        SecurityContextHolder.setContext(securityContext);

        when(sessionService.isSessionExpired(any(String.class))).thenReturn(false);
        accessRuleService = new AccessRuleService(accessRuleRepository, "false", "false", "false", "false","false", "false");
        authorizationService = new AuthorizationService(accessRuleService, sessionService, "fence,okta");
    }

    @Test
    public void testIsAuthorized_AccessRulePassed() {
        Application application = createTestApplication();
        User user = createTestUser();

        // create access_rule for privilege
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");


        // set application and access_rule for all user privileges
        for (Privilege privilege1 : user.getRoles().iterator().next().getPrivileges()) {
            privilege1.setAccessRules(Collections.singleton(accessRule));
            privilege1.setApplication(application);
        }
        configureUserSecurityContext(user);
        user.setConnection(createFenceTestConnection());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = authorizationService.isAuthorized(application, requestBody, user);

        assertTrue(result);
    }

    @Test
    public void testIsAuthorized_AccessRuleFailed() {
        Application application = createTestApplication();
        User user = createTestUser();
        configureUserSecurityContext(user);
        user.setConnection(createFenceTestConnection());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");

        boolean result = authorizationService.isAuthorized(application, requestBody, user);

        assertFalse(result);
    }

    @Test
    public void testIsAuthorized_AccessRuleFailed_strict() {
        Application application = createTestApplication();
        User user = createTestUser();
        configureUserSecurityContext(user);
        user.setConnection(createFenceTestConnection());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");

        boolean result = authorizationService.isAuthorized(application, requestBody, user);

        assertFalse(result);
    }

    private Connection createFenceTestConnection() {
        Connection connection = new Connection();
        connection.setUuid(UUID.randomUUID());
        connection.setLabel("FENCE");
        connection.setSubPrefix("fence|");
        return connection;
    }

    @Test
    public void testEvaluateAccessRule_GatesPassed() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_GatesFailed() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertFalse(result);
    }

    @Test
    public void testExtractAndCheckRule_Pass() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = accessRuleService.extractAndCheckRule(accessRule, requestBody);

        assertTrue(result);
    }

    @Test
    public void testExtractAndCheckRule_Fail() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");

        boolean result = accessRuleService.extractAndCheckRule(accessRule, requestBody);

        assertFalse(result);
    }

    @Test
    public void testDecisionMaker_Pass() {
        AccessRule accessRule = new AccessRule();
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        boolean result = accessRuleService.decisionMaker(accessRule, "value");

        assertTrue(result);
    }

    @Test
    public void testDecisionMaker_Fail() {
        AccessRule accessRule = new AccessRule();
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        boolean result = accessRuleService.decisionMaker(accessRule, "differentValue");

        assertFalse(result);
    }

    @Test
    public void testIsAuthorized_NoRequestBody() {
        Application application = createTestApplication();
        User user = createTestUser();
        configureUserSecurityContext(user);
        application.setPrivileges(user.getPrivilegesByApplication(application));

        boolean result = authorizationService.isAuthorized(application, null, user);

        assertTrue(result);
    }

    @Test
    public void testIsAuthorized_NoPrivileges() {
        Application application = createTestApplication();
        User user = createTestUser();
        user.setConnection(createFenceTestConnection());

        user.getRoles().iterator().next().setPrivileges(Collections.emptySet());
        boolean result = authorizationService.isAuthorized(application, new HashMap<>(), user);

        assertFalse(result);
    }

    @Test
    public void testEvaluateAccessRule_NoGates() {
        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_AllGatesPassed_AND() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "value2");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_OneGateFails_AND() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "differentValue");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertFalse(result);
    }

    @Test
    public void testEvaluateAccessRule_OneGatePassed_OR() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));
        accessRule.setGateAnyRelation(true);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "differentValue");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_AllGatesFail_OR() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule gate2 = new AccessRule();
        gate2.setRule("$.test2");
        gate2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate2.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Arrays.asList(gate1, gate2)));
        accessRule.setGateAnyRelation(true);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "differentValue");
        requestBody.put("test2", "differentValue");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertFalse(result);
    }

    @Test
    public void testEvaluateAccessRule_EvaluateOnlyByGates() {
        AccessRule gate1 = new AccessRule();
        gate1.setRule("$.test");
        gate1.setType(AccessRule.TypeNaming.ALL_EQUALS);
        gate1.setValue("value");

        AccessRule accessRule = new AccessRule();
        accessRule.setGates(new HashSet<>(Collections.singletonList(gate1)));
        accessRule.setEvaluateOnlyByGates(true);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testEvaluateAccessRule_SubAccessRules() {
        AccessRule subAccessRule = new AccessRule();
        subAccessRule.setRule("$.test2");
        subAccessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        subAccessRule.setValue("value2");

        AccessRule accessRule = new AccessRule();
        accessRule.setRule("$.test");
        accessRule.setType(AccessRule.TypeNaming.ALL_EQUALS);
        accessRule.setValue("value");
        accessRule.setSubAccessRule(new HashSet<>(Collections.singletonList(subAccessRule)));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("test", "value");
        requestBody.put("test2", "value2");

        boolean result = accessRuleService.evaluateAccessRule(requestBody, accessRule);

        assertTrue(result);
    }

    @Test
    public void testGates() throws IOException {

        Assert.assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_matchGate, Map.class), GATE_resouceUUID));
        Assert.assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_matchGate, Map.class), GATE_has_expectedResultType));

    }

    @Test
    public void testWithGate_passGate_passCheck_all_string_contains() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_passGate_passCheck_string_contains, Map.class), AR_CategoryFilter_String_contains));

    }

    @Test
    public void testWithGate_passGate_notPassCheck_string_contains() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(getSample_passGate_passCheck_array_contains, Map.class), AR_CategoryFilter_String_contains));
    }

    @Test
    public void testWithGate_passGate_passCheck_Array_contains() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_Any_Contains.setGates(gates);
        Assert.assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(getSample_passGate_passCheck_array_contains, Map.class), AR_CategoryFilter_Any_Contains));

    }

    @Test
    public void testWithGate_passGate_notPassCheck() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(getSample_passGate_passCheck_array_contains, Map.class), AR_CategoryFilter_String_contains));

    }

    @Test
    public void testWithGate_notPassGate() throws IOException {
        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_no_pass_gate, Map.class), AR_CategoryFilter_String_contains));
    }

    /**
     * Testing merging functionality, empty nodes, and no such node
     *
     * @throws IOException
     */
    @Test
    public void testMerging_emptyNodes_noSuchNode() throws IOException {
        Set<AccessRule> inputAccessRules = new HashSet<>();
        inputAccessRules.add(AR_Fields_ALL_AGE);
        inputAccessRules.add(AR_Fields_ALL_SEX);

        Set<AccessRule> mergedAccessRules = accessRuleService.preProcessARBySortedKeys(inputAccessRules);

        Assert.assertEquals(1, mergedAccessRules.size());

        Assert.assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_UUID_8694e3d4_withFields_SEX_And_AGE, Map.class),
                mergedAccessRules.stream().findFirst().get()));

        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_UUID_8694e3d4_withFields_and_SEE_AGE_SEX, Map.class),
                mergedAccessRules.stream().findFirst().get()));

        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_UUID_8694e3d4_withEmptyFields, Map.class),
                mergedAccessRules.stream().findFirst().get()));

        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_UUID_8694e3d4_withNoFieldsNode, Map.class),
                mergedAccessRules.stream().findFirst().get()));
    }

    /**
     * AccessRule rule isEmpty and isNotEmpty are special cases,
     * we should have a test case especially for it
     *
     * @throws IOException
     */
    @Test
    public void testRuleIsEmpty_IsNotEmpty() throws IOException {
        Assert.assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_UUID_8694e3d4_withEmptyFields, Map.class), AR_Fields_IS_EMPTY));
        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_UUID_8694e3d4_withEmptyFields, Map.class), AR_Fields_IS_NOT_EMPTY));
    }

    /**
     * testing gates combination either all or any
     * @throws IOException
     */
    @Test
    public void testGatesAllorAny() throws IOException{
        AccessRule accessRuleGatesAllAny = new AccessRule();
        accessRuleGatesAllAny.setUuid(UUID.randomUUID());
        accessRuleGatesAllAny.setRule("$.queries..query.categoryFilter");
        accessRuleGatesAllAny.setType(AccessRule.TypeNaming.IS_NOT_EMPTY);
        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        gates.add(GATE_has_expectedResultType);
        accessRuleGatesAllAny.setGates(gates);

        // default is false, for testing, we explicitly set it here just for fluently reading code
        accessRuleGatesAllAny.setGateAnyRelation(false);
        // No gates applied, return false.
        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_gates_all_any, Map.class), accessRuleGatesAllAny));

        // the rules will deny the access, but since we set only evaluate gates with OR relationship,
        // while the gates passes, it will return true.
        // Here we test any relationship and evaluate only by gates.
        accessRuleGatesAllAny.setGateAnyRelation(true);
        accessRuleGatesAllAny.setEvaluateOnlyByGates(true);
        Assert.assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_gates_all_any, Map.class), accessRuleGatesAllAny));

    }

    /**
     * testing gates combination nested all and any
     * @throws IOException
     */
    @Test
    public void testGateNestedAllorAny() throws IOException {
        AccessRule accessRuleGatesAllandAny = new AccessRule();
        accessRuleGatesAllandAny.setUuid(UUID.randomUUID());
        accessRuleGatesAllandAny.setRule("$.queries..query.numericFilters.*");
        accessRuleGatesAllandAny.setType(AccessRule.TypeNaming.IS_NOT_EMPTY);

        // the relationship of the nestedGate here is
        // GATE_resouceUUID && GATE_has_expectedResultType && (GATE_has_categoryFilters || GATE_has_requiredFields)
        AccessRule orGate = new AccessRule();
        orGate.setUuid(UUID.randomUUID());
        orGate.setGateAnyRelation(true);
        orGate.setName("Gate_OR_for_GATE_has_categoryFilters_GATE_has_requiredFields");
        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_has_requiredFields);
        gates.add(GATE_has_categoryFilters);
        orGate.setGates(gates);
        orGate.setEvaluateOnlyByGates(true);

        Set<AccessRule> gates2 = new HashSet<>();
        gates2.add(orGate);
        gates2.add(GATE_resouceUUID);
        gates2.add(GATE_has_expectedResultType);
        accessRuleGatesAllandAny.setGates(gates2);

        Assert.assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_nestedGates, Map.class), accessRuleGatesAllandAny));

    }

    private Role createTestRole() {
        Role role = new Role();
        role.setName("TEST_ROLE");
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createTestPrivilege()));
        return role;
    }

    private Privilege createTestPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName("TEST_PRIVILEGE");
        privilege.setUuid(UUID.randomUUID());
        return privilege;
    }

    private void configureUserSecurityContext(User user) {
        CustomUserDetails customUserDetails = new CustomUserDetails(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(customUserDetails, null, customUserDetails.getAuthorities());
        when(securityContext.getAuthentication()).thenReturn(authentication);
    }

    private Role createSuperAdminRole() {
        Role role = new Role();
        role.setName(SecurityRoles.SUPER_ADMIN.name());
        role.setUuid(UUID.randomUUID());
        role.setPrivileges(Collections.singleton(createSuperAdminPrivilege()));
        return role;
    }

    private Privilege createSuperAdminPrivilege() {
        Privilege privilege = new Privilege();
        privilege.setName(AuthNaming.AuthRoleNaming.SUPER_ADMIN);
        privilege.setUuid(UUID.randomUUID());
        return privilege;
    }

    private User createTestUser() {
        User user = new User();
        user.setUuid(UUID.randomUUID());
        user.setRoles(new HashSet<>(Collections.singleton(createTestRole())));
        user.setSubject("TEST_SUBJECT");
        user.setEmail("test@email.com");
        user.setAcceptedTOS(new Date());
        user.setActive(true);
        return user;
    }

    private Application createTestApplication() {
        Application application = new Application();
        application.setUuid(UUID.randomUUID());
        application.setName("TEST_APPLICATION");
        application.setPrivileges(new HashSet<>());
        return application;
    }
}
