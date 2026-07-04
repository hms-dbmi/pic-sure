package edu.harvard.hms.dbmi.avillach.shadow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Joins the GW and WF shadow-record streams by {@code correlationId}, keeping unpaired records on either side. */
public final class PairJoiner {

    private PairJoiner() {}

    /**
     * Joins gw and wf records sharing a correlation id into {@link Pair}s. A correlation id present on only one side produces a
     * {@link Pair} with the missing side set to {@code null} (an UNPAIRED record, per the classifier's rules).
     */
    public static List<Pair> join(List<ShadowRecord> gw, List<ShadowRecord> wf) {
        Map<String, ShadowRecord> gwById = byCorrelationId(gw);
        Map<String, ShadowRecord> wfById = byCorrelationId(wf);
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(gwById.keySet());
        ids.addAll(wfById.keySet());
        return ids.stream().map(id -> new Pair(id, gwById.get(id), wfById.get(id))).collect(Collectors.toList());
    }

    private static Map<String, ShadowRecord> byCorrelationId(List<ShadowRecord> records) {
        return records.stream()
            .collect(Collectors.toMap(ShadowRecord::correlationId, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
