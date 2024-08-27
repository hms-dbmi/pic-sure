package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CategoricalConcept(
    String conceptPath, String name, String display, String dataset, String description,

    List<String> values,

    @Nullable
    List<Concept> children,

    @Nullable
    Map<String, String> meta

) implements Concept {

    public CategoricalConcept(CategoricalConcept core, Map<String, String> meta) {
        this(core.conceptPath, core.name, core.display, core.dataset, core.description, core.values, core.children, meta);
    }


    @JsonProperty("type")
    @Override
    public ConceptType type() {
        return ConceptType.Categorical;
    }

    @Override
    public boolean equals(Object object) {
        return conceptEquals(object);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conceptPath, dataset);
    }
}
