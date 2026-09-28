package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaPurpose.Setting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaProvider.DISABLED;
import static edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaProvider.TURNSTILE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CaptchaGateValidatorTest {

    private static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final CaptchaGateValidator generationDisabled = new CaptchaGateValidator(false, false);

    private static CaptchaSettings turnstile(CaptchaPurpose purpose, String secret, String expectedAction, String url) {
        return new CaptchaSettings(purpose, TURNSTILE, secret, expectedAction, url);
    }

    private static CaptchaSettings turnstile(CaptchaPurpose purpose) {
        return turnstile(purpose, purpose + "-secret", purpose + "-action", URL);
    }

    private static CaptchaSettings disabled(CaptchaPurpose purpose) {
        return new CaptchaSettings(purpose, DISABLED, "", "", URL);
    }

    private static void assertMessageNames(IllegalStateException e, String... properties) {
        for (String property : properties) {
            assertTrue(e.getMessage().contains(property), e.getMessage());
        }
    }

    @ParameterizedTest
    @EnumSource(CaptchaPurpose.class)
    public void testTurnstileWithoutSecretFailsNamingThePurpose(CaptchaPurpose purpose) {
        IllegalStateException e = assertThrows(
            IllegalStateException.class, () -> generationDisabled.validate(List.of(turnstile(purpose, " ", "some-action", URL)))
        );

        assertMessageNames(e, purpose.property(Setting.SECRET));
    }

    @ParameterizedTest
    @EnumSource(CaptchaPurpose.class)
    public void testTurnstileWithoutExpectedActionFailsNamingThePurpose(CaptchaPurpose purpose) {
        IllegalStateException e = assertThrows(
            IllegalStateException.class, () -> generationDisabled.validate(List.of(turnstile(purpose, "test-secret", "", URL)))
        );

        assertMessageNames(e, purpose.property(Setting.EXPECTED_ACTION));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"", " ", "not a url", "siteverify.example/turnstile", "/turnstile/v0/siteverify", "ftp://siteverify.example/x",
            "https:///no-host", " https://challenges.cloudflare.com/turnstile/v0/siteverify"}
    )
    public void testTurnstileWithUnusableUrlFailsNamingThePurpose(String url) {
        for (CaptchaPurpose purpose : CaptchaPurpose.values()) {
            IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> generationDisabled.validate(List.of(turnstile(purpose, "test-secret", "some-action", url)))
            );

            assertMessageNames(e, purpose.property(Setting.URL));
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"https://challenges.cloudflare.com/turnstile/v0/siteverify", "http://127.0.0.1:8080/siteverify", "HTTPS://example.org"}
    )
    public void testTurnstileWithHttpUrlPasses(String url) {
        assertDoesNotThrow(
            () -> generationDisabled.validate(List.of(turnstile(CaptchaPurpose.API_KEY, "test-secret", "some-action", url)))
        );
    }

    @Test
    public void testFullyConfiguredTurnstilePurposesPass() {
        assertDoesNotThrow(
            () -> new CaptchaGateValidator(true, false)
                .validate(List.of(turnstile(CaptchaPurpose.API_KEY), turnstile(CaptchaPurpose.SESSION)))
        );
    }

    @Test
    public void testSharedExpectedActionFailsNamingBothPurposes() {
        List<CaptchaSettings> settings = List.of(
            turnstile(CaptchaPurpose.API_KEY, "api-key-secret", "same-action", URL),
            turnstile(CaptchaPurpose.SESSION, "session-secret", "same-action", URL)
        );

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> generationDisabled.validate(settings));

        assertMessageNames(
            e, CaptchaPurpose.API_KEY.property(Setting.EXPECTED_ACTION), CaptchaPurpose.SESSION.property(Setting.EXPECTED_ACTION)
        );
    }

    @Test
    public void testSharedSecretFailsNamingBothPurposes() {
        List<CaptchaSettings> settings = List.of(
            turnstile(CaptchaPurpose.API_KEY, "same-secret", "api-key-action", URL),
            turnstile(CaptchaPurpose.SESSION, "same-secret", "session-action", URL)
        );

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> generationDisabled.validate(settings));

        assertMessageNames(e, CaptchaPurpose.API_KEY.property(Setting.SECRET), CaptchaPurpose.SESSION.property(Setting.SECRET));
        assertFalse(e.getMessage().contains("same-secret"), "the message must not echo the secret: " + e.getMessage());
    }

    // a disabled purpose has no widget, so its leftover values cannot collide with the enabled one
    @Test
    public void testSharedValuesAreIgnoredWhenOnePurposeIsDisabled() {
        List<CaptchaSettings> settings = List.of(
            turnstile(CaptchaPurpose.API_KEY, "same-secret", "same-action", URL),
            new CaptchaSettings(CaptchaPurpose.SESSION, DISABLED, "same-secret", "same-action", URL)
        );

        assertDoesNotThrow(() -> generationDisabled.validate(settings));
    }

    @Test
    public void testGenerationEnabledWithApiKeyDisabledFailsNamingThePurpose() {
        CaptchaGateValidator validator = new CaptchaGateValidator(true, false);

        IllegalStateException e =
            assertThrows(IllegalStateException.class, () -> validator.validate(List.of(disabled(CaptchaPurpose.API_KEY))));

        assertMessageNames(e, CaptchaPurpose.API_KEY.property(Setting.PROVIDER), "api.key.allow.ungated.generation");
    }

    @Test
    public void testGenerationEnabledWithApiKeyDisabledPassesWithExplicitOptIn() {
        assertDoesNotThrow(() -> new CaptchaGateValidator(true, true).validate(List.of(disabled(CaptchaPurpose.API_KEY))));
    }

    // the generation flags gate API key generation only; a disabled session widget says nothing about it
    @Test
    public void testGenerationFlagsDoNotGateTheSessionPurpose() {
        assertDoesNotThrow(
            () -> new CaptchaGateValidator(true, false)
                .validate(List.of(turnstile(CaptchaPurpose.API_KEY), disabled(CaptchaPurpose.SESSION)))
        );
    }

    @Test
    public void testSettingsToStringOmitsTheSecret() {
        assertFalse(turnstile(CaptchaPurpose.API_KEY, "do-not-log", "some-action", URL).toString().contains("do-not-log"));
    }
}
