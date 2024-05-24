package edu.harvard.dbmi.avillach.dictionary.concept.model;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

public record CategoricalConcept(
    String conceptPath, String name, String display, String dataset,

    List<String> values,

    @Nullable
    List<Concept> children,

    @Nullable
    Map<String, String> meta

) implements Concept {

    public CategoricalConcept(CategoricalConcept core, Map<String, String> meta) {
        this(core.conceptPath, core.name, core.display, core.dataset, core.values, core.children, core.meta);
    }


    @Override
    public ConceptType type() {
        return ConceptType.Categorical;
    }
}
