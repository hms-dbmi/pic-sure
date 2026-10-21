package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.model.response.OpenAccessValidationResponse.KeyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OpenAccessValidationResponseTest {

    @Test
    public void testToStringRedactsRefreshedToken() {
        String token = "picsure_s_eyJhbGciOiJIUzI1NiJ9.session.signature";
        OpenAccessValidationResponse response = new OpenAccessValidationResponse(true, KeyType.USER, "key-id", "AbCd1234", null, token);

        assertFalse(response.toString().contains(token));
        assertTrue(response.toString().contains("refreshedToken=REDACTED"));
        assertTrue(response.toString().contains("key-id"));
        assertTrue(OpenAccessValidationResponse.granted(null).toString().contains("refreshedToken=null"));
    }
}
