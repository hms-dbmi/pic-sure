package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ReferenceMapping mapping() {
        return ReferenceMapping.load(getClass().getResourceAsStream("/target-service-mapping.yml"));
    }

    private ShadowRecord rec(String side, String cid, String targetService, String query, String decision) throws Exception {
        return new ShadowRecord(side, cid, "introspection", "h", targetService, mapper.readTree(query), false, null, decision);
    }

    @Test
    void failsGateWhenDivergencePresent() throws Exception {
        Reconciler r = new Reconciler(mapping());
        Report report = r.run(
            List.of(rec("GW", "c", "/query/sync", "{\"a\":1}", null)), List.of(rec("WF", "c", "/picsure/query/sync", "{\"a\":2}", "active"))
        ); // query mismatch
        assertEquals(1, report.counts().get(VerdictType.DIVERGENCE));
        assertFalse(report.passesExitGate());
    }

    @Test
    void failsGateWhenRejectNeverObserved() throws Exception {
        Reconciler r = new Reconciler(mapping());
        // only an allow seen for /query/sync -> decision coverage incomplete
        Report report = r.run(
            List.of(rec("GW", "c", "/query/sync", "{\"a\":1}", null)), List.of(rec("WF", "c", "/picsure/query/sync", "{\"a\":1}", "active"))
        );
        assertFalse(report.passesExitGate());
        assertTrue(report.coverage().get("/query/sync").contains("active"));
        assertFalse(report.coverage().get("/query/sync").contains("inactive"));
    }

    @Test
    void passesGateWhenCleanAndBothDecisionsSeen() throws Exception {
        Reconciler r = new Reconciler(mapping());
        Report report = r.run(
            List.of(rec("GW", "c1", "/query/sync", "{\"a\":1}", null), rec("GW", "c2", "/query/sync", "{\"a\":1}", null)),
            List.of(
                rec("WF", "c1", "/picsure/query/sync", "{\"a\":1}", "active"),
                rec("WF", "c2", "/picsure/query/sync", "{\"a\":1}", "inactive")
            )
        );
        assertEquals(0, report.counts().getOrDefault(VerdictType.DIVERGENCE, 0));
        assertTrue(report.passesExitGate());
    }

    /**
     * C1 regression: the reviewer's reproduced false-PASS. The gateway emitted NOTHING (empty --gw); the --wf file has one allow and one
     * reject on the same route. The old gate credited full coverage from the WF side alone and printed PASS/exit 0. It must now FAIL:
     * UNPAIRED &gt; 0, and WF-only records grant NO coverage.
     */
    @Test
    void allUnpairedRunFailsGate() throws Exception {
        Reconciler r = new Reconciler(mapping());
        Report report = r.run(
            List.of(), // gateway emitted nothing
            List.of(
                rec("WF", "c1", "/picsure/query/sync", "{\"a\":1}", "active"),
                rec("WF", "c2", "/picsure/query/sync", "{\"a\":1}", "inactive")
            )
        );
        assertEquals(2, report.counts().get(VerdictType.UNPAIRED));
        assertTrue(report.coverage().isEmpty(), "WF-only records must grant no coverage");
        assertEquals(2, report.unpaired().size());
        assertEquals("WF", report.unpaired().get(0).side());
        assertEquals("/picsure/query/sync", report.unpaired().get(0).route());
        assertFalse(report.passesExitGate());
    }

    /** A single unpaired record (partial gateway coverage) also fails the gate — no vacuous pass on WF-derived coverage. */
    @Test
    void partialGatewayCoverageFailsGateOnUnpaired() throws Exception {
        Reconciler r = new Reconciler(mapping());
        Report report = r.run(
            List.of(rec("GW", "c1", "/query/sync", "{\"a\":1}", null)), // gateway only saw the allow
            List.of(
                rec("WF", "c1", "/picsure/query/sync", "{\"a\":1}", "active"),
                rec("WF", "c2", "/picsure/query/sync", "{\"a\":1}", "inactive") // reject only on WF -> UNPAIRED
            )
        );
        assertEquals(1, report.counts().get(VerdictType.UNPAIRED));
        assertFalse(report.passesExitGate());
    }

    /** Zero paired records (and nothing else) never passes — the gate is non-vacuous. */
    @Test
    void zeroPairedRunFailsGate() {
        Report report = new Reconciler(mapping()).run(List.of(), List.of());
        assertFalse(report.passesExitGate());
    }

    /** With --routes, a listed route that appears in NEITHER log fails the gate even though every observed route is fully covered. */
    @Test
    void routesFileWithUncoveredRouteFailsGate() throws Exception {
        Set<String> routes = new LinkedHashSet<>(List.of("/query/sync", "/search/R"));
        Report report = new Reconciler(mapping()).run(
            List.of(rec("GW", "c1", "/query/sync", "{\"a\":1}", null), rec("GW", "c2", "/query/sync", "{\"a\":1}", null)),
            List.of(
                rec("WF", "c1", "/picsure/query/sync", "{\"a\":1}", "active"),
                rec("WF", "c2", "/picsure/query/sync", "{\"a\":1}", "inactive")
            ), routes
        );
        assertTrue(report.coverage().get("/query/sync").containsAll(List.of("active", "inactive")));
        assertFalse(report.passesExitGate(), "/search/R was never observed -> gate must fail");
    }

    /** With --routes listing only the fully-covered route, the gate passes. */
    @Test
    void routesFilePassesWhenEveryListedRouteFullyCovered() throws Exception {
        Set<String> routes = new LinkedHashSet<>(List.of("/query/sync"));
        Report report = new Reconciler(mapping()).run(
            List.of(rec("GW", "c1", "/query/sync", "{\"a\":1}", null), rec("GW", "c2", "/query/sync", "{\"a\":1}", null)),
            List.of(
                rec("WF", "c1", "/picsure/query/sync", "{\"a\":1}", "active"),
                rec("WF", "c2", "/picsure/query/sync", "{\"a\":1}", "inactive")
            ), routes
        );
        assertTrue(report.passesExitGate());
    }

    /** Path-only pairs (GW query absent by design) still credit decision coverage and are surfaced via {@code pathOnlyMatches}. */
    @Test
    void pathOnlyMatchesCreditCoverageAndAreCounted() throws Exception {
        Report report = new Reconciler(mapping()).run(
            List.of(rec("GW", "c1", "/query/sync", "null", null), rec("GW", "c2", "/query/sync", "null", null)),
            List.of(
                rec("WF", "c1", "/picsure/query/sync", "{\"a\":1}", "active"),
                rec("WF", "c2", "/picsure/query/sync", "{\"a\":1}", "inactive")
            )
        );
        assertEquals(2, report.pathOnlyMatches());
        assertEquals(0, report.counts().getOrDefault(VerdictType.DIVERGENCE, 0));
        assertTrue(report.passesExitGate());
    }
}
