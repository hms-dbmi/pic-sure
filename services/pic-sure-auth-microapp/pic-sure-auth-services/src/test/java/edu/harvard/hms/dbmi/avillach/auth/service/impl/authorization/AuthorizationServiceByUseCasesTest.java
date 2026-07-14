package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.repository.AccessRuleRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>This is a test class from the view of high level use cases (user input aspect)</p>
 * <br>
 * <p>This class should contain the following use cases:</p>
 * <ul>
 *    <li>A: Level 1 users must not include DATAFRAME as expectedResultType
 *           <br><b>This is for the more general level 1 and level 2 abstractions we already have in other projects.</b></li>
 *    <li>B: A user has access to \\demographics\\ but not to \\laboratory\\
 *           <br><b>Basic study-level access control</b></li>
 *    <li>C: A user has access to everything for COUNT expectedResultType, but only \\demographics\\ for DATAFRAME
 *           <br><b>This is level 1 for everything except level 2 for demographics Also emulates study level access where a user is level 1 for all studies and level 2 for a specific study </b>
 *           <br><b>Also emulates study level access where a user is level 1 for all studies and level 2 for a specific study</b>
 *           </li>
 *    <li>D: A user has access to everything for COUNT and CROSS_COUNT expectedResultTypes but only has DATAFRAME access if they include \\demographics\\SEX\\male as a filter
 *           <br><b>In this case male is being used to emulate a consent group based access control</b> </li>
 *    <li>E: A user has access to \\laboratory\\ if they have included \\demographics\\SEX\\male as a filter, and has access to \\examination\\ if they have included \\demographics\\SEX\\female as a filter
 *           <br><b>This is a more complex consent group based access control use-case, male is consent group A, female is consent group B</b></li>
 *    <li>F: A user has access to run queries with variantInfoFilters and specific variant category filters only. The user cannot include any other filters or select any fields and can only do COUNT queries.
 *           <br><b>This is the authentication only variant search functionality.</b> </li>
 * </ul>
 *
 * We also have a class testing from the aspect of design, which means each test case is just testing one feature.
 * @see AuthorizationServiceTest
 */
@SpringBootTest
@ContextConfiguration(classes = {AccessRuleService.class})
public class AuthorizationServiceByUseCasesTest {

    @MockBean
    private AccessRuleRepository accessRuleRepository;

    @Autowired
    private AccessRuleService accessRuleService;

    private final ObjectMapper mapper = new ObjectMapper();

    private static AccessRule rule_caseA;
    private static AccessRule rule_caseB;
    private static AccessRule rule_caseC;
    private static AccessRule rule_caseD;
    private static AccessRule rule_caseE;
    private static AccessRule rule_caseE_2;
    private static AccessRule rule_caseF;
    private static AccessRule rule_caseG;

    private static AccessRule AR_CategoryFilter_String_contains;
    private static AccessRule AR_CategoryFilter_Array_Contains;
    private static AccessRule AR_ExpectedResultType_String_contains;


    public static String sample_caseAB_pass = "{\n" +
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
            "    \"expectedResultType\": \"COUNT\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseAB_fail = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\laboratory\\\\SEX\\\\\": [\n" +
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
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseCD_pass = "{\n" +
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
            "    \"expectedResultType\": \"dataframe\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseCD_fail = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\",\n" +
            "        \"female\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"dataframe\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\",\n" +
            "      \"\\\\laboratory\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseCD_fail_2 = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\laboratory\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"dataframe\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseE_pass = "{\n" +
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
            "    \"expectedResultType\": \"dataframe\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\laboratory\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseE_2_pass = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"female\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"dataframe\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\examination\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseE_fail = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"nothing\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"dataframe\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\examination\\\\AGE\\\\\",\n" +
            "      \"\\\\laboratory\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseE_2_fail = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"nothing\"\n" +
            "      ],\n" +
            "      \"\\\\laboratory\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\demographics\\\\AGE\\\\\"\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"dataframe\",\n" +
            "    \"fields\": [\n" +
            "      \"\\\\demographics\\\\SEX\\\\\",\n" +
            "      \"\\\\examination\\\\AGE\\\\\"\n" +
            "    ]\n" +
            "  },\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseF_pass = "{\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"3,112222,112222,C,T\": [\n" +
            "        \"1/1\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "    ],\n" +
            "    \"variantInfoFilters\": [\n" +
            "      {\n" +
            "        \"categoryVariantInfoFilters\": {\n" +
            "          \"HD\": [\n" +
            "            \"\\\"Asthma,_severe\\\"\"\n" +
            "          ]\n" +
            "        },\n" +
            "        \"numericVariantInfoFilters\": {}\n" +
            "      }\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"COUNT\"\n" +
            "  },\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseF_fail = "{\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"3,112222,112222,C,T\": [\n" +
            "        \"1/1\"\n" +
            "      ],\n" +
            "      \"\\\\demographics\\\\SEX\\\\\": [\n" +
            "        \"male\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "    ],\n" +
            "    \"variantInfoFilters\": [\n" +
            "      {\n" +
            "        \"categoryVariantInfoFilters\": {\n" +
            "          \"HD\": [\n" +
            "            \"\\\"Asthma,_severe\\\"\"\n" +
            "          ]\n" +
            "        },\n" +
            "        \"numericVariantInfoFilters\": {}\n" +
            "      }\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"COUNT\"\n" +
            "  },\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseF_fail_2 = "{\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"3,112222,112222,C,T\": [\n" +
            "        \"1/1\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"COUNT\"\n" +
            "  },\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseF_fail_3 = "{\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"3,112222,112222,C,T\": [\n" +
            "        \"1/1\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "    ],\n" +
            "    \"variantInfoFilters\": [\n" +
            "      {\n" +
            "        \"categoryVariantInfoFilters\": {\n" +
            "          \"HD\": [\n" +
            "            \"\\\"Asthma,_severe\\\"\"\n" +
            "          ]\n" +
            "        },\n" +
            "        \"numericVariantInfoFilters\": {}\n" +
            "      }\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"CROSS_COUNT\"\n" +
            "  },\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseF_fail_4 = "{\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"3,112222,112222,C,T\": [\n" +
            "        \"1/1\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [\n" +
            "      \"\\\\000_UDN ID\\\\\"\n" +
            "    ],\n" +
            "    \"variantInfoFilters\": [\n" +
            "      {\n" +
            "        \"categoryVariantInfoFilters\": {\n" +
            "          \"HD\": [\n" +
            "            \"\\\"Asthma,_severe\\\"\"\n" +
            "          ]\n" +
            "        },\n" +
            "        \"numericVariantInfoFilters\": {}\n" +
            "      }\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"COUNT\"\n" +
            "  },\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    public static String sample_caseF_fail_5 = "{\n" +
            "  \"resourceUUID\": \"8e8c7ed0-87ea-4342-b8da-f939e46bac26\",\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"3,112222,112222,C,T\": [\n" +
            "        \"1/1\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {\"nothing\":null},\n" +
            "    \"requiredFields\": [\n" +
            "    ],\n" +
            "    \"variantInfoFilters\": [\n" +
            "      {\n" +
            "        \"categoryVariantInfoFilters\": {\n" +
            "          \"HD\": [\n" +
            "            \"\\\"Asthma,_severe\\\"\"\n" +
            "          ]\n" +
            "        },\n" +
            "        \"numericVariantInfoFilters\": {}\n" +
            "      }\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"COUNT\"\n" +
            "  },\n" +
            "  \"resourceCredentials\": {}\n" +
            "}";

    // Sample for anyRecordOf test case (Case G)
    public static String sample_caseG_pass = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "    },\n" +
            "    \"numericFilters\": {},\n" +
            "    \"requiredFields\": [],\n" +
            "    \"anyRecordOf\": [\n" +
            "      \"\\\\phs001001\\\\pht005655\\\\phv00354560\\\\age\\\\\"\n" +
            "    ],\n" +
            "    \"fields\": [\n" +
            "      \"\\\\_Topmed Study Accession with Subject ID\\\\\",\n" +
            "      \"\\\\_Parent Study Accession with Subject ID\\\\\"\n" +
            "    ],\n" +
            "    \"variantInfoFilters\": [\n" +
            "      {\n" +
            "        \"categoryVariantInfoFilters\": {},\n" +
            "        \"numericVariantInfoFilters\": {}\n" +
            "      }\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"COUNT\"\n" +
            "  },\n" +
            "  \"resourceUUID\": \"02e23f52-f354-4e8b-992c-d37c8b9ba140\"\n" +
            "}";

    public static String sample_caseG_fail = "{\n" +
            "  \"query\": {\n" +
            "    \"categoryFilters\": {\n" +
            "      \"\\\\_consents\\\\\": [\n" +
            "        \"phs000956.c2\",\"phs001189.c1\",\"tutorial-biolincc_digitalis\",\"phs001143.c1\",\"phs001032.c1\",\"phs001013.c1\",\"phs001013.c2\",\"phs000280.c1\",\"phs000284.c1\",\"phs000200.c1\",\"phs000280.c2\",\"phs001040.c1\",\"phs000951.c1\",\"phs000974.c1\",\"tutorial-biolincc_camp\",\"phs000997.c1\",\"phs000200.c2\",\"phs000007.c2\",\"phs000974.c2\",\"phs000007.c1\",\"phs000993.c1\",\"phs000179.c1\",\"phs000993.c2\",\"phs001062.c1\",\"phs000964.c3\",\"phs000964.c4\",\"phs000285.c2\",\"phs000285.c1\",\"tutorial-biolincc_framingham\",\"phs000964.c1\",\"phs000964.c2\",\"phs000287.c1\",\"phs001211.c2\",\"phs001211.c1\",\"phs000287.c2\",\"phs000287.c3\",\"phs000287.c4\",\"open_access-1000Genomes\",\"phs001024.c1\",\"phs000209.c1\",\"phs000209.c2\"\n" +
            "      ]\n" +
            "    },\n" +
            "    \"numericFilters\": {\"age\": {\"min\": 20, \"max\": 80}},\n" +
            "    \"requiredFields\": [\"\\\\demographics\\\\AGE\\\\\"],\n" +
            "    \"anyRecordOf\": [\n" +
            "      \"\\\\different_path\\\\different_value\\\\\"\n" +
            "    ],\n" +
            "    \"fields\": [\n" +
            "      \"\\\\_Topmed Study Accession with Subject ID\\\\\",\n" +
            "      \"\\\\_Parent Study Accession with Subject ID\\\\\"\n" +
            "    ],\n" +
            "    \"variantInfoFilters\": [\n" +
            "      {\n" +
            "        \"categoryVariantInfoFilters\": {},\n" +
            "        \"numericVariantInfoFilters\": {}\n" +
            "      }\n" +
            "    ],\n" +
            "    \"expectedResultType\": \"DATAFRAME\"\n" +
            "  },\n" +
            "  \"resourceUUID\": \"02e23f52-f354-4e8b-992c-d37c8b9ba140\"\n" +
            "}";

    @BeforeAll
    public static void init() {
        initialTestCaseA();
        initialTestCaseB();
        initialTestCaseC();
        initialTestCaseD();
        initialTestCaseE();
        initialTestCaseF();
        initialTestCaseG();
    }

    @BeforeEach
    public void setUp() {
        AccessRuleRepository accessRuleRepository = Mockito.mock(AccessRuleRepository.class);
        accessRuleService = new AccessRuleService(accessRuleRepository, "false", "false", "false", "false","false", "false");
    }

    @Test
    public void testCaseA() throws IOException {
        assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseAB_pass, Map.class), rule_caseA));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseAB_fail, Map.class), rule_caseA));
    }

    private static void initialTestCaseA(){
        rule_caseA = new AccessRule();
        rule_caseA.setUuid(UUID.randomUUID());
        rule_caseA.setType(AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE);
        rule_caseA.setName("rule_caseA");
        rule_caseA.setRule("$..expectedResultType");
//        rule_caseA.setRule("$..\\laboratory\\*");
        rule_caseA.setValue("DATAFRAME");
    }

    @Test
    public void testCaseB() throws IOException {
        assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseAB_pass, Map.class), rule_caseB));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseAB_fail, Map.class), rule_caseB));
    }

    private static void initialTestCaseB(){
        rule_caseB = new AccessRule();
        rule_caseB.setUuid(UUID.randomUUID());
        rule_caseB.setName("rule_caseB");
        rule_caseB.setRule("$..*");
        rule_caseB.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        rule_caseB.setValue("\\demographics\\");

        AccessRule rule_caseB_sub = new AccessRule();
        rule_caseB_sub.setUuid(UUID.randomUUID());
        rule_caseB_sub.setName("rule_caseB_sub");
        rule_caseB_sub.setRule("$..*");
        rule_caseB_sub.setType(AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE);
        rule_caseB_sub.setCheckMapNode(true);
        rule_caseB_sub.setValue("\\laboratory\\");
        Set<AccessRule> accessRuleSubSet = new HashSet<>();
        accessRuleSubSet.add(rule_caseB_sub);
        rule_caseB.setSubAccessRule(accessRuleSubSet);
    }

    @Test
    public void testCaseC() throws IOException {
        assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseCD_pass, Map.class), rule_caseC));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseCD_fail, Map.class), rule_caseC));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseCD_fail_2, Map.class), rule_caseC));
    }

    private static void initialTestCaseC(){
        rule_caseC = new AccessRule();
        rule_caseC.setUuid(UUID.randomUUID());
        rule_caseC.setName("rule_caseC");

        AccessRule rule_caseC_gate2 = new AccessRule();
        rule_caseC_gate2.setUuid(UUID.randomUUID());
        rule_caseC_gate2.setName("rule_caseC_gate2");
        rule_caseC_gate2.setRule("$.query.expectedResultType");
        rule_caseC_gate2.setType(AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE);
        rule_caseC_gate2.setValue("DATAFRAME");

        Set<AccessRule> rule_caseC_gates = new HashSet<>();
        rule_caseC_gates.add(rule_caseC_gate2);
        rule_caseC.setGates(rule_caseC_gates);

        AccessRule rule_caseC_subRule = new AccessRule();
        rule_caseC_subRule.setUuid(UUID.randomUUID());
        rule_caseC_subRule.setName("rule_caseC_subRule");
        rule_caseC_subRule.setRule("$.query.categoryFilters");
        rule_caseC_subRule.setCheckMapNode(true);
        rule_caseC_subRule.setCheckMapKeyOnly(true);
        rule_caseC_subRule.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        rule_caseC_subRule.setValue("\\demographics\\");
        Set<AccessRule> rule_caseC_subRules = new HashSet<>();
        rule_caseC_subRules.add(rule_caseC_subRule);
        rule_caseC.setSubAccessRule(rule_caseC_subRules);

        AccessRule rule_caseC_subRule2 = new AccessRule();
        rule_caseC_subRule2.setUuid(UUID.randomUUID());
        rule_caseC_subRule2.setName("rule_caseC_subRule");
        rule_caseC_subRule2.setRule("$.query.requiredFields");
        rule_caseC_subRule2.setCheckMapNode(true);
        rule_caseC_subRule2.setCheckMapKeyOnly(true);
        rule_caseC_subRule2.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        rule_caseC_subRule2.setValue("\\demographics\\");
        rule_caseC_subRules.add(rule_caseC_subRule2);

        AccessRule rule_caseC_subRule3 = new AccessRule();
        rule_caseC_subRule3.setUuid(UUID.randomUUID());
        rule_caseC_subRule3.setName("rule_caseC_subRule");
        rule_caseC_subRule3.setRule("$.query.fields");
        rule_caseC_subRule3.setCheckMapNode(true);
        rule_caseC_subRule3.setCheckMapKeyOnly(true);
        rule_caseC_subRule3.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        rule_caseC_subRule3.setValue("\\demographics\\");
        rule_caseC_subRules.add(rule_caseC_subRule3);
    }

    @Test
    public void testCaseD() throws IOException {
        assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseCD_pass, Map.class), rule_caseD));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseCD_fail, Map.class), rule_caseD));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseCD_fail_2, Map.class), rule_caseD));
    }

    private static void initialTestCaseD(){
        rule_caseD = new AccessRule();
        rule_caseD.setUuid(UUID.randomUUID());
        rule_caseD.setName("rule_caseD");
        rule_caseD.setRule("$.query.categoryFilters.\\demographics\\SEX\\");
        rule_caseD.setType(AccessRule.TypeNaming.ALL_EQUALS);
        rule_caseD.setValue("male");

        AccessRule rule_caseD_gate = new AccessRule();
        rule_caseD_gate.setUuid(UUID.randomUUID());
        rule_caseD_gate.setName("rule_caseD_gate");
        rule_caseD_gate.setRule("$.query.expectedResultType");
        rule_caseD_gate.setType(AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE);
        rule_caseD_gate.setValue("dataframe");
        Set<AccessRule> rule_caseD_gates = new HashSet<>();
        rule_caseD_gates.add(rule_caseD_gate);
        rule_caseD.setGates(rule_caseD_gates);
    }

    @Test
    public void testCaseE() throws IOException {
        assertTrue(
                accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_pass, Map.class), rule_caseE)
                || accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_pass, Map.class), rule_caseE_2)
        );

        assertTrue(
                accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_2_pass, Map.class), rule_caseE)
                        || accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_2_pass, Map.class), rule_caseE_2)
        );

        assertFalse(
                accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_fail, Map.class), rule_caseE)
                        || accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_fail, Map.class), rule_caseE_2)
        );

        assertFalse(
                accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_2_fail, Map.class), rule_caseE)
                        || accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseE_2_fail, Map.class), rule_caseE_2)
        );

    }

    private static void initialTestCaseE(){
        rule_caseE = new AccessRule();
        rule_caseE.setUuid(UUID.randomUUID());
        rule_caseE.setName("rule_caseE");
        rule_caseE.setRule("$.query.categoryFilters.\\demographics\\SEX\\");
        rule_caseE.setType(AccessRule.TypeNaming.ALL_EQUALS);
        rule_caseE.setValue("male");

        AccessRule rule_caseE_gate = new AccessRule();
        rule_caseE_gate.setUuid(UUID.randomUUID());
        rule_caseE_gate.setName("rule_caseE_gate");
        rule_caseE_gate.setRule("$..*");
        rule_caseE_gate.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        rule_caseE_gate.setValue("\\laboratory\\");
        rule_caseE_gate.setCheckMapNode(true);
        Set<AccessRule> rule_caseE_gates = new HashSet<>();
        rule_caseE_gates.add(rule_caseE_gate);
        rule_caseE.setGates(rule_caseE_gates);

        rule_caseE_2 = new AccessRule();
        rule_caseE_2.setUuid(UUID.randomUUID());
        rule_caseE_2.setName("rule_caseE_2");
        rule_caseE_2.setRule("$.query.categoryFilters.\\demographics\\SEX\\");
        rule_caseE_2.setType(AccessRule.TypeNaming.ALL_EQUALS);
        rule_caseE_2.setValue("female");

        AccessRule rule_caseE_2_gate = new AccessRule();
        rule_caseE_2_gate.setUuid(UUID.randomUUID());
        rule_caseE_2_gate.setName("rule_caseE_2_gate");
        rule_caseE_2_gate.setRule("$..*");
        rule_caseE_2_gate.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        rule_caseE_2_gate.setValue("\\examination\\");
        rule_caseE_2_gate.setCheckMapNode(true);
        Set<AccessRule> rule_caseE_2_gates = new HashSet<>();
        rule_caseE_2_gates.add(rule_caseE_2_gate);
        rule_caseE_2.setGates(rule_caseE_2_gates);
    }


    @Test
    public void testCaseF() throws IOException {
        assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseF_pass, Map.class), rule_caseF));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseF_fail, Map.class), rule_caseF));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseF_fail_2, Map.class), rule_caseF));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseF_fail_3, Map.class), rule_caseF));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseF_fail_4, Map.class), rule_caseF));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseF_fail_5, Map.class), rule_caseF));
    }

    private static void initialTestCaseF(){
        rule_caseF = new AccessRule();
        rule_caseF.setUuid(UUID.randomUUID());
        rule_caseF.setName("rule_caseF");
        rule_caseF.setRule("$.query");
        rule_caseF.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        rule_caseF.setValue("variantInfoFilters");
        rule_caseF.setCheckMapNode(true);
        rule_caseF.setCheckMapKeyOnly(true);
        Set<AccessRule> rule_caseF_subRules = new HashSet<>();
        rule_caseF.setSubAccessRule(rule_caseF_subRules);

        AccessRule rule_caseF_subRule = new AccessRule();
        rule_caseF_subRule.setUuid(UUID.randomUUID());
        rule_caseF_subRule.setName("rule_caseF_subRule");
        rule_caseF_subRule.setRule("$.query.categoryFilters");
        rule_caseF_subRule.setCheckMapNode(true);
        rule_caseF_subRule.setCheckMapKeyOnly(true);
        rule_caseF_subRule.setType(AccessRule.TypeNaming.ALL_REG_MATCH);
        rule_caseF_subRule.setValue("^[0-9]*,[0-9]*,[0-9]*,[ATCG],[ATCG]$");
        rule_caseF_subRules.add(rule_caseF_subRule);


        AccessRule rule_caseF_subRule_2 = new AccessRule();
        rule_caseF_subRule_2.setUuid(UUID.randomUUID());
        rule_caseF_subRule_2.setName("rule_caseF_subRule_2");
        rule_caseF_subRule_2.setRule("$.query.expectedResultType");
        rule_caseF_subRule_2.setType(AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE);
        rule_caseF_subRule_2.setValue("COUNT");
        rule_caseF_subRules.add(rule_caseF_subRule_2);

        AccessRule rule_caseF_subRule_3 = new AccessRule();
        rule_caseF_subRule_3.setUuid(UUID.randomUUID());
        rule_caseF_subRule_3.setName("rule_caseF_subRule_3");
        rule_caseF_subRule_3.setRule("$.query.numericFilters");
        rule_caseF_subRule_3.setType(AccessRule.TypeNaming.IS_EMPTY);
        rule_caseF_subRules.add(rule_caseF_subRule_3);

        AccessRule rule_caseF_subRule_4 = new AccessRule();
        rule_caseF_subRule_4.setUuid(UUID.randomUUID());
        rule_caseF_subRule_4.setName("rule_caseF_subRule_4");
        rule_caseF_subRule_4.setRule("$.query.requiredFields");
        rule_caseF_subRule_4.setType(AccessRule.TypeNaming.IS_EMPTY);
        rule_caseF_subRules.add(rule_caseF_subRule_4);
    }

    @Test
    public void testCaseG() throws IOException {
        assertTrue(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseG_pass, Map.class), rule_caseG));
        assertFalse(accessRuleService.evaluateAccessRule(mapper.readValue(sample_caseG_fail, Map.class), rule_caseG));
    }

    private static void initialTestCaseG(){
        rule_caseG = new AccessRule();
        rule_caseG.setUuid(UUID.randomUUID());
        rule_caseG.setName("rule_caseG");
        rule_caseG.setRule("$.query");
        rule_caseG.setType(AccessRule.TypeNaming.ANY_CONTAINS);
        rule_caseG.setValue("anyRecordOf");
        rule_caseG.setCheckMapNode(true);
        rule_caseG.setCheckMapKeyOnly(true);

        Set<AccessRule> subRules = new HashSet<>();
        rule_caseG.setSubAccessRule(subRules);

        // Sub-rule 1: Check that expectedResultType is COUNT
        AccessRule subRule1 = new AccessRule();
        subRule1.setUuid(UUID.randomUUID());
        subRule1.setName("rule_caseG_subRule1");
        subRule1.setRule("$.query.expectedResultType");
        subRule1.setType(AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE);
        subRule1.setValue("COUNT");
        subRules.add(subRule1);

        // Sub-rule 2: Check that numericFilters is empty
        AccessRule subRule2 = new AccessRule();
        subRule2.setUuid(UUID.randomUUID());
        subRule2.setName("rule_caseG_subRule2");
        subRule2.setRule("$.query.numericFilters");
        subRule2.setType(AccessRule.TypeNaming.IS_EMPTY);
        subRules.add(subRule2);

        // Sub-rule 3: Check that requiredFields is empty
        AccessRule subRule3 = new AccessRule();
        subRule3.setUuid(UUID.randomUUID());
        subRule3.setName("rule_caseG_subRule3");
        subRule3.setRule("$.query.requiredFields");
        subRule3.setType(AccessRule.TypeNaming.IS_EMPTY);
        subRules.add(subRule3);

        // Sub-rule 4: Check that anyRecordOf contains a specific path
        AccessRule subRule4 = new AccessRule();
        subRule4.setUuid(UUID.randomUUID());
        subRule4.setName("rule_caseG_subRule4");
        subRule4.setRule("$.query.anyRecordOf[0]");
        subRule4.setType(AccessRule.TypeNaming.ALL_EQUALS);
        subRule4.setValue("\\phs001001\\pht005655\\phv00354560\\age\\");
        subRules.add(subRule4);
    }
}
