package edu.harvard.hms.dbmi.avillach.shadow;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class PairJoinerTest {

    @Test
    void joinsMatchingIdsAndKeepsUnpaired() {
        ShadowRecord gw = new ShadowRecord("GW", "c1", "introspection", "h", "/p", null, false, null, null);
        ShadowRecord wf = new ShadowRecord("WF", "c1", "introspection", "h", "/p", null, false, null, "active");
        ShadowRecord lonely = new ShadowRecord("GW", "c2", "introspection", "h", "/p", null, false, null, null);

        List<Pair> pairs = PairJoiner.join(List.of(gw, lonely), List.of(wf));
        Pair p1 = pairs.stream().filter(p -> p.correlationId().equals("c1")).findFirst().orElseThrow();
        assertNotNull(p1.gw());
        assertNotNull(p1.wf());
        Pair p2 = pairs.stream().filter(p -> p.correlationId().equals("c2")).findFirst().orElseThrow();
        assertNotNull(p2.gw());
        assertNull(p2.wf());
    }

    @Test
    void wfOnlyRecordIsUnpairedOnGwSide() {
        ShadowRecord wf = new ShadowRecord("WF", "c3", "introspection", "h", "/p", null, false, null, "active");
        List<Pair> pairs = PairJoiner.join(List.of(), List.of(wf));
        Pair p = pairs.stream().filter(p2 -> p2.correlationId().equals("c3")).findFirst().orElseThrow();
        assertNull(p.gw());
        assertNotNull(p.wf());
    }

    @Test
    void emptyInputsProduceEmptyOutput() {
        List<Pair> pairs = PairJoiner.join(List.of(), List.of());
        assertNotNull(pairs);
        org.junit.jupiter.api.Assertions.assertTrue(pairs.isEmpty());
    }
}
