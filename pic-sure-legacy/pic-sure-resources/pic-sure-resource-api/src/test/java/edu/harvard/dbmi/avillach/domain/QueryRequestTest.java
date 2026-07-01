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
    public void shouldSerializeGICRequest() throws JsonProcessingException {
        FederatedQueryRequest expected = new FederatedQueryRequest();
        expected.setQuery(null);
        expected.setResourceCredentials(new HashMap<>());
        expected.setResourceUUID(UUID.randomUUID());
        expected.setCommonAreaUUID(UUID.randomUUID());
        expected.setInstitutionOfOrigin("Top secret institution (shh!)");
        String json = mapper.writeValueAsString(expected);

        QueryRequest actual = mapper.readValue(json, QueryRequest.class);

        Assert.assertEquals(FederatedQueryRequest.class, actual.getClass());
        Assert.assertEquals(expected.getResourceUUID(), actual.getResourceUUID());
    }

    @Test
    public void shouldSerializeRequestWithNoType() throws JsonProcessingException {
        // Make sure json without the @Type annotation doesn't break this
        String json = "{\"resourceCredentials\":{},\"query\":null,\"resourceUUID\":\"e4513cca-12c0-4fe2-b2fd-5d05b821056c\"}";
        QueryRequest actual = mapper.readValue(json, QueryRequest.class);

        Assert.assertEquals(GeneralQueryRequest.class, actual.getClass());
    }

    @Test
    public void shouldSerializeRequestWithNoTypeWithGICFields() throws JsonProcessingException {
        String json = "{\"resourceCredentials\":{},\"query\":null,\"resourceUUID\":\"716d744f-9c89-40af-b572-222c1b20848f\",\"commonAreaUUID\":\"a79e3da0-e1fa-4626-9873-41cffd5e9115\",\"institutionOfOrigin\":\"Top secret institution (shh!)\"}";
        QueryRequest actual = mapper.readValue(json, QueryRequest.class);

        // This is for backwards compatibility. An un-updated client should handle unknown fields gracefully
        Assert.assertEquals(GeneralQueryRequest.class, actual.getClass());
    }

    @Test
    public void shouldSerializeRequestWithGICTypeAndExtraField() throws JsonProcessingException {
        String json = "{\"@type\":\"FederatedQueryRequest\",\"madeUpField\":0,\"resourceCredentials\":{},\"query\":null,\"resourceUUID\":\"716d744f-9c89-40af-b572-222c1b20848f\",\"commonAreaUUID\":\"a79e3da0-e1fa-4626-9873-41cffd5e9115\",\"institutionOfOrigin\":\"Top secret institution (shh!)\"}";
        QueryRequest actual = mapper.readValue(json, QueryRequest.class);

        // This is for backwards compatibility.
        // An un-updated client should handle unknown fields gracefully, even when it's a GIC request
        Assert.assertEquals(FederatedQueryRequest.class, actual.getClass());
    }
}