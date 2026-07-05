package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ClassifierTest {

    private final ObjectMapper M = new ObjectMapper();

    private Classifier classifier() {
        return new Classifier(ReferenceMapping.load(getClass().getResourceAsStream("/target-service-mapping.yml")));
    }

    private ShadowRecord gw(String t, String tok, String q) throws Exception {
        return new ShadowRecord("GW", "c", "introspection", tok, t, M.readTree(q), false, null, null);
    }

    private ShadowRecord wf(String t, String tok, String q, String dec) throws Exception {
        return new ShadowRecord("WF", "c", "introspection", tok, t, M.readTree(q), false, null, dec);
    }

    @Test
    void rawEqualIsMatch() throws Exception {
        // Two independent systems sent PSAMA the identical raw target -> raw equality is parity -> MATCH.
        Verdict v =
            classifier().classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.MATCH, v.type());
        assertNull(v.reason());
    }

    @Test
    void bothRawPicsureQuerySyncIsMatch() throws Exception {
        // Both sides sent the identical raw "/picsure/query/sync" (a COSMETIC-mapped prefix) -> raw equality is parity -> MATCH.
        Verdict v = classifier()
            .classify(new Pair("c", gw("/picsure/query/sync", "h", "{\"a\":1}"), wf("/picsure/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.MATCH, v.type());
        assertNull(v.reason());
    }

    @Test
    void rawEqualButIpAddressDiffersIsExpectedDiff() throws Exception {
        // Same raw target on both sides but different observer ipAddress -> an incidental difference (the ipAddress-only
        // notion), not a disagreement about what is authorized -> EXPECTED_DIFF.
        ShadowRecord gw = new ShadowRecord("GW", "c", "introspection", "h", "/query/sync", M.readTree("{\"a\":1}"), false, "1.1.1.1", null);
        ShadowRecord wf =
            new ShadowRecord("WF", "c", "introspection", "h", "/query/sync", M.readTree("{\"a\":1}"), false, "2.2.2.2", "active");
        Verdict v = classifier().classify(new Pair("c", gw, wf));
        assertEquals(VerdictType.EXPECTED_DIFF, v.type());
    }

    @Test
    void rawDifferSameCanonicalBothCosmeticIsExpectedDiff() throws Exception {
        // Raw targets DIFFER but share canonical "/query/" and both variants are COSMETIC -> a cosmetic rewrite difference
        // the mapping's own definition says does not change PSAMA's evaluation -> EXPECTED_DIFF.
        Verdict v = classifier()
            .classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/picsure/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.EXPECTED_DIFF, v.type());
    }

    @Test
    void decisionAffectingIsIntentional() throws Exception {
        // Raw targets DIFFER but share canonical "/query/"; the /v3 variant is DECISION_AFFECTING -> INTENTIONAL_BEHAVIOR_CHANGE.
        Verdict v =
            classifier().classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/v3/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.INTENTIONAL_BEHAVIOR_CHANGE, v.type());
    }

    @Test
    void rawDifferSameCanonicalDecisionAffectingIsIntentional() throws Exception {
        // GW "/v3/query/sync" vs WF "/picsure/query/sync": raw differ, both canonicalize to "/query/", and the /v3 variant is
        // DECISION_AFFECTING (changes which PSAMA rule applies) -> a known, intentional behavior difference.
        Verdict v = classifier()
            .classify(new Pair("c", gw("/v3/query/sync", "h", "{\"a\":1}"), wf("/picsure/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.INTENTIONAL_BEHAVIOR_CHANGE, v.type());
    }

    @Test
    void differentCanonicalsIsTargetServiceDivergence() throws Exception {
        // Raw targets differ AND canonicalize to different routes ("/query/sync" vs "/search/x") -> the two sides genuinely
        // disagree about what is being authorized -> DIVERGENCE("target-service").
        Verdict v = classifier().classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/search/x", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.DIVERGENCE, v.type());
        assertEquals("target-service", v.reason());
    }

    @Test
    void gatewayUnmappedPathDifferentCanonicalIsDivergence() throws Exception {
        // GW sent an unmapped raw path; the two sides canonicalize to different routes ("/WRONG/sync" vs "/query/sync") -> the
        // sides genuinely disagree about what is being authorized -> DIVERGENCE("target-service").
        Verdict v = classifier()
            .classify(new Pair("c", gw("/WRONG/sync", "h", "{\"a\":1}"), wf("/picsure/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.DIVERGENCE, v.type());
        assertEquals("target-service", v.reason());
    }

    @Test
    void queryMismatchIsDivergence() throws Exception {
        Verdict v = classifier()
            .classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/picsure/query/sync", "h", "{\"a\":2}", "active")));
        assertEquals(VerdictType.DIVERGENCE, v.type());
        assertEquals("query-mismatch", v.reason());
    }

    @Test
    void bothQueriesPresentAndMatchingIsPlainMatchNotPathOnly() throws Exception {
        // both sides present -> strict compare, plain MATCH with no path-only tag (regression: path-only must not leak here)
        Verdict v =
            classifier().classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.MATCH, v.type());
        assertNull(v.reason());
    }

    @Test
    void gwQueryAbsentWfPresentMatchingPathIsPathOnlyMatch() throws Exception {
        // I2: OBSERVE never buffers the POST body so the GW query is null by design while WF logged the parsed body.
        // Matching path + token -> MATCH, tagged path-only (NOT a query-mismatch DIVERGENCE).
        Verdict v = classifier().classify(new Pair("c", gw("/query/sync", "h", "null"), wf("/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.MATCH, v.type());
        assertEquals("path-only", v.reason());
    }

    @Test
    void bothRawV3QuerySyncPathOnlyIsMatchNotDivergence() throws Exception {
        // LIVE-SMOKE REGRESSION (2026-07-05): both sides sent PSAMA the identical raw "/v3/query/sync" (a DECISION_AFFECTING-
        // mapped prefix), same tokenHash, GW query null by design (OBSERVE never buffers) / WF query present, same decision.
        // Raw equality is parity -> MATCH tagged path-only. It is NOT DIVERGENCE (the old canonical-required rule false-diverged
        // this in-parity pair) and NOT INTENTIONAL_BEHAVIOR_CHANGE (that verdict now requires the raw targets to DIFFER).
        Verdict v =
            classifier().classify(new Pair("c", gw("/v3/query/sync", "h", "null"), wf("/v3/query/sync", "h", "{\"a\":1}", "inactive")));
        assertEquals(VerdictType.MATCH, v.type());
        assertEquals("path-only", v.reason());
    }

    @Test
    void gwQueryAbsentCosmeticRouteIsPathOnlyExpectedDiff() throws Exception {
        // The realistic observe shape: raw targets differ on a cosmetic variant (same canonical), GW query null by design ->
        // EXPECTED_DIFF, still tagged path-only.
        Verdict v =
            classifier().classify(new Pair("c", gw("/query/sync", "h", "null"), wf("/picsure/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.EXPECTED_DIFF, v.type());
        assertEquals("path-only", v.reason());
    }

    @Test
    void gwQueryAbsentButPathMismatchStillDivergence() throws Exception {
        // path-only skips the query dimension but a genuine target-service mismatch is still a DIVERGENCE.
        Verdict v =
            classifier().classify(new Pair("c", gw("/WRONG/sync", "h", "null"), wf("/picsure/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.DIVERGENCE, v.type());
        assertEquals("target-service", v.reason());
    }

    @Test
    void resourceCredentialsLeakIsFlagged() throws Exception {
        Verdict v = classifier().classify(
            new Pair("c", gw("/query/sync", "h", "{\"resourceCredentials\":{\"k\":\"v\"}}"), wf("/picsure/query/sync", "h", "{}", "active"))
        );
        assertEquals(VerdictType.DIVERGENCE, v.type());
        assertEquals("resourceCredentials-leak", v.reason());
    }

    @Test
    void tokenMismatchIsDivergence() throws Exception {
        Verdict v = classifier().classify(new Pair("c", gw("/query/sync", "h1", "{}"), wf("/picsure/query/sync", "h2", "{}", "active")));
        assertEquals(VerdictType.DIVERGENCE, v.type());
        assertEquals("token", v.reason());
    }

    @Test
    void unpairedWhenOneSideMissing() throws Exception {
        Verdict v = classifier().classify(new Pair("c", gw("/query/sync", "h", "{}"), null));
        assertEquals(VerdictType.UNPAIRED, v.type());
    }

    @Test
    void unpairedWhenGwSideMissing() throws Exception {
        Verdict v = classifier().classify(new Pair("c", null, wf("/picsure/query/sync", "h", "{}", "active")));
        assertEquals(VerdictType.UNPAIRED, v.type());
    }

    @Test
    void skipListPathIsSkip() throws Exception {
        Verdict v = classifier().classify(new Pair("c", gw("/actuator/health", "h", "{}"), wf("/actuator/health", "h", "{}", "active")));
        assertEquals(VerdictType.SKIP, v.type());
    }

    @Test
    void skipListWinsEvenWhenUnpaired() throws Exception {
        Verdict v = classifier().classify(new Pair("c", gw("/info/version", "h", "{}"), null));
        assertEquals(VerdictType.SKIP, v.type());
    }

    @Test
    void skipListWinsOverTokenMismatch() throws Exception {
        Verdict v = classifier().classify(new Pair("c", gw("/logging/foo", "h1", "{}"), wf("/logging/foo", "h2", "{}", "active")));
        assertEquals(VerdictType.SKIP, v.type());
    }
}
