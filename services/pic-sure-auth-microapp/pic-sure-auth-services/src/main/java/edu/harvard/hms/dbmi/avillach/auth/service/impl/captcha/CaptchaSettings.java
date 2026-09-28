package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaPurpose.Setting;
import org.springframework.core.env.Environment;

/**
 * One purpose's {@code captcha.<purpose>.*} properties.
 */
record CaptchaSettings(CaptchaPurpose purpose, CaptchaProvider provider, String secret, String expectedAction, String siteVerifyUrl) {

    // required properties: a missing key stops startup instead of leaving a purpose silently ungated
    static CaptchaSettings read(CaptchaPurpose purpose, Environment environment) {
        return new CaptchaSettings(
            purpose, CaptchaProvider.from(purpose, environment.getRequiredProperty(purpose.property(Setting.PROVIDER))),
            environment.getRequiredProperty(purpose.property(Setting.SECRET)),
            environment.getRequiredProperty(purpose.property(Setting.EXPECTED_ACTION)),
            environment.getRequiredProperty(purpose.property(Setting.URL))
        );
    }

    // the generated toString would print the secret
    @Override
    public String toString() {
        return "CaptchaSettings[purpose=" + purpose + ", provider=" + provider + ", expectedAction=" + expectedAction + ", siteVerifyUrl="
            + siteVerifyUrl + "]";
    }
}
