package edu.harvard.dbmi.avillach.dictionary.concept.model;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

public record InteriorConcept (
    String conceptPath, String name, String display, String dataset,

    @Nullable
    List<Concept> children,
    Map<String, String> meta

) implements Concept {
    @Override
    public ConceptType type() {
        return ConceptType.Interior;
    }
}
