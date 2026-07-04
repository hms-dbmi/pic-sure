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
 *
 * <p><b>Path-only comparison (I2).</b> By design the gateway's OBSERVE catch-all branch never buffers POST bodies, so a POST's GW
 * {@code query} is {@code null} while WildFly logs the parsed body. Comparing those strictly would classify every JSON POST as a
 * {@code DIVERGENCE("query-mismatch")} and make the exit gate unattainable. So the one case "GW query absent, WF query present" skips the
 * query dimension and compares on the remaining dimensions (target service, token) only. The result stays a normal agreement verdict
 * ({@code MATCH}/{@code EXPECTED_DIFF}/{@code INTENTIONAL_BEHAVIOR_CHANGE}) — NOT a new enum value (downstream contract) — but is tagged
 * with {@link #PATH_ONLY_MATCH_REASON} so {@link Report} can count it distinctly and a human signing off sees how much evidence is
 * path-only. A mismatched path is still a {@code DIVERGENCE}; both-sides-present keeps the strict compare; both-sides-null compares the
 * rest as before.
 */
public final class Classifier {

    /** Copied verbatim from the plan's Global Constraints "No-introspection skip-list". */
    private static final List<String> SKIP_PREFIXES =
        List.of("/info/", "/bin/continuous", "/logging", "/actuator", "/openapi", "/swagger-ui");

    /**
     * Reason tag stamped on an otherwise-agreeing verdict when the GW {@code query} was absent by design (OBSERVE never buffers POST
     * bodies) and only path/token could be compared. Surfaced by {@link Report#pathOnlyMatches()} as a distinct count; deliberately NOT a
     * new {@link VerdictType} (the reconciler verdict enum is a downstream contract).
     */
    static final String PATH_ONLY_MATCH_REASON = "path-only";

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

        // I2: "GW query absent (by design in OBSERVE), WF query present" is the one case we compare path-only, tagging the
        // agreement so Report can count it distinctly. Both-present -> strict compare; both-null -> compares equal below.
        boolean gwQueryAbsent = gw.query() == null || gw.query().isNull();
        boolean wfQueryPresent = wf.query() != null && !wf.query().isNull();
        boolean pathOnly = gwQueryAbsent && wfQueryPresent;

        if (!pathOnly && !JsonCanonical.equalIgnoringOrder(gw.query(), wf.query())) {
            boolean leak = JsonCanonical.containsResourceCredentials(gw.query()) || JsonCanonical.containsResourceCredentials(wf.query());
            return Verdict.of(VerdictType.DIVERGENCE, leak ? "resourceCredentials-leak" : "query-mismatch");
        }

        String reason = pathOnly ? PATH_ONLY_MATCH_REASON : null;
        String expected = mapping.canonical(wf.targetService());
        RouteMode mode = mapping.mode(wf.targetService());
        boolean targetServiceDiffersRaw = !Objects.equals(gw.targetService(), wf.targetService());

        if (mode == RouteMode.DECISION_AFFECTING && targetServiceDiffersRaw) {
            return new Verdict(VerdictType.INTENTIONAL_BEHAVIOR_CHANGE, reason);
        }
        if (Objects.equals(gw.targetService(), expected)) {
            boolean incidentalDiff = !Objects.equals(wf.targetService(), expected) || !Objects.equals(gw.ipAddress(), wf.ipAddress());
            return new Verdict(incidentalDiff ? VerdictType.EXPECTED_DIFF : VerdictType.MATCH, reason);
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
