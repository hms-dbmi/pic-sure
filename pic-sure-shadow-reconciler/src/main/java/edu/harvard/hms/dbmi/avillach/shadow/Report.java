package edu.harvard.hms.dbmi.avillach.shadow;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The aggregate result of reconciling a full run: per-{@link VerdictType} counts, a breakdown of {@code DIVERGENCE} verdicts by sub-reason,
 * and decision coverage (which allow/reject decisions were actually observed per canonical route, from the WF side). Also evaluates the
 * observe-window exit gate (Task 14 / plan §7): safe to cut the gateway over from {@code observe} to {@code enforce} only when the run
 * proves both zero divergences and full decision coverage on every canonical route it saw.
 */
public final class Report {

    private final Map<VerdictType, Integer> counts = new EnumMap<>(VerdictType.class);
    private final Map<String, Integer> divergencesByReason = new TreeMap<>();
    private final Map<String, Set<String>> coverage = new TreeMap<>();

    /** Folds one classified {@link Pair} into the running aggregate. Package-private: only {@link Reconciler} builds a {@link Report}. */
    void record(Verdict verdict, Pair pair, ReferenceMapping mapping) {
        counts.merge(verdict.type(), 1, Integer::sum);
        if (verdict.type() == VerdictType.DIVERGENCE) {
            divergencesByReason.merge(verdict.reason() == null ? "?" : verdict.reason(), 1, Integer::sum);
        }
        if (pair.wf() != null && pair.wf().decision() != null) {
            String canonicalRoute = mapping.canonical(pair.wf().targetService());
            coverage.computeIfAbsent(canonicalRoute, k -> new TreeSet<>()).add(pair.wf().decision());
        }
    }

    /** Count of joined pairs per {@link VerdictType}. */
    public Map<VerdictType, Integer> counts() {
        return counts;
    }

    /** Count of {@code DIVERGENCE} verdicts, broken down by sub-reason (e.g. {@code "token"}, {@code "query-mismatch"}). */
    public Map<String, Integer> divergencesByReason() {
        return divergencesByReason;
    }

    /** Canonical route -&gt; the set of WF decisions observed for it (e.g. {@code "active"}, {@code "inactive"}). */
    public Map<String, Set<String>> coverage() {
        return coverage;
    }

    /**
     * The observe-window exit gate: {@code true} iff no {@code DIVERGENCE} was observed AND every canonical route that was observed at all
     * saw both an allow decision ({@code "active"}/{@code "allow"}) and a reject decision ({@code "inactive"}/{@code "deny"}). An empty run
     * (no coverage at all) never passes — the gate proves decision parity was actually exercised, not merely absent.
     */
    public boolean passesExitGate() {
        if (counts.getOrDefault(VerdictType.DIVERGENCE, 0) > 0) {
            return false;
        }
        if (coverage.isEmpty()) {
            return false;
        }
        for (Set<String> decisionsSeen : coverage.values()) {
            boolean sawAllow = decisionsSeen.contains("active") || decisionsSeen.contains("allow");
            boolean sawReject = decisionsSeen.contains("inactive") || decisionsSeen.contains("deny");
            if (!(sawAllow && sawReject)) {
                return false;
            }
        }
        return true;
    }

    /** Renders a human-readable summary suitable for CLI stdout. */
    public String render() {
        StringBuilder sb = new StringBuilder("=== Shadow Parity Report ===\n");
        counts.forEach((verdictType, count) -> sb.append(verdictType).append(": ").append(count).append('\n'));
        if (!divergencesByReason.isEmpty()) {
            sb.append("-- divergences by reason --\n");
            divergencesByReason.forEach((reason, count) -> sb.append("  ").append(reason).append(": ").append(count).append('\n'));
        }
        sb.append("-- decision coverage --\n");
        coverage.forEach((route, decisionsSeen) -> sb.append("  ").append(route).append(": ").append(decisionsSeen).append('\n'));
        sb.append("EXIT GATE: ").append(passesExitGate() ? "PASS" : "FAIL").append('\n');
        return sb.toString();
    }
}
