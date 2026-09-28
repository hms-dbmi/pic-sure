package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAccessValidationTest {

    @Test
    void toStringRedactsRefreshedToken() {
        String token = "picsure_s_eyJhbGciOiJIUzI1NiJ9.session.signature";
        OpenAccessValidation validation = new OpenAccessValidation(true, "SESSION", "session-sub", null, null, token);

        assertThat(validation.toString()).doesNotContain(token).contains("refreshedToken=REDACTED").contains("session-sub");
        assertThat(OpenAccessValidation.fromBoolean(true).toString()).contains("refreshedToken=null");
    }
}
