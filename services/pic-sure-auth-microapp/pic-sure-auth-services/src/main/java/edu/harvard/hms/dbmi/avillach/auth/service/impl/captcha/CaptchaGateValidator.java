package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaPurpose.Setting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Startup checks for the CAPTCHA purposes, run once by {@link CaptchaConfiguration} before it builds any verifier. Each failure names the
 * misconfigured purpose, so a half-configured deployment says which widget is wrong.
 */
@Component
public class CaptchaGateValidator {

    private final boolean generationEnabled;
    private final boolean allowUngatedGeneration;
    private final boolean sessionEnabled;
    private final boolean allowUngatedSession;

    public CaptchaGateValidator(
        @Value("${api.key.generation.enabled}") boolean generationEnabled,
        @Value("${api.key.allow.ungated.generation}") boolean allowUngatedGeneration,
        @Value("${api.key.session.enabled}") boolean sessionEnabled, @Value("${api.key.allow.ungated.session}") boolean allowUngatedSession
    ) {
        this.generationEnabled = generationEnabled;
        this.allowUngatedGeneration = allowUngatedGeneration;
        this.sessionEnabled = sessionEnabled;
        this.allowUngatedSession = allowUngatedSession;
    }

    /**
     * @param settings every purpose's settings
     * @throws IllegalStateException if any purpose cannot start safely with this configuration
     */
    void validate(Collection<CaptchaSettings> settings) {
        settings.forEach(this::validate);
        requireSeparateWidgets(settings);
    }

    private void validate(CaptchaSettings settings) {
        CaptchaPurpose purpose = settings.purpose();
        switch (settings.provider()) {
            case TURNSTILE -> {
                requireText(purpose, Setting.SECRET, settings.secret());
                // a blank action or an unusable URL would reject every real token: fails closed, but silently breaks the feature
                requireText(purpose, Setting.EXPECTED_ACTION, settings.expectedAction());
                requireSiteVerifyUrl(purpose, settings.siteVerifyUrl());
            }
            // fail closed: a gated feature must not silently run ungated because its CAPTCHA is not configured
            case DISABLED -> {
                Optional<String> failure = ungatedWithoutOptIn(purpose);
                if (failure.isPresent()) {
                    throw new IllegalStateException(failure.get());
                }
            }
        }
    }

    // exhaustive, so a new purpose does not compile until it decides whether its feature may run ungated
    private Optional<String> ungatedWithoutOptIn(CaptchaPurpose purpose) {
        return switch (purpose) {
            case API_KEY -> generationEnabled && !allowUngatedGeneration ? Optional.of(
                "api.key.generation.enabled is true but " + purpose.property(Setting.PROVIDER) + " is '" + CaptchaProvider.DISABLED.value()
                    + "'. Configure " + purpose.property(Setting.PROVIDER)
                    + ", or explicitly accept ungated anonymous key minting with api.key.allow.ungated.generation=true."
            ) : Optional.empty();
            case SESSION -> sessionEnabled && !allowUngatedSession ? Optional.of(
                "api.key.session.enabled is true but " + purpose.property(Setting.PROVIDER) + " is '" + CaptchaProvider.DISABLED.value()
                    + "'. Configure " + purpose.property(Setting.PROVIDER)
                    + ", or explicitly accept ungated open-access session issuance with api.key.allow.ungated.session=true."
            ) : Optional.empty();
        };
    }

    // two purposes sharing a widget can replay each other's tokens; distinct actions alone would only partly separate them
    private static void requireSeparateWidgets(Collection<CaptchaSettings> settings) {
        List<CaptchaSettings> turnstile = settings.stream().filter(s -> s.provider() == CaptchaProvider.TURNSTILE).toList();
        for (int i = 0; i < turnstile.size(); i++) {
            for (int j = i + 1; j < turnstile.size(); j++) {
                requireDifferent(turnstile.get(i), turnstile.get(j), Setting.SECRET, CaptchaSettings::secret);
                requireDifferent(turnstile.get(i), turnstile.get(j), Setting.EXPECTED_ACTION, CaptchaSettings::expectedAction);
            }
        }
    }

    private static void requireDifferent(CaptchaSettings a, CaptchaSettings b, Setting setting, Function<CaptchaSettings, String> value) {
        if (value.apply(a).equals(value.apply(b))) {
            throw new IllegalStateException(
                a.purpose().property(setting) + " and " + b.purpose().property(setting) + " are the same. " + a.purpose().feature()
                    + " and " + b.purpose().feature()
                    + " need separate Turnstile widgets, each with its own secret and action, so a token minted for one"
                    + " cannot be replayed against the other."
            );
        }
    }

    private static void requireSiteVerifyUrl(CaptchaPurpose purpose, String url) {
        requireText(purpose, Setting.URL, url);
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            uri = null;
        }
        boolean httpScheme = uri != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        if (!httpScheme || uri.getHost() == null) {
            throw new IllegalStateException(
                purpose.property(Setting.URL) + " is '" + url + "', which is not an absolute http(s) URL, so " + purpose.feature()
                    + " would reject every token."
            );
        }
    }

    private static void requireText(CaptchaPurpose purpose, Setting setting, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                purpose.property(Setting.PROVIDER) + " is '" + CaptchaProvider.TURNSTILE.value() + "' but " + purpose.property(setting)
                    + " is not set, so " + purpose.feature() + " cannot be CAPTCHA-verified."
            );
        }
    }
}
