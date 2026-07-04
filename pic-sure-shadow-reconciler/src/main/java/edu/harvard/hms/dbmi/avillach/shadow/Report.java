package edu.harvard.hms.dbmi.avillach.shadow;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The aggregate result of reconciling a full run: per-{@link VerdictType} counts, a breakdown of {@code DIVERGENCE} verdicts by sub-reason,
 * the {@code UNPAIRED} records (so a human can see exactly which side emitted a record the other never matched), the count of path-only
 * matches (evidence where the GW query was absent by design and only path/decision could be compared -- see {@link Classifier}), and
 * decision coverage (which allow/reject decisions were actually observed per canonical route). Also evaluates the observe-window exit gate
 * (Task 14 / plan §7): safe to cut the gateway over from {@code observe} to {@code enforce} only when the run proves ALL of: zero
 * divergences, zero unpaired records, and full allow+reject decision coverage on every route required.
 *
 * <p><b>Coverage is credited ONLY from fully-paired, non-divergent pairs</b> -- both a GW and a WF record present, and the verdict an
 * agreement ({@code MATCH}/{@code EXPECTED_DIFF}/{@code INTENTIONAL_BEHAVIOR_CHANGE}). A WF-side record with no GW counterpart grants NO
 * coverage; that was the C1 false-PASS (an all-{@code UNPAIRED} run -- gateway emitted nothing -- used to credit full WF-derived coverage
 * and print PASS). {@code SKIP}, {@code UNPAIRED} and {@code DIVERGENCE} pairs never count toward coverage.
 *
 * <p><b>Required routes.</b> Without an explicit route universe the gate requires full coverage of every canonical route seen in a paired
 * record (and still fails on zero paired records -- never a vacuous pass). When an expected-route set is supplied (CLI {@code --routes}),
 * the gate additionally requires every listed route to be fully covered, so a route that appears in NEITHER log fails the gate instead of
 * being silently unevaluated.
 */
public final class Report {

    /**
     * One {@code UNPAIRED} record's identity for the exit-gate failure listing: which side emitted it, its raw route, its correlation id.
     */
    public record Unpaired(String side, String route, String correlationId) {
    }

    private final Map<VerdictType, Integer> counts = new EnumMap<>(VerdictType.class);
    private final Map<String, Integer> divergencesByReason = new TreeMap<>();
    private final Map<String, Set<String>> coverage = new TreeMap<>();
    private final List<Unpaired> unpaired = new ArrayList<>();
    private int pathOnlyMatches;

    /**
     * Optional explicit route universe (canonical routes) the gate must see fully covered; {@code null} = check only observed paired
     * routes.
     */
    private final Set<String> expectedRoutes;

    /** A report whose gate checks only the canonical routes actually observed in paired records. */
    public Report() {
        this(null);
    }

    /**
     * A report whose gate additionally requires every route in {@code expectedRoutes} (canonical routes, one per line of the
     * {@code --routes} file) to be fully covered -- closing the "route absent from both logs is never evaluated" hole. {@code null} keeps
     * the observed-only behavior.
     */
    public Report(Set<String> expectedRoutes) {
        this.expectedRoutes = expectedRoutes == null ? null : new LinkedHashSet<>(expectedRoutes);
    }

    /** Folds one classified {@link Pair} into the running aggregate. Package-private: only {@link Reconciler} builds a {@link Report}. */
    void record(Verdict verdict, Pair pair, ReferenceMapping mapping) {
        counts.merge(verdict.type(), 1, Integer::sum);
        if (verdict.type() == VerdictType.DIVERGENCE) {
            divergencesByReason.merge(verdict.reason() == null ? "?" : verdict.reason(), 1, Integer::sum);
        }
        if (verdict.type() == VerdictType.UNPAIRED) {
            recordUnpaired(pair);
        }
        if (verdict.type() != VerdictType.DIVERGENCE && Classifier.PATH_ONLY_MATCH_REASON.equals(verdict.reason())) {
            pathOnlyMatches++;
        }
        if (creditsCoverage(verdict, pair)) {
            String canonicalRoute = mapping.canonical(pair.wf().targetService());
            coverage.computeIfAbsent(canonicalRoute, k -> new TreeSet<>()).add(pair.wf().decision());
        }
    }

    /** Coverage counts only fully-paired, non-divergent pairs carrying a WF decision. WF-only records grant no coverage (the C1 fix). */
    private static boolean creditsCoverage(Verdict verdict, Pair pair) {
        if (pair.gw() == null || pair.wf() == null || pair.wf().decision() == null) {
            return false;
        }
        return switch (verdict.type()) {
            case MATCH, EXPECTED_DIFF, INTENTIONAL_BEHAVIOR_CHANGE -> true;
            default -> false;
        };
    }

    private void recordUnpaired(Pair pair) {
        boolean gwPresent = pair.gw() != null;
        ShadowRecord present = gwPresent ? pair.gw() : pair.wf();
        unpaired.add(new Unpaired(gwPresent ? "GW" : "WF", present == null ? null : present.targetService(), pair.correlationId()));
    }

    /** Count of joined pairs per {@link VerdictType}. */
    public Map<VerdictType, Integer> counts() {
        return counts;
    }

    /** Count of {@code DIVERGENCE} verdicts, broken down by sub-reason (e.g. {@code "token"}, {@code "query-mismatch"}). */
    public Map<String, Integer> divergencesByReason() {
        return divergencesByReason;
    }

    /**
     * Canonical route -&gt; the set of WF decisions observed for it (e.g. {@code "active"}, {@code "inactive"}), from paired records only.
     */
    public Map<String, Set<String>> coverage() {
        return coverage;
    }

    /** The {@code UNPAIRED} records (side, raw route, correlation id) — a non-empty list fails the exit gate. */
    public List<Unpaired> unpaired() {
        return unpaired;
    }

    /**
     * Count of path-only matches: pairs whose GW query was absent by design (OBSERVE never buffers POST bodies) so the compare fell back to
     * path + decision. These still count toward coverage, but are surfaced separately so a human signing off sees how much of the evidence
     * is path-only rather than a full query match.
     */
    public int pathOnlyMatches() {
        return pathOnlyMatches;
    }

    /**
     * The observe-window exit gate: {@code true} iff no {@code DIVERGENCE} was observed, no {@code UNPAIRED} record exists, and every
     * required canonical route saw both an allow decision ({@code "active"}/{@code "allow"}) and a reject decision
     * ({@code "inactive"}/{@code "deny"}). The required routes are every route observed in a paired record, unioned with any explicit
     * {@code --routes} universe. An empty required set (no paired records and no expected routes) never passes — the gate proves decision
     * parity was actually exercised, not merely absent.
     */
    public boolean passesExitGate() {
        if (counts.getOrDefault(VerdictType.DIVERGENCE, 0) > 0) {
            return false;
        }
        if (counts.getOrDefault(VerdictType.UNPAIRED, 0) > 0) {
            return false;
        }
        Set<String> routesToCheck = new TreeSet<>(coverage.keySet());
        if (expectedRoutes != null) {
            routesToCheck.addAll(expectedRoutes);
        }
        if (routesToCheck.isEmpty()) {
            return false;
        }
        for (String route : routesToCheck) {
            if (!fullyCovered(route)) {
                return false;
            }
        }
        return true;
    }

    private boolean fullyCovered(String route) {
        Set<String> decisionsSeen = coverage.getOrDefault(route, Set.of());
        boolean sawAllow = decisionsSeen.contains("active") || decisionsSeen.contains("allow");
        boolean sawReject = decisionsSeen.contains("inactive") || decisionsSeen.contains("deny");
        return sawAllow && sawReject;
    }

    /** Renders a human-readable summary suitable for CLI stdout. */
    public String render() {
        StringBuilder sb = new StringBuilder("=== Shadow Parity Report ===\n");
        counts.forEach((verdictType, count) -> sb.append(verdictType).append(": ").append(count).append('\n'));
        if (pathOnlyMatches > 0) {
            sb.append("path-only matches (GW query absent by design; compared on path+decision only): ").append(pathOnlyMatches)
                .append('\n');
        }
        if (!divergencesByReason.isEmpty()) {
            sb.append("-- divergences by reason --\n");
            divergencesByReason.forEach((reason, count) -> sb.append("  ").append(reason).append(": ").append(count).append('\n'));
        }
        if (!unpaired.isEmpty()) {
            sb.append("-- unpaired records (one side emitted no counterpart) --\n");
            unpaired.forEach(
                u -> sb.append("  ").append(u.side()).append(' ').append(u.route()).append(" (").append(u.correlationId()).append(")\n")
            );
        }
        sb.append("-- decision coverage --\n");
        coverage.forEach((route, decisionsSeen) -> sb.append("  ").append(route).append(": ").append(decisionsSeen).append('\n'));
        if (expectedRoutes != null) {
            sb.append("-- required routes (--routes) --\n");
            expectedRoutes.forEach(
                route -> sb.append("  ").append(route).append(": ").append(fullyCovered(route) ? "covered" : "MISSING allow+reject")
                    .append('\n')
            );
        }
        sb.append("EXIT GATE: ").append(passesExitGate() ? "PASS" : "FAIL").append('\n');
        return sb.toString();
    }
}
