package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaPurpose.Setting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts every token. Whether the purpose's feature may run ungated is decided by {@link CaptchaGateValidator}, not here.
 */
public class DisabledCaptchaVerifier implements CaptchaVerifier {

    private static final Logger logger = LoggerFactory.getLogger(DisabledCaptchaVerifier.class);

    public DisabledCaptchaVerifier(CaptchaPurpose purpose) {
        logger.warn(
            "CAPTCHA verification is DISABLED for {} ({}) - it is not gated against automated abuse", purpose.feature(),
            purpose.property(Setting.PROVIDER)
        );
    }

    @Override
    public boolean verify(String captchaToken, String remoteIp) {
        return true;
    }
}
