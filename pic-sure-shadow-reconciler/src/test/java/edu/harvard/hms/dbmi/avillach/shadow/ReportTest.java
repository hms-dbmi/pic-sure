package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
}
