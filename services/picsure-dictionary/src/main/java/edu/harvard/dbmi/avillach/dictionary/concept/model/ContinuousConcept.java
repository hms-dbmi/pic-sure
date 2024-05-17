package edu.harvard.dbmi.avillach.dictionary.concept.model;

import jakarta.annotation.Nullable;

import java.util.Map;

public record ContinuousConcept(
    String conceptPath, String name, String display, String dataset,

    @Nullable Integer min, @Nullable Integer max,
    Map<String, String> meta
) implements Concept {

    @Override
    public ConceptType type() {
        return ConceptType.Continuous;
    }
}
