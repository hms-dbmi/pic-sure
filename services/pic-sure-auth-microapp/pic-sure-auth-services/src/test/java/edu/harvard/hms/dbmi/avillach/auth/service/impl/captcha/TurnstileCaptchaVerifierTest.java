package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Uses a real RestTemplate behind MockRestServiceServer (not a mocked template) so the default error handler, form encoding, and message
 * converters — all part of the fail-closed contract — are exercised.
 */
public class TurnstileCaptchaVerifierTest {

    private static final String URL = "https://siteverify.example/turnstile";
    private static final String TOKEN = "XXXX.DUMMY.TOKEN";

    private MockRestServiceServer server;
    private TurnstileCaptchaVerifier verifier;

    @BeforeEach
    public void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        verifier = new TurnstileCaptchaVerifier(restTemplate, new ObjectMapper(), "test-secret", URL);
    }

    private void respondWith(String body) {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    public void testAcceptsSuccessfulVerification() {
        respondWith("{\"success\": true, \"action\": \"generate-api-key\"}");

        assertTrue(verifier.verify(TOKEN, "203.0.113.7"));
        server.verify();
    }

    @Test
    public void testAcceptsSuccessWithoutAction() {
        respondWith("{\"success\": true}");

        assertTrue(verifier.verify(TOKEN, null));
    }

    @Test
    public void testSendsFormEncodedSecretTokenAndIp() {
        MultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("secret", "test-secret");
        expected.add("response", TOKEN);
        expected.add("remoteip", "203.0.113.7");
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED)).andExpect(content().formData(expected))
            .andRespond(withSuccess("{\"success\": true}", MediaType.APPLICATION_JSON));

        assertTrue(verifier.verify(TOKEN, "203.0.113.7"));
        server.verify();
    }

    @Test
    public void testOmitsRemoteIpWhenAbsent() {
        MultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("secret", "test-secret");
        expected.add("response", TOKEN);
        server.expect(requestTo(URL)).andExpect(content().formData(expected))
            .andRespond(withSuccess("{\"success\": true}", MediaType.APPLICATION_JSON));

        assertTrue(verifier.verify(TOKEN, null));
        server.verify();
    }

    @Test
    public void testRejectsFailedVerification() {
        respondWith("{\"success\": false, \"error-codes\": [\"invalid-input-response\"]}");

        assertFalse(verifier.verify(TOKEN, null));
    }

    @Test
    public void testRejectsTokenForDifferentAction() {
        respondWith("{\"success\": true, \"action\": \"login\"}");

        assertFalse(verifier.verify(TOKEN, null));
    }

    // no expectation is registered: any HTTP call would throw an AssertionError, which the
    // verifier's fail-closed catch (Exception) does not swallow
    @Test
    public void testRejectsBlankTokenWithoutCallingCloudflare() {
        assertFalse(verifier.verify(null, "203.0.113.7"));
        assertFalse(verifier.verify("  ", "203.0.113.7"));

        server.verify();
    }

    @Test
    public void testRejectsClientErrorResponse() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"success\": false}"));

        assertFalse(verifier.verify(TOKEN, null));
    }

    @Test
    public void testRejectsServerErrorResponse() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertFalse(verifier.verify(TOKEN, null));
    }

    @Test
    public void testRejectsRedirectResponse() {
        server.expect(requestTo(URL)).andRespond(
            withStatus(HttpStatus.FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\": true, \"action\": \"generate-api-key\"}")
        );

        assertFalse(verifier.verify(TOKEN, null));
    }

    @Test
    public void testProductionClientDoesNotFollowRedirects() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer redirectServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = redirectServer.getAddress().getPort();
        redirectServer.createContext("/siteverify", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/redirected");
            exchange.sendResponseHeaders(HttpStatus.TEMPORARY_REDIRECT.value(), -1);
            exchange.close();
        });
        redirectServer.createContext("/redirected", exchange -> {
            redirectedRequests.incrementAndGet();
            byte[] response = "{\"success\": true, \"action\": \"generate-api-key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(HttpStatus.OK.value(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        redirectServer.start();

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            TurnstileCaptchaVerifier productionVerifier =
                new TurnstileCaptchaVerifier(httpClient, new ObjectMapper(), "test-secret", "http://127.0.0.1:" + port + "/siteverify");

            assertFalse(productionVerifier.verify(TOKEN, null));
            assertEquals(0, redirectedRequests.get());
        } finally {
            redirectServer.stop(0);
        }
    }

    @Test
    public void testRejectsWhenCloudflareIsUnreachable() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("connect timed out")));

        assertFalse(verifier.verify(TOKEN, null));
    }

    @Test
    public void testRejectsMalformedResponse() {
        respondWith("not json");

        assertFalse(verifier.verify(TOKEN, null));
    }

    @Test
    public void testFailsStartupWithoutSecret() {
        TurnstileCaptchaVerifier noSecret = new TurnstileCaptchaVerifier(new RestTemplate(), new ObjectMapper(), " ", URL);

        assertThrows(IllegalStateException.class, noSecret::requireSecret);
        assertDoesNotThrow(verifier::requireSecret);
    }
}
