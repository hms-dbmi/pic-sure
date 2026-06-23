package edu.harvard.dbmi.avillach.dictionaryweights;

import java.util.Set;

public record Weight(String key, String tier) {
    private static final Set<String> VALID_TIERS = Set.of("A", "B", "C", "D");

    public Weight {
        if (tier == null || !VALID_TIERS.contains(tier)) {
            throw new IllegalArgumentException("Tier must be one of: A, B, C, D");
        }
    }
}
