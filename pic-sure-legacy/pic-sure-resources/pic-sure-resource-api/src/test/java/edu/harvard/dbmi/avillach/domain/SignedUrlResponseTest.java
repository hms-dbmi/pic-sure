package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignedUrlResponseTest {

    @Test
    public void testJacksonSerialization() throws JsonProcessingException {
        SignedUrlResponse signedUrlResponse = new SignedUrlResponse("http://google.com/");
        ObjectMapper objectMapper = new ObjectMapper();
        String serialized = objectMapper.writeValueAsString(signedUrlResponse);
        SignedUrlResponse deserialized = objectMapper.readValue(serialized, SignedUrlResponse.class);
        assertEquals(signedUrlResponse.getSignedUrl(), deserialized.getSignedUrl());
    }
}
