package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    public void testTypeMismatchIs400NotServerError() {
        MethodArgumentTypeMismatchException ex =
            new MethodArgumentTypeMismatchException("not-a-type", ApiKeyType.class, "keyType", null, null);

        ResponseEntity<?> response = handler.handleTypeMismatch(ex);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    public void testTypeMismatchNamesParameterAndEnumConstantsWithoutEchoingValue() {
        MethodArgumentTypeMismatchException ex =
            new MethodArgumentTypeMismatchException("not-a-type", ApiKeyType.class, "keyType", null, null);

        String body = String.valueOf(handler.handleTypeMismatch(ex).getBody());

        assertTrue(body.contains("keyType"));
        assertTrue(body.contains("USER"));
        assertTrue(body.contains("PLATFORM"));
        // the offending value is client-controlled and must not be reflected
        assertFalse(body.contains("not-a-type"));
    }

    @Test
    public void testUnreadableBodyIs400WithoutParserDetails() {
        HttpMessageNotReadableException ex =
            new HttpMessageNotReadableException("JSON parse error: raw-client-payload", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<?> response = handler.handleUnreadableBody(ex);

        assertEquals(400, response.getStatusCode().value());
        assertFalse(String.valueOf(response.getBody()).contains("raw-client-payload"));
    }

    @Test
    public void testTypeMismatchOnNonEnumNamesExpectedType() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException("abc", int.class, "page", null, null);

        String body = String.valueOf(handler.handleTypeMismatch(ex).getBody());

        assertEquals(400, handler.handleTypeMismatch(ex).getStatusCode().value());
        assertTrue(body.contains("page"));
    }
}
