package edu.harvard.dbmi.avillach.visualization.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleVisualizationException_clientError_returns400() {
        BadVisualizationRequestException e = new BadVisualizationRequestException("Could not parse query: bad format");

        ResponseEntity<Map<String, String>> response = handler.handleBadVisualizationRequest(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Could not parse query: bad format", response.getBody().get("error"));
    }

    @Test
    void handleVisualizationException_hpdsHttpError_returns502() {
        HttpServerErrorException cause =
            HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null, null, null);
        HpdsUpstreamException e = new HpdsUpstreamException("HPDS query failed with status 500: Internal Server Error", cause);

        ResponseEntity<Map<String, String>> response = handler.handleHpdsUpstreamException(e);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertTrue(response.getBody().get("error").contains("HPDS query failed"));
    }

    @Test
    void handleVisualizationException_hpds4xxError_returns502() {
        HttpClientErrorException cause = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null);
        HpdsUpstreamException e = new HpdsUpstreamException("HPDS query failed with status 404: Not Found", cause);

        ResponseEntity<Map<String, String>> response = handler.handleHpdsUpstreamException(e);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    @Test
    void handleVisualizationException_networkError_returns502() {
        ResourceAccessException cause = new ResourceAccessException("Connection refused", new IOException("Connection refused"));
        HpdsUpstreamException e = new HpdsUpstreamException("HPDS query failed: Connection refused", cause);

        ResponseEntity<Map<String, String>> response = handler.handleHpdsUpstreamException(e);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertTrue(response.getBody().get("error").contains("Connection refused"));
    }

    @Test
    void handleVisualizationException_configError_returns500() {
        VisualizationConfigurationException e =
            new VisualizationConfigurationException("Authorized HPDS resource UUID (hpds.resource.authorized.uuid) is not configured");

        ResponseEntity<Map<String, String>> response = handler.handleVisualizationConfigurationException(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().get("error").contains("not configured"));
    }

    @Test
    void handleValidationException_returns400WithFieldErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(null, "request");
        bindingResult.addError(new FieldError("request", "query", "must not be null"));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Map<String, String>> response = handler.handleValidationException(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("error").contains("query"));
        assertTrue(response.getBody().get("error").contains("must not be null"));
    }

    @Test
    void handleUnreadableMessage_returns400() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException("Could not read JSON", (Throwable) null, null);

        ResponseEntity<Map<String, String>> response = handler.handleUnreadableMessage(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Malformed request body", response.getBody().get("error"));
    }

    @Test
    void handleIllegalArgument_returns400() {
        IllegalArgumentException e = new IllegalArgumentException("invalid parameter");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid parameter", response.getBody().get("error"));
    }

    @Test
    void handleGenericException_returns500() {
        Exception e = new RuntimeException("something unexpected");

        ResponseEntity<Map<String, String>> response = handler.handleGenericException(e);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Internal server error", response.getBody().get("error"));
    }
}
