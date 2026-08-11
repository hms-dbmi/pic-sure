package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.client.HttpClientErrorException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * PSAMA's error body used to be {@code PicSureResponseBody} -- {@code {message, content}} -- where {@code message} was often a generic
 * label ("Application error") and {@code content} carried the detail. It is now the same {@code {errorType, message, requestId}} every
 * other PIC-SURE service emits, with the detail promoted into {@code message} so consumers reading that field still get the useful text.
 */
class GlobalExceptionHandlerContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void assertBody(ResponseEntity<Map<String, Object>> response, HttpStatus status, String errorType, String message) {
        assertEquals(status, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertTrue(body != null && body.containsKey("errorType") && body.containsKey("message") && body.containsKey("requestId"));
        assertEquals(errorType, body.get("errorType"));
        assertEquals(message, body.get("message"));
        assertFalse(body.containsKey("content"), "the PicSureResponseBody envelope must be gone");
    }

    @Test
    void picsureExceptionCarriesItsOwnStatusAndType() {
        assertBody(
            handler.handlePicsureException(new PicsureException(HttpStatus.NOT_FOUND, "not_found", "Role not found - uuid: abc")),
            HttpStatus.NOT_FOUND, "not_found", "Role not found - uuid: abc"
        );
    }

    @Test
    void constraintViolationStaysA409() {
        assertBody(
            handler.handleConstraintViolation(new DataIntegrityViolationException("fk")), HttpStatus.CONFLICT, "conflict",
            "Cannot delete this resource as it's referenced by other entities in the system"
        );
    }

    @Test
    void accessDeniedStaysA403() {
        assertBody(
            handler.handleAccessDenied(new AccessDeniedException("nope")), HttpStatus.FORBIDDEN, "forbidden",
            "You do not have permission to perform this operation"
        );
    }

    @Test
    void notAuthorizedStaysA401() {
        assertBody(
            handler.handleNotAuthorized(new NotAuthorizedException("bad token")), HttpStatus.UNAUTHORIZED, "unauthorized", "bad token"
        );
    }

    @Test
    void usernameNotFoundStaysA401() {
        assertBody(
            handler.handleUsernameNotFound(new UsernameNotFoundException("no such user")), HttpStatus.UNAUTHORIZED, "unauthorized",
            "no such user"
        );
    }

    @Test
    void illegalArgumentStaysA400() {
        assertBody(
            handler.handleIllegalArgument(new IllegalArgumentException("bad uuid")), HttpStatus.BAD_REQUEST, "bad_request", "bad uuid"
        );
    }

    /** An upstream IdP's status is preserved rather than flattened, exactly as before. */
    @Test
    void upstreamClientErrorKeepsItsStatus() {
        ResponseEntity<Map<String, Object>> response =
            handler.handleHttpClientError(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("upstream_error", response.getBody().get("errorType"));
    }

    @Test
    void unmappedFailuresDoNotLeakInternals() {
        assertBody(
            handler.handleGenericException(new IllegalStateException("connection pool exhausted at com.foo.Bar:41")),
            HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred"
        );
    }

    /** {@code requestId} comes from the MDC the commons RequestIdFilter sets, which is how a user-reported error is found in the logs. */
    @Test
    void requestIdIsCarriedFromTheMdc() {
        MDC.put("requestId", "req-42");
        try {
            ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(new IllegalArgumentException("x"));
            assertEquals("req-42", response.getBody().get("requestId"));
        } finally {
            MDC.remove("requestId");
        }
    }
}
