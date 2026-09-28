package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import org.apache.hc.client5.http.classic.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.EnumMap;
import java.util.Map;

/**
 * Builds one {@link CaptchaVerifier} per {@link CaptchaPurpose}, each from its own {@code captcha.<purpose>.*} properties. Call sites
 * inject theirs by bean name ({@code apiKeyCaptchaVerifier}, {@code sessionCaptchaVerifier}) and stay typed {@link CaptchaVerifier}.
 */
@Configuration
public class CaptchaConfiguration {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<CaptchaPurpose, CaptchaSettings> settings = new EnumMap<>(CaptchaPurpose.class);

    // every purpose is read and validated together, before any verifier exists: some rules compare purposes
    public CaptchaConfiguration(
        Environment environment, HttpClient httpClient, ObjectMapper objectMapper, CaptchaGateValidator gateValidator
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        for (CaptchaPurpose purpose : CaptchaPurpose.values()) {
            settings.put(purpose, CaptchaSettings.read(purpose, environment));
        }
        gateValidator.validate(settings.values());
    }

    @Bean
    public CaptchaVerifier apiKeyCaptchaVerifier() {
        return build(settings.get(CaptchaPurpose.API_KEY));
    }

    @Bean
    public CaptchaVerifier sessionCaptchaVerifier() {
        return build(settings.get(CaptchaPurpose.SESSION));
    }

    private CaptchaVerifier build(CaptchaSettings settings) {
        return switch (settings.provider()) {
            case TURNSTILE -> new TurnstileCaptchaVerifier(
                httpClient, objectMapper, settings.secret(), settings.siteVerifyUrl(), settings.expectedAction()
            );
            case DISABLED -> new DisabledCaptchaVerifier(settings.purpose());
        };
    }
}
