package edu.harvard.hms.dbmi.avillach.shadow;

import java.util.List;
import java.util.Objects;

/**
 * Classifies a joined {@link Pair} of shadow records into a {@link Verdict}, per the plan's Global Constraints and the Task 13 brief. Rules
 * are evaluated in order; the first matching rule wins:
 *
 * <ol> <li>Either record's {@code targetService} is on the no-introspection skip-list -&gt; {@code SKIP}. <li>Exactly one side is present
 * (the other is {@code null}) -&gt; {@code UNPAIRED}. <li>{@code tokenHash} mismatch -&gt; {@code DIVERGENCE("token")}. <li>{@code query}
 * differs (order-insensitive) -&gt; {@code DIVERGENCE("resourceCredentials-leak")} if either side still carries
 * {@code resourceCredentials}, else {@code DIVERGENCE("query-mismatch")}. <li>Target-service comparison against the independent
 * {@link ReferenceMapping}: a {@code DECISION_AFFECTING} route where GW and WF raw target services differ is an
 * {@code INTENTIONAL_BEHAVIOR_CHANGE}; if the gateway's target service equals the canonical expectation it is a {@code MATCH} (or
 * {@code EXPECTED_DIFF} when WF's raw target service or {@code ipAddress} still differs from canonical — i.e. an incidental, cosmetic
 * difference); otherwise it is a {@code DIVERGENCE("target-service")}. </ol>
 */
public final class Classifier {

    /** Copied verbatim from the plan's Global Constraints "No-introspection skip-list". */
    private static final List<String> SKIP_PREFIXES =
        List.of("/info/", "/bin/continuous", "/logging", "/actuator", "/openapi", "/swagger-ui");

    private final ReferenceMapping mapping;

    public Classifier(ReferenceMapping mapping) {
        this.mapping = mapping;
    }

    public Verdict classify(Pair pair) {
        if (onSkipList(pair.gw()) || onSkipList(pair.wf())) {
            return Verdict.of(VerdictType.SKIP);
        }
        if (pair.gw() == null || pair.wf() == null) {
            return Verdict.of(VerdictType.UNPAIRED);
        }

        ShadowRecord gw = pair.gw();
        ShadowRecord wf = pair.wf();

        if (!Objects.equals(gw.tokenHash(), wf.tokenHash())) {
            return Verdict.of(VerdictType.DIVERGENCE, "token");
        }

        if (!JsonCanonical.equalIgnoringOrder(gw.query(), wf.query())) {
            boolean leak = JsonCanonical.containsResourceCredentials(gw.query()) || JsonCanonical.containsResourceCredentials(wf.query());
            return Verdict.of(VerdictType.DIVERGENCE, leak ? "resourceCredentials-leak" : "query-mismatch");
        }

        String expected = mapping.canonical(wf.targetService());
        RouteMode mode = mapping.mode(wf.targetService());
        boolean targetServiceDiffersRaw = !Objects.equals(gw.targetService(), wf.targetService());

        if (mode == RouteMode.DECISION_AFFECTING && targetServiceDiffersRaw) {
            return Verdict.of(VerdictType.INTENTIONAL_BEHAVIOR_CHANGE);
        }
        if (Objects.equals(gw.targetService(), expected)) {
            boolean incidentalDiff = !Objects.equals(wf.targetService(), expected) || !Objects.equals(gw.ipAddress(), wf.ipAddress());
            return Verdict.of(incidentalDiff ? VerdictType.EXPECTED_DIFF : VerdictType.MATCH);
        }
        return Verdict.of(VerdictType.DIVERGENCE, "target-service");
    }

    private boolean onSkipList(ShadowRecord record) {
        if (record == null) {
            return false;
        }
        String targetService = record.targetService();
        return targetService != null && SKIP_PREFIXES.stream().anyMatch(targetService::startsWith);
    }
}
