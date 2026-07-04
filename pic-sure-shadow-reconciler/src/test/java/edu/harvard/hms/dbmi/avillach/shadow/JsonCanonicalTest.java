package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonCanonicalTest {

    private final ObjectMapper M = new ObjectMapper();

    private JsonNode n(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void objectKeyOrderIgnored() throws Exception {
        assertTrue(JsonCanonical.equalIgnoringOrder(n("{\"a\":1,\"b\":2}"), n("{\"b\":2,\"a\":1}")));
    }

    @Test
    void differentValuesNotEqual() throws Exception {
        assertFalse(JsonCanonical.equalIgnoringOrder(n("{\"a\":1}"), n("{\"a\":2}")));
    }

    @Test
    void nestedObjectKeyOrderIgnored() throws Exception {
        assertTrue(JsonCanonical.equalIgnoringOrder(n("{\"a\":{\"x\":1,\"y\":2}}"), n("{\"a\":{\"y\":2,\"x\":1}}")));
    }

    @Test
    void arrayOrderMatters() throws Exception {
        assertFalse(JsonCanonical.equalIgnoringOrder(n("{\"a\":[1,2]}"), n("{\"a\":[2,1]}")));
        assertTrue(JsonCanonical.equalIgnoringOrder(n("{\"a\":[1,2]}"), n("{\"a\":[1,2]}")));
    }

    @Test
    void bothNullIsEqual() throws Exception {
        assertTrue(JsonCanonical.equalIgnoringOrder(null, null));
        assertTrue(JsonCanonical.equalIgnoringOrder(n("null"), null));
    }

    @Test
    void detectsResourceCredentials() throws Exception {
        assertTrue(JsonCanonical.containsResourceCredentials(n("{\"query\":{\"resourceCredentials\":{\"x\":\"y\"}}}")));
        assertFalse(JsonCanonical.containsResourceCredentials(n("{\"query\":{}}")));
    }

    @Test
    void detectsResourceCredentialsNestedInArray() throws Exception {
        assertTrue(JsonCanonical.containsResourceCredentials(n("{\"a\":[{\"resourceCredentials\":{}}]}")));
    }
}
