package edu.harvard.hms.dbmi.avillach.commons.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PicsureExceptionTest {

    @Test
    void exposesStatusErrorTypeAndMessage() {
        PicsureException exception = new PicsureException(HttpStatus.UNAUTHORIZED, "auth.missing_token", "No authorization header found.");

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exception.getErrorType()).isEqualTo("auth.missing_token");
        assertThat(exception.getMessage()).isEqualTo("No authorization header found.");
    }

    @Test
    void isARuntimeException() {
        assertThat(new PicsureException(HttpStatus.FORBIDDEN, "auth.forbidden", "nope")).isInstanceOf(RuntimeException.class);
    }
}
