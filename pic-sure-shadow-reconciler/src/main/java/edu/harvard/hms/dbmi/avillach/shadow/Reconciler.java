package edu.harvard.hms.dbmi.avillach.shadow;

import java.util.List;
import java.util.Set;

/** Runs the full offline pipeline: joins GW/WF shadow records by correlation id, classifies each pair, and aggregates a {@link Report}. */
public final class Reconciler {

    private final ReferenceMapping mapping;
    private final Classifier classifier;

    public Reconciler(ReferenceMapping mapping) {
        this.mapping = mapping;
        this.classifier = new Classifier(mapping);
    }

    /**
     * Joins, classifies, and aggregates the given GW and WF shadow-record streams into a {@link Report} that checks only observed routes.
     */
    public Report run(List<ShadowRecord> gw, List<ShadowRecord> wf) {
        return run(gw, wf, null);
    }

    /**
     * Joins, classifies, and aggregates into a {@link Report}. When {@code expectedRoutes} is non-null, the exit gate additionally requires
     * every listed canonical route to be fully covered (a route absent from both logs fails the gate rather than being unevaluated).
     */
    public Report run(List<ShadowRecord> gw, List<ShadowRecord> wf, Set<String> expectedRoutes) {
        Report report = new Report(expectedRoutes);
        for (Pair pair : PairJoiner.join(gw, wf)) {
            report.record(classifier.classify(pair), pair, mapping);
        }
        return report;
    }
}
