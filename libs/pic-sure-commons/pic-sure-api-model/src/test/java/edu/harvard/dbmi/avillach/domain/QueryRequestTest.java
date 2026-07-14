package edu.harvard.dbmi.avillach.domain;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.UUID;

public class QueryRequestTest {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void shouldSerializeGeneralQueryRequest() throws JsonProcessingException {
        GeneralQueryRequest expected = new GeneralQueryRequest();
        expected.setQuery(null);
        expected.setResourceCredentials(new HashMap<>());
        expected.setResourceUUID(UUID.randomUUID());
        String json = mapper.writeValueAsString(expected);

        QueryRequest actual = mapper.readValue(json, QueryRequest.class);

        Assert.assertEquals(GeneralQueryRequest.class, actual.getClass());
        Assert.assertEquals(expected.getResourceUUID(), actual.getResourceUUID());
    }

    @Test
    public void shouldSerializeRequestWithNoType() throws JsonProcessingException {
        // Make sure json without the @Type annotation doesn't break this
        String json = "{\"resourceCredentials\":{},\"query\":null,\"resourceUUID\":\"e4513cca-12c0-4fe2-b2fd-5d05b821056c\"}";
        QueryRequest actual = mapper.readValue(json, QueryRequest.class);

        Assert.assertEquals(GeneralQueryRequest.class, actual.getClass());
    }

    /**
     * Pins the fallback that makes HpdsQueryV3Controller#rejectInstitutionalQuery necessary. FederatedQueryRequest was removed, but
     * @JsonTypeInfo(defaultImpl = GeneralQueryRequest.class) resolves an UNKNOWN type id to the default impl before FAIL_ON_INVALID_SUBTYPE
     *                           is consulted, and @JsonIgnoreProperties(ignoreUnknown = true) swallows the surplus GIC fields. So a
     *                           federated body does not 400 -- it is silently reinterpreted. If this test ever fails, the 410 guard's
     *                           rationale has changed; read it before touching either.
     */
    @Test
    public void shouldDeserializeRemovedFederatedTypeAsGeneralQueryRequest() throws JsonProcessingException {
        String json = "{\"@type\":\"FederatedQueryRequest\",\"query\":\"q\"," + "\"commonAreaUUID\":\"" + UUID.randomUUID() + "\","
            + "\"institutionOfOrigin\":\"BCH\",\"requesterEmail\":\"alice@harvard.edu\"}";

        QueryRequest parsed = new ObjectMapper().readValue(json, QueryRequest.class);

        Assert.assertTrue("unknown @type must fall back to the defaultImpl", parsed instanceof GeneralQueryRequest);
        Assert.assertEquals("q", parsed.getQuery());
    }
}
