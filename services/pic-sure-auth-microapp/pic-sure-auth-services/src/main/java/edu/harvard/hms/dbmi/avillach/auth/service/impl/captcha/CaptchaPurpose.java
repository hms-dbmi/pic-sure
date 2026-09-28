package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

/**
 * A feature gated by its own CAPTCHA widget. Each purpose gets its own {@link edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier}
 * bean, configured under {@code captcha.<key>.*}, so a token minted for one widget cannot be replayed against another.
 */
public enum CaptchaPurpose {

    API_KEY("api-key", "API key generation"), SESSION("session", "open-access session issuance");

    /**
     * The settings every purpose has, one property each.
     */
    public enum Setting {

        PROVIDER("provider"), SECRET("secret"), EXPECTED_ACTION("expected-action"), URL("url");

        private final String key;

        Setting(String key) {
            this.key = key;
        }
    }

    private final String key;
    private final String feature;

    CaptchaPurpose(String key, String feature) {
        this.key = key;
        this.feature = feature;
    }

    /**
     * @param setting the per-purpose setting, e.g. {@link Setting#SECRET}
     * @return the full property name, e.g. {@code captcha.api-key.secret}
     */
    public String property(Setting setting) {
        return "captcha." + key + "." + setting.key;
    }

    /**
     * @return the gated feature, for log and startup-failure messages
     */
    public String feature() {
        return feature;
    }
}
