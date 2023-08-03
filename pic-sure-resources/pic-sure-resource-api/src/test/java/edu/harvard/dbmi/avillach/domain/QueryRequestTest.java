package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

public class QueryRequestTest extends TestCase {

    public void testShouldIgnoreMissingQueryId() throws JsonProcessingException {
        QueryRequest expected = new QueryRequestWithEq();
        expected.setQuery("{}");
        expected.setResourceCredentials(new HashMap<>());
        expected.setResourceUUID(UUID.fromString("364c958e-53bb-4be7-bd5b-44fcda1592bc"));
        expected.setCommonAreaUUID(null);

        ObjectMapper mapper = new ObjectMapper();
        String jsonWithNoCUUID =
            "{\"resourceCredentials\":{},\"query\":\"{}\",\"resourceUUID\":\"364c958e-53bb-4be7-bd5b-44fcda1592bc\"}";
        QueryRequest actual = mapper.readValue(jsonWithNoCUUID, QueryRequestWithEq.class);

        assertEquals(expected, actual);
    }

    /**
     * This is just to get comparison to work for the test.
     */
    private static final class QueryRequestWithEq extends QueryRequest {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof QueryRequestWithEq)) return false;
            QueryRequestWithEq that = (QueryRequestWithEq) o;
            return Objects.equals(getResourceCredentials(), that.getResourceCredentials()) && Objects.equals(getQuery(), that.getQuery()) && Objects.equals(getResourceUUID(), that.getResourceUUID()) && Objects.equals(getCommonAreaUUID(), that.getCommonAreaUUID());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getResourceCredentials(), getQuery(), getResourceUUID(), getCommonAreaUUID());
        }
    }
}