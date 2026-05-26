package edu.harvard.dbmi.avillach.visualization.processing;

import edu.harvard.dbmi.avillach.visualization.model.ContinuousDistributionData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContinuousDistributionProcessor {

    private final BinningService binningService;

    public ContinuousDistributionProcessor(BinningService binningService) {
        this.binningService = binningService;
    }

    public List<ContinuousDistributionData> process(Map<String, Map<String, Integer>> crossCounts, boolean obfuscated, boolean binValues) {
        Map<String, Map<String, Integer>> processed = binValues ? binningService.binContinuousData(crossCounts) : crossCounts;
        List<ContinuousDistributionData> distributions = new ArrayList<>();

        for (Map.Entry<String, Map<String, Integer>> entry : processed.entrySet()) {
            if (DistributionMetadata.SKIP_KEYS.contains(entry.getKey()) || entry.getValue().isEmpty()) {
                continue;
            }

            String title = DistributionMetadata.titleFor(entry.getKey());
            distributions.add(
                new ContinuousDistributionData(
                    title, true, new LinkedHashMap<>(entry.getValue()), obfuscated, DistributionMetadata.xAxisLabelFor(title),
                    "Number of Participants", null, null
                )
            );
        }

        return distributions;
    }
}
