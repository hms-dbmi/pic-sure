package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void cleanMatch() throws Exception {
        // cosmetic route, gateway emits canonical, WF emits raw -> MATCH/EXPECTED_DIFF
        Verdict v = classifier()
            .classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/picsure/query/sync", "h", "{\"a\":1}", "active")));
        assertTrue(v.type() == VerdictType.MATCH || v.type() == VerdictType.EXPECTED_DIFF);
    }

    @Test
    void trueMatchWhenBothSidesCanonicalAndIpMatches() throws Exception {
        Verdict v =
            classifier().classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.MATCH, v.type());
    }

    @Test
    void decisionAffectingIsIntentional() throws Exception {
        Verdict v =
            classifier().classify(new Pair("c", gw("/query/sync", "h", "{\"a\":1}"), wf("/v3/query/sync", "h", "{\"a\":1}", "active")));
        assertEquals(VerdictType.INTENTIONAL_BEHAVIOR_CHANGE, v.type());
    }

    @Test
    void resolverBugIsDivergence() throws Exception {
        // cosmetic route but gateway produced a non-canonical value
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
    void gwQueryAbsentCosmeticRouteIsPathOnlyExpectedDiff() throws Exception {
        // The realistic observe shape: GW emits canonical, WF emits raw cosmetic path -> EXPECTED_DIFF, still tagged path-only.
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
