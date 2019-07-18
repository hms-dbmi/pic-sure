package edu.harvard.hms.dbmi.avillach;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.service.auth.AuthorizationService;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This test class is from the aspect of features in the code
 */
public class AuthorizationServiceTest extends AuthorizationService{


    ObjectMapper mapper = new ObjectMapper();


    private static AccessRule GATE_resouceUUID;
    private static AccessRule GATE_2;

    private static AccessRule AR_CategoryFilter_String_contains;
    private static AccessRule AR_CategoryFilter_Any_Contains;
    private static AccessRule AR_Fields_ALL_SEX;
    private static AccessRule AR_Fields_ALL_AGE;
    private static AccessRule AR_Fields_IS_EMPTY;
    private static AccessRule AR_Fields_IS_NOT_EMPTY;
    private static AccessRule AR_ExpectedResultType_String_contains;

    private static String sample_matchGate = "{\"queries\":[{\"resourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\",\"query\":{\"expectedResultType\":\"DATAFRAME\"}}]}";

    private static String sample_passGate = "{\"queries\":[{\n" +
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

    private static String sample_UUID_8694e3d4_withFields_SEE_AGE_SEX = "{\"queries\":[{\n" +
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

    @Test
    public void testGate_resourceUUID() throws IOException {

        Assert.assertTrue(checkAccessRule(mapper.readValue(sample_matchGate, Map.class), GATE_resouceUUID));

    }

    @Test
    public void testWithGate_passGate_passCheck_all_string_contains() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertTrue(checkAccessRule(mapper.readValue(sample_passGate_passCheck_string_contains, Map.class), AR_CategoryFilter_String_contains));

    }

    @Test
    public void testWithGate_passGate_notPassCheck_string_contains() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertFalse(checkAccessRule(mapper.readValue(getSample_passGate_passCheck_array_contains, Map.class), AR_CategoryFilter_String_contains));

    }

    @Test
    public void testWithGate_passGate_passCheck_Array_contains() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_Any_Contains.setGates(gates);
        Assert.assertTrue(checkAccessRule(mapper.readValue(getSample_passGate_passCheck_array_contains, Map.class), AR_CategoryFilter_Any_Contains));

    }

    @Test
    public void testWithGate_passGate_notPassCheck() throws IOException{

        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertFalse(checkAccessRule(mapper.readValue(getSample_passGate_passCheck_array_contains, Map.class), AR_CategoryFilter_String_contains));

    }

    @Test
    public void testWithGate_notPassGate() throws IOException {
        Set<AccessRule> gates = new HashSet<>();
        gates.add(GATE_resouceUUID);
        AR_CategoryFilter_String_contains.setGates(gates);
        Assert.assertTrue(checkAccessRule(mapper.readValue(sample_no_pass_gate, Map.class), AR_CategoryFilter_String_contains));
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

        Set<AccessRule> mergedAccessRules = preProcessARBySortedKeys(inputAccessRules);

        Assert.assertEquals(1, mergedAccessRules.size());

        Assert.assertTrue(checkAccessRule(mapper.readValue(sample_UUID_8694e3d4_withFields_SEX_And_AGE, Map.class),
                mergedAccessRules.stream().findFirst().get()));

        Assert.assertFalse(checkAccessRule(mapper.readValue(sample_UUID_8694e3d4_withFields_SEE_AGE_SEX, Map.class),
                mergedAccessRules.stream().findFirst().get()));

        Assert.assertFalse(checkAccessRule(mapper.readValue(sample_UUID_8694e3d4_withEmptyFields, Map.class),
                mergedAccessRules.stream().findFirst().get()));

        Assert.assertFalse(checkAccessRule(mapper.readValue(sample_UUID_8694e3d4_withNoFieldsNode, Map.class),
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
        Assert.assertTrue(checkAccessRule(mapper.readValue(sample_UUID_8694e3d4_withEmptyFields, Map.class), AR_Fields_IS_EMPTY));
        Assert.assertFalse(checkAccessRule(mapper.readValue(sample_UUID_8694e3d4_withEmptyFields, Map.class), AR_Fields_IS_NOT_EMPTY));
    }


}
