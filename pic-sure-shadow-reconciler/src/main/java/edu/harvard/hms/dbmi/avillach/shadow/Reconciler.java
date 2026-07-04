package edu.harvard.hms.dbmi.avillach.shadow;

import java.util.List;

/** Runs the full offline pipeline: joins GW/WF shadow records by correlation id, classifies each pair, and aggregates a {@link Report}. */
public final class Reconciler {

    private final ReferenceMapping mapping;
    private final Classifier classifier;

    public Reconciler(ReferenceMapping mapping) {
        this.mapping = mapping;
        this.classifier = new Classifier(mapping);
    }

    /** Joins, classifies, and aggregates the given GW and WF shadow-record streams into a {@link Report}. */
    public Report run(List<ShadowRecord> gw, List<ShadowRecord> wf) {
        Report report = new Report();
        for (Pair pair : PairJoiner.join(gw, wf)) {
            report.record(classifier.classify(pair), pair, mapping);
        }
        return report;
    }
}
