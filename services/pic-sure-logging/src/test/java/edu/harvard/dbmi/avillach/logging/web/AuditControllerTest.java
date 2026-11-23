package edu.harvard.dbmi.avillach.logging.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "picsure.logging.api-key=integration-test-key")
class AuditControllerTest {

    private static final String API_KEY = "integration-test-key";

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private ResponseEntity<String> postAudit(String body, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return rest.exchange("/audit", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void validRequestReturns202() {
        ResponseEntity<String> response = postAudit("{\"event_type\":\"QUERY\",\"action\":\"execute\"}", API_KEY);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).contains("accepted");
    }

    @Test
    void missingApiKeyReturns401() {
        assertThat(postAudit("{\"event_type\":\"QUERY\"}", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void wrongApiKeyReturns401() {
        assertThat(postAudit("{\"event_type\":\"QUERY\"}", "wrong-key").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void malformedJsonReturns400() {
        assertThat(postAudit("not-json", API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    /**
     * An empty body raises HttpMessageNotReadableException before the controller runs. Javalin returned 400 here (readValue("") threw). A
     * bare @ExceptionHandler(Exception.class) catch-all would turn it into 500 — this pins the contract.
     */
    @Test
    void emptyBodyReturns400() {
        assertThat(postAudit("", API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    /** The catch-all advice must not mask Spring MVC's own 4xx as 500. */
    @Test
    void wrongHttpMethodIsNotAServerError() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        ResponseEntity<String> response = rest.exchange("/audit", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().is5xxServerError()).isFalse();
    }

    @Test
    void missingEventTypeReturns400() {
        assertThat(postAudit("{\"action\":\"execute\"}", API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void blankEventTypeReturns400() {
        assertThat(postAudit("{\"event_type\":\"   \"}", API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownFieldsAreIgnored() {
        assertThat(postAudit("{\"event_type\":\"TEST\",\"unknown_field\":\"v\"}", API_KEY).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void missingContentTypeStillReturns202NotUnsupportedMediaType() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", API_KEY);
        ResponseEntity<String> response =
            rest.exchange("/audit", HttpMethod.POST, new HttpEntity<>("{\"event_type\":\"TEST\"}", headers), String.class);

        assertThat(response.getStatusCode().value()).isNotEqualTo(415);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void nestingBeyondDepth10Returns400() {
        String json = "{\"event_type\":\"TEST\",\"metadata\":" + "{\"k\":".repeat(15) + "\"v\"" + "}".repeat(15) + "}";

        assertThat(postAudit(json, API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void nestingAtDepth10Returns202() {
        String json = "{\"event_type\":\"TEST\",\"metadata\":" + "{\"k\":".repeat(8) + "\"v\"" + "}".repeat(8) + "}";

        assertThat(postAudit(json, API_KEY).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void stringLongerThan10KbReturns400() {
        String json = "{\"event_type\":\"TEST\",\"action\":\"" + "x".repeat(11_000) + "\"}";

        assertThat(postAudit(json, API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void metadataOver50KeysReturns400() {
        assertThat(postAudit("{\"event_type\":\"TEST\",\"metadata\":" + jsonMap(51) + "}", API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void metadataAtExactly50KeysReturns202() {
        assertThat(postAudit("{\"event_type\":\"TEST\",\"metadata\":" + jsonMap(50) + "}", API_KEY).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void errorOver20KeysReturns400() {
        assertThat(postAudit("{\"event_type\":\"TEST\",\"error\":" + jsonMap(21) + "}", API_KEY).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void errorAtExactly20KeysReturns202() {
        assertThat(postAudit("{\"event_type\":\"TEST\",\"error\":" + jsonMap(20) + "}", API_KEY).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void bodyOver1MbReturns413() {
        String json = "{\"event_type\":\"TEST\",\"action\":\"" + "x".repeat(1_100_000) + "\"}";

        assertThat(postAudit(json, API_KEY).getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void unauthenticatedOversizedBodyReturns401NotTooLarge() {
        String json = "{\"event_type\":\"TEST\",\"action\":\"" + "x".repeat(1_100_000) + "\"}";

        assertThat(postAudit(json, null).getStatusCode().value()).isEqualTo(401);
    }

    /**
     * A chunked request declares no Content-Length, so RequestSizeLimitFilter cannot reject it up front. The counting wrapper trips
     * mid-read and throws RequestBodyTooLargeException, which ApiExceptionHandler maps to 413.
     *
     * This is the test that proves the exception must be unchecked: an IOException here would be rewrapped by Spring's argument resolver as
     * HttpMessageNotReadableException and return 400. TestRestTemplate always sets Content-Length, so this uses the JDK client, whose
     * BodyPublishers.ofInputStream has unknown length and therefore chunks.
     */
    @Test
    void chunkedBodyOverTheCapReturns413() throws Exception {
        // Only just over the cap, so the client has sent almost the whole body before the
        // server responds — keeps the early-response/connection-reset race narrow.
        byte[] payload = ("{\"event_type\":\"TEST\",\"action\":\"" + "x".repeat(1_048_576) + "\"}").getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/audit")).header("X-API-Key", API_KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload))).build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    private static String jsonMap(int keys) {
        return IntStream.rangeClosed(1, keys).mapToObj(i -> "\"key" + i + "\":\"val" + i + "\"").collect(Collectors.joining(",", "{", "}"));
    }
}
