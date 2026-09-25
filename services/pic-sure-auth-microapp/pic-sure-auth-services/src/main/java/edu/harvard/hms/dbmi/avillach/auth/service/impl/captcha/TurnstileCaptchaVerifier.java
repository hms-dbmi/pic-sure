package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import jakarta.annotation.PostConstruct;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies Cloudflare Turnstile tokens against the siteverify endpoint. Fails closed: any verification error (network, non-2xx, malformed
 * response) rejects the key mint rather than silently disabling the abuse gate.
 */
@Service
@ConditionalOnProperty(name = "captcha.provider", havingValue = "turnstile")
public class TurnstileCaptchaVerifier implements CaptchaVerifier {

    private static final Logger logger = LoggerFactory.getLogger(TurnstileCaptchaVerifier.class);

    // must match the action set on the frontend widget; best-effort — rejects tokens minted by
    // other widgets on the same sitekey, but only when that widget declared an action
    static final String EXPECTED_ACTION = "generate-api-key";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String secret;
    private final String siteVerifyUrl;

    @Autowired
    public TurnstileCaptchaVerifier(
        HttpClient httpClient, ObjectMapper objectMapper, @Value("${captcha.turnstile.secret}") String secret,
        @Value("${captcha.turnstile.url:https://challenges.cloudflare.com/turnstile/v0/siteverify}") String siteVerifyUrl
    ) {
        this(buildRestTemplate(httpClient), objectMapper, secret, siteVerifyUrl);
    }

    TurnstileCaptchaVerifier(RestTemplate restTemplate, ObjectMapper objectMapper, String secret, String siteVerifyUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.siteVerifyUrl = siteVerifyUrl;
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

    @PostConstruct
    public void requireSecret() {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                "captcha.provider is 'turnstile' but captcha.turnstile.secret is not set. Configure TURNSTILE_SECRET_KEY."
            );
        }
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
            String action = body.path("action").asText("");
            if (!action.isEmpty() && !EXPECTED_ACTION.equals(action)) {
                logger.warn("Turnstile token was minted for a different action: {}", action);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("Turnstile verification errored, rejecting key generation: {}", e.toString());
            return false;
        }
    }
}
