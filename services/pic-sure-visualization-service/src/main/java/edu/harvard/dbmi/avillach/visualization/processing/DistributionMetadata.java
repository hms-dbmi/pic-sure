package edu.harvard.dbmi.avillach.visualization.processing;

import java.util.Set;

final class DistributionMetadata {

    static final Set<String> SKIP_KEYS = Set.of("\\_consents\\", "\\_harmonized_consent\\", "\\_topmed_consents\\", "\\_parent_consents\\");

    private DistributionMetadata() {}

    static String titleFor(String conceptPath) {
        String[] titleParts = conceptPath.split("\\\\");
        if (titleParts.length == 0) {
            return conceptPath;
        }
        String leaf = titleParts[titleParts.length - 1];
        String parent = titleParts.length >= 2 ? titleParts[titleParts.length - 2] : "";
        return parent.isEmpty() ? leaf : parent + ": " + leaf;
    }

    static String xAxisLabelFor(String conceptPath) {
        String[] titleParts = conceptPath.split("\\\\");
        if (titleParts.length == 0) {
            return conceptPath;
        }
        return titleParts[titleParts.length - 1];
    }
}
