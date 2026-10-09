package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    public void testTypeMismatchIs400NotServerError() throws Exception {
        MethodArgumentTypeMismatchException ex =
            new MethodArgumentTypeMismatchException("not-a-type", ApiKeyType.class, "keyType", null, null);

        ResponseEntity<?> response = handler.handleException(ex, request());

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    public void testTypeMismatchNamesTheParameterWithoutEchoingValue() throws Exception {
        MethodArgumentTypeMismatchException ex =
            new MethodArgumentTypeMismatchException("not-a-type", ApiKeyType.class, "keyType", null, null);

        String body = String.valueOf(handler.handleException(ex, request()).getBody());

        assertTrue(body.contains("Bad Request"));
        assertTrue(body.contains(PicsureExceptionAdvice.invalidParameter("keyType")));
        assertFalse(body.contains("not-a-type"));
    }

    @Test
    public void testUnreadableBodyIs400WithoutParserDetails() throws Exception {
        HttpMessageNotReadableException ex =
            new HttpMessageNotReadableException("JSON parse error: raw-client-payload", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<?> response = handler.handleException(ex, request());

        assertEquals(400, response.getStatusCode().value());
        assertFalse(String.valueOf(response.getBody()).contains("raw-client-payload"));
    }

    @Test
    public void testRuntimeExceptionIs500WithoutItsMessage() {
        ResponseEntity<?> response = handler.handleRuntime(new IllegalStateException("internal detail"));

        String body = String.valueOf(response.getBody());
        assertEquals(500, response.getStatusCode().value());
        assertTrue(body.contains("Internal Server Error"));
        assertTrue(body.contains(PicsureExceptionAdvice.SERVER_ERROR));
        assertFalse(body.contains("internal detail"));
    }

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest());
    }
}
