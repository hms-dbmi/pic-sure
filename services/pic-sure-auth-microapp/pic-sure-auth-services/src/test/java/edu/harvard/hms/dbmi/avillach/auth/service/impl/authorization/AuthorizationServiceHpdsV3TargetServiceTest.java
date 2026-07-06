package edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuthorizationService#isHpdsV3TargetService(String)}.
 * <p>
 * This helper controls when the HPDS-v3 consent-based access rule check is skipped, so it must
 * match clean HPDS-v3 target service paths precisely (segment-aware), without being fooled by
 * substrings like "v30", "v3ish", or paths where "hpds"/"v3" appear in the wrong position.
 */
class AuthorizationServiceHpdsV3TargetServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/hpds/auth/v3",
            "/hpds/auth/v3/query",
            "/hpds/open/v3",
            "/hpds/open/v3/query/abc/result",
            "/hpds/auth/v3/"
    })
    void returnsTrueForCleanHpdsV3Paths(String targetService) {
        assertTrue(AuthorizationService.isHpdsV3TargetService(targetService));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/dictionary/v3/hpds",
            "/hpds/auth/v30/query",
            "/hpds/auth/v3ish/query",
            "/hpds/v3/query",
            "/foo/hpds/auth/v3/query",
            "/hpds/auth/v3-query",
            "/v3/query"
    })
    void returnsFalseForNonMatchingPaths(String targetService) {
        assertFalse(AuthorizationService.isHpdsV3TargetService(targetService));
    }

    @Test
    void returnsFalseForNull() {
        assertFalse(AuthorizationService.isHpdsV3TargetService(null));
    }

    @Test
    void legacyV3PrefixIsNotMatchedByHelperButIsHandledSeparatelyByStartsWith() {
        // isHpdsV3TargetService is intentionally narrow; the legacy "/v3" prefix continues to be
        // handled by the separate startsWith("/v3") check in passesAccessRuleEvaluation until Phase 7.
        assertFalse(AuthorizationService.isHpdsV3TargetService("/v3/query"));
    }

    @Test
    void combinedSkipPredicate_legacyV3PathSkips() {
        assertTrue(skipsConsentCheck("/v3/query"));
    }

    @Test
    void combinedSkipPredicate_cleanHpdsV3PathSkips() {
        assertTrue(skipsConsentCheck("/hpds/auth/v3/query"));
    }

    @Test
    void combinedSkipPredicate_unrelatedPathDoesNotSkip() {
        assertFalse(skipsConsentCheck("/dictionary/x"));
    }

    /**
     * Mirrors the skip condition used in {@link AuthorizationService#passesAccessRuleEvaluation}.
     */
    private static boolean skipsConsentCheck(String targetService) {
        return targetService != null
                && (targetService.startsWith("/v3") || AuthorizationService.isHpdsV3TargetService(targetService));
    }
}
