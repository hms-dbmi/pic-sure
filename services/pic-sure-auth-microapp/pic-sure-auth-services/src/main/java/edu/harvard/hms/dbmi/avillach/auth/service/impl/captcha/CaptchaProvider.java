package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaPurpose.Setting;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The values a purpose's {@code provider} property accepts.
 */
public enum CaptchaProvider {

    DISABLED("disabled"), TURNSTILE("turnstile");

    private final String value;

    CaptchaProvider(String value) {
        this.value = value;
    }

    /**
     * @return the property value that selects this provider
     */
    public String value() {
        return value;
    }

    /**
     * Case-insensitive, like the {@code @ConditionalOnProperty(havingValue = ...)} selection this replaced, and surrounding whitespace is
     * ignored.
     *
     * @param purpose the purpose whose {@code provider} property this is, named in the failure
     * @param rawValue the property value as configured
     * @return the selected provider
     * @throws IllegalStateException if the value names no provider
     */
    public static CaptchaProvider from(CaptchaPurpose purpose, String rawValue) {
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(provider -> provider.value.equals(normalized)).findFirst().orElseThrow(
            () -> new IllegalStateException(
                purpose.property(Setting.PROVIDER) + " is '" + rawValue + "'; expected "
                    + Arrays.stream(values()).map(provider -> "'" + provider.value + "'").collect(Collectors.joining(" or ")) + "."
            )
        );
    }
}
