package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DisabledCaptchaVerifierTest {

    @Test
    public void testFailsStartupWhenGenerationEnabledWithoutExplicitOptIn() {
        DisabledCaptchaVerifier verifier = new DisabledCaptchaVerifier(true, false);

        assertThrows(IllegalStateException.class, verifier::enforceExplicitOptIn);
    }

    @Test
    public void testStartsWhenUngatedGenerationExplicitlyAllowed() {
        DisabledCaptchaVerifier verifier = new DisabledCaptchaVerifier(true, true);

        assertDoesNotThrow(verifier::enforceExplicitOptIn);
        assertTrue(verifier.verify(null, null));
    }

    @Test
    public void testStartsWhenGenerationDisabled() {
        assertDoesNotThrow(new DisabledCaptchaVerifier(false, false)::enforceExplicitOptIn);
    }
}
