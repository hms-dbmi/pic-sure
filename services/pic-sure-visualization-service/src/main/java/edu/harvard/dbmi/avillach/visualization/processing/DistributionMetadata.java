package edu.harvard.dbmi.avillach.visualization.processing;

import java.util.Set;

final class DistributionMetadata {

    static final Set<String> SKIP_KEYS = Set.of(
        "\\_consents\\",
        "\\_harmonized_consent\\",
        "\\_topmed_consents\\",
        "\\_parent_consents\\"
    );

    private DistributionMetadata() {}

    static String titleFor(String conceptPath) {
        String[] titleParts = conceptPath.split("\\\\");
        if (titleParts.length >= 2) {
            return (
                titleParts[titleParts.length - 2] +
                ": " +
                titleParts[titleParts.length - 1]
            );
        }
        return conceptPath;
    }

    static String xAxisLabelFor(String title) {
        int lastSpace = title.lastIndexOf(' ');
        if (lastSpace >= 0 && lastSpace < title.length() - 1) {
            return title.substring(lastSpace + 1);
        }
        return title;
    }
}
