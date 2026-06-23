package edu.harvard.dbmi.avillach.dictionaryweights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class WeightingParser {
    private static final Logger LOG = LoggerFactory.getLogger(WeightingParser.class);
    private static final Set<String> VALID_TIERS = Set.of("A", "B", "C", "D");
    private static final Map<String, String> NUMERIC_TIER_MAP = Map.of("1", "A", "2", "B", "3", "C", "4", "D");

    public List<Weight> parseWeights(List<String> weights) {
        return weights.stream()
            .flatMap(this::parseWeight)
            .toList();
    }

    private Stream<Weight> parseWeight(String line) {
        String[] split = line.split(",");
        if (split.length != 2) {
            LOG.warn("Failed to parse line {}, expected two column CSV", line);
            return Stream.empty();
        }

        String raw = split[1].trim().toUpperCase();
        String tier = NUMERIC_TIER_MAP.getOrDefault(raw, raw);
        if (!VALID_TIERS.contains(tier)) {
            LOG.warn("Failed to parse line {} because {} is not a valid tier (A/B/C/D)", line, raw);
            return Stream.empty();
        }

        return Stream.of(new Weight(split[0].trim(), tier));
    }

}
