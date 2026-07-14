package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ContinuousDistributionData;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContinuousDistributionProcessor {

    public List<ContinuousDistributionData> process(Map<String, Map<String, ObfuscatedCount>> crossCounts, boolean obfuscated) {
        List<ContinuousDistributionData> distributions = new ArrayList<>();
        for (Map.Entry<String, Map<String, ObfuscatedCount>> entry : crossCounts.entrySet()) {
            if (DistributionMetadata.SKIP_KEYS.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String title = DistributionMetadata.titleFor(entry.getKey());
            distributions.add(
                new ContinuousDistributionData(
                    entry.getKey(), title, true, new LinkedHashMap<>(entry.getValue()), obfuscated,
                    DistributionMetadata.xAxisLabelFor(entry.getKey()), "Number of Participants", null, null
                )
            );
        }
        return distributions;
    }
}
