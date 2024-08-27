package edu.harvard.dbmi.avillach.dictionary.concept.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

import java.util.Map;
import java.util.Objects;

public record ContinuousConcept(
    String conceptPath, String name, String display, String dataset, String description,

    @Nullable Integer min, @Nullable Integer max,
    Map<String, String> meta
) implements Concept {

    public ContinuousConcept(ContinuousConcept core, Map<String, String> meta) {
        this(core.conceptPath, core.name, core.display, core.dataset, core.description, core.min, core.max, meta);
    }

    @JsonProperty("type")
    @Override
    public ConceptType type() {
        return ConceptType.Continuous;
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
