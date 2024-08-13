package edu.harvard.dbmi.avillach.dictionaryweights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class WeightingParser {
    private static final Logger LOG = LoggerFactory.getLogger(WeightingParser.class);

    public List<Weight> parseWeights(List<String> weights) {
        return weights.stream()
            .flatMap(this::parseWeight)
            .toList();

    }

    private Stream<Weight> parseWeight(String line) {
        String[] split = line.split(",");
        if (split.length != 2) {
            LOG.warn("Failed to parse line {}, expected two column TSV", line);
            return Stream.empty();
        }

        int weight;
        try {
            weight = Integer.parseInt(split[1]);
        } catch (NumberFormatException ignored) {
            LOG.warn("Failed to parse line {} because {} is not an int", line, split[1]);
            return Stream.empty();
        }

        return Stream.of(new Weight(split[0], weight));
    }

}
