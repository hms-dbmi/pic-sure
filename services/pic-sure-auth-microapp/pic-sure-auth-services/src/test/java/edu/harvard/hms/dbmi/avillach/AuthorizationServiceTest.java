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

/**
 * This test class is from the aspect of features in the code
 */
public class AuthorizationServiceTest extends AuthorizationService{


    ObjectMapper mapper = new ObjectMapper();


    private static AccessRule GATE_resouceUUID;
    private static AccessRule GATE_2;

    private static AccessRule AR_CategoryFilter_String_contains;
    private static AccessRule AR_CategoryFilter_Array_Contains;
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

    private static String sample_notPassGate = "{\"queries\":[{\n" +
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

    @BeforeClass
    public static void init() {
        GATE_resouceUUID = new AccessRule();
        GATE_resouceUUID.setType(AccessRule.TypeNaming.ALL_EQUALS);
        GATE_resouceUUID.setName("Gate_resoruceUUID");
        GATE_resouceUUID.setRule("$.queries..resourceUUID");
        GATE_resouceUUID.setValue("8694e3d4-5cb4-410f-8431-993445e6d3f6");

        AR_CategoryFilter_String_contains = new AccessRule();
        AR_CategoryFilter_String_contains.setName("AR_CategoryFilter");
        AR_CategoryFilter_String_contains.setRule("$.queries..fields.*");
        AR_CategoryFilter_String_contains.setType(AccessRule.TypeNaming.ALL_CONTAINS);
        AR_CategoryFilter_String_contains.setValue("\\demographics\\SEX\\");

        AR_CategoryFilter_Array_Contains = new AccessRule();
        AR_CategoryFilter_Array_Contains.setName("AR_CategoryFilter");
        AR_CategoryFilter_Array_Contains.setRule("$.queries..fields.*");
        AR_CategoryFilter_Array_Contains.setType(AccessRule.TypeNaming.ARRAY_CONTAINS);
        AR_CategoryFilter_Array_Contains.setValue("\\demographics\\SEX\\");


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
        AR_CategoryFilter_Array_Contains.setGates(gates);
        Assert.assertTrue(checkAccessRule(mapper.readValue(getSample_passGate_passCheck_array_contains, Map.class), AR_CategoryFilter_Array_Contains));

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
        Assert.assertTrue(checkAccessRule(mapper.readValue(sample_notPassGate, Map.class), AR_CategoryFilter_String_contains));
    }

}
