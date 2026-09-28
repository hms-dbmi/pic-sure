package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "captcha.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledCaptchaVerifier implements CaptchaVerifier {

    private static final Logger logger = LoggerFactory.getLogger(DisabledCaptchaVerifier.class);

    private final boolean generationEnabled;
    private final boolean allowUngatedGeneration;

    public DisabledCaptchaVerifier(
        @Value("${api.key.generation.enabled}") boolean generationEnabled,
        @Value("${api.key.allow.ungated.generation}") boolean allowUngatedGeneration
    ) {
        this.generationEnabled = generationEnabled;
        this.allowUngatedGeneration = allowUngatedGeneration;
    }

    // fail closed: the public generation endpoint must not silently run ungated because no
    // CAPTCHA provider happens to be configured
    @PostConstruct
    public void enforceExplicitOptIn() {
        if (generationEnabled && !allowUngatedGeneration) {
            throw new IllegalStateException(
                "api.key.generation.enabled is true but no CAPTCHA provider is configured. Configure captcha.provider,"
                    + " or explicitly accept ungated anonymous key minting with api.key.allow.ungated.generation=true."
            );
        }
        logger.warn("CAPTCHA verification is DISABLED - API key generation is not gated against automated key farming");
    }

    @Override
    public boolean verify(String captchaToken, String remoteIp) {
        return true;
    }
}
