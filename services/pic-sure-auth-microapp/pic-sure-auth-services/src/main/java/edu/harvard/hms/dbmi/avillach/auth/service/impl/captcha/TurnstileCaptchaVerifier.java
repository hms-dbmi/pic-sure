package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies Cloudflare Turnstile tokens against the siteverify endpoint for one widget. Fails closed: any verification error (network,
 * non-2xx, malformed response, wrong action) rejects the request rather than silently disabling the abuse gate.
 */
public class TurnstileCaptchaVerifier implements CaptchaVerifier {

    private static final Logger logger = LoggerFactory.getLogger(TurnstileCaptchaVerifier.class);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secret;
    private final String siteVerifyUrl;
    // must equal the action the frontend widget declares; separates purposes even if two ever share one widget
    private final String expectedAction;

    public TurnstileCaptchaVerifier(
        HttpClient httpClient, ObjectMapper objectMapper, String secret, String siteVerifyUrl, String expectedAction
    ) {
        this(buildRestTemplate(httpClient), objectMapper, secret, siteVerifyUrl, expectedAction);
    }

    TurnstileCaptchaVerifier(
        RestTemplate restTemplate, ObjectMapper objectMapper, String secret, String siteVerifyUrl, String expectedAction
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.siteVerifyUrl = siteVerifyUrl;
        this.expectedAction = expectedAction;
    }

    // own template rather than RestClientUtil's shared one: the shared template has no timeouts,
    // and a hung siteverify call would pin servlet threads. The shared HttpClient keeps the
    // deployment's egress proxy configuration
    private static RestTemplate buildRestTemplate(HttpClient httpClient) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient) {
            @Override
            protected RequestConfig createRequestConfig(Object client) {
                RequestConfig baseConfig = super.createRequestConfig(client);
                return RequestConfig.copy(baseConfig != null ? baseConfig : RequestConfig.DEFAULT).setRedirectsEnabled(false).build();
            }
        };
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setConnectionRequestTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    @Override
    public boolean verify(String captchaToken, String remoteIp) {
        if (!StringUtils.hasText(captchaToken)) {
            return false;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", captchaToken);
        if (StringUtils.hasText(remoteIp)) {
            form.add("remoteip", remoteIp);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(siteVerifyUrl, new HttpEntity<>(form, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.warn("Turnstile verification returned non-success status: {}", response.getStatusCode());
                return false;
            }
            JsonNode body = objectMapper.readTree(response.getBody());
            if (!body.path("success").asBoolean(false)) {
                logger.warn("Turnstile rejected the token: error-codes={}", body.path("error-codes"));
                return false;
            }
            // exact match: a token whose widget declared no action is rejected too
            String action = body.path("action").asText("");
            if (!expectedAction.equals(action)) {
                logger.warn("Turnstile token was minted for action '{}', expected '{}'", action, expectedAction);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("Turnstile verification errored, rejecting the token: {}", e.toString());
            return false;
        }
    }
}
